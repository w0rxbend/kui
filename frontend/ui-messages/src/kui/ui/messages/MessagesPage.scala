package kui.ui.messages

import java.time.Instant

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.kernel.{ClusterId, TopicName}
import kui.message.contract.{BrowseAddress, MessageDto}
import kui.ui.kernel.api.ApiClient
import kui.ui.kernel.component.*
import kui.ui.kernel.file.Download
import kui.ui.kernel.query.UrlParams
import kui.ui.kernel.sse.{SseConnection, SseError}
import kui.ui.messages.browse.{BrowseQuery, BrowseSession}
import kui.ui.messages.filter.FilterEditor
import kui.ui.messages.produce.{ProduceDraft, ProduceDrawer, ResendDrawer, ResendTarget}
import kui.ui.messages.row.RecordTable
import kui.ui.messages.table.{FlatTable, FlattenLimits, JsonFlattener, RecordCsv, RecordSource}

/** The message browser: the screen the product is used on more than any other.
  *
  * ## Nothing is read until somebody asks
  *
  * Opening the screen does not start a browse. Every other screen in KUI fetches on mount because its answer
  * is a fixed, small document; a browse is a Kafka consumer reading an unbounded log, and starting one for
  * anybody who so much as clicked the wrong tab is how a cluster ends up with consumers nobody asked for. So
  * the controls are drawn, the empty state says what to do, and Read starts it.
  *
  * ## Where it starts is a choice, and the choice is in the URL
  *
  * Beginning, end, an offset, or a time — the four ways anybody actually describes where to look. All of them
  * live in the query string under the *service's own* parameter names, which means a browse is a link: an
  * operator who has found the record that matters sends the URL and their colleague sees the same records. It
  * also means the Back button undoes a filter, which is what a browser's Back button is for.
  *
  * The per-partition seek the contract also supports has no control of its own — it would be a table of
  * inputs, one per partition — but it survives a URL round trip, so a link carrying one keeps working.
  *
  * ## Following live
  *
  * Follow starts at the end and keeps the stream open. It is exclusive with a start position and the server
  * refuses the combination, so pressing it *sets* the seek to the end rather than sending both.
  *
  * ## Stopping is real
  *
  * Stop aborts the request, and that abort travels: the gateway's stream is cancelled, the service's fiber is
  * cancelled and its Kafka consumer is closed (ADR-035). Unmounting the element does the same thing, so
  * navigating away cannot leave a consumer running — which is the entire reason this screen streams over an
  * abortable fetch rather than the browser's own `EventSource`.
  *
  * ## Writing, and the two verbs it needs
  *
  * Publish opens a form; an open record offers two more actions, and they are deliberately two rather than
  * one, because they are different operations that produce different records.
  *
  *   - **Republish** opens the same publish form holding what the record contains, editable. What lands is a
  *     new record you composed, re-encoded through a serde.
  *   - **Copy to another topic** sends the record's *bytes* somewhere else, untouched: nothing is decoded and
  *     nothing is re-encoded, so it works on a topic KUI cannot read and the copy is indistinguishable from
  *     the original. This is the operation Kouncil has and the other reference products do not.
  *
  * Both go through the service's mutation endpoints, which refuse a read-only cluster before touching a Kafka
  * client and write an audit record either way (ADR-047). Neither carries a plan token: ADR-045's question is
  * "what will this do to what is already there", and appending to a log has no answer to it — the honest
  * equivalent is the receipt, which is what these forms show.
  *
  * ## What is still deliberately absent
  *
  * Purge. It is the destructive operation on this screen, the one ADR-045's plan token exists for, and the
  * service does not serve it yet. No button is rendered for it, not even a disabled one: a disabled control
  * for something that cannot be honoured end to end is a promise with a date on it (DEVPLAN §10 D8).
  */
object MessagesPage {

  /** The serdes the browse bar's two pickers offer, defined once for both directions in [[SerdeChoices]] —
    * the publish form offers the same list, and the two must not drift.
    */
  val Serdes: List[(String, String)] = SerdeChoices.options

  /** The four starts the screen offers, as `(value, label)`. The values are the ones `BrowseQuery` reads. */
  val StartKinds: List[(String, String)] =
    List(
      "latest" -> Messages.SeekLatest,
      "beginning" -> Messages.SeekBeginning,
      "offset" -> Messages.SeekOffset,
      "timestamp" -> Messages.SeekTimestamp
    )

  def apply(
      topic: TopicName,
      cluster: ClusterId,
      api: ApiClient,
      zone: Signal[String],
      session: BrowseSession
  ): HtmlElement = {

    /** What the two drawers are showing. `None` is closed.
      *
      * They are owned here, one of each, rather than by the row that opens them: a drawer per open record
      * would mean a table with ten records open holds ten focus traps, and only one of them can be visible
      * anyway. Republishing from a row is therefore "put this record's contents in the draft", which is also
      * exactly what the Publish button does with an empty one.
      */
    val draft: Var[Option[ProduceDraft]] = Var(None)
    val resendTarget: Var[Option[ResendTarget]] = Var(None)

    /** Which of the table view's columns the user has put away.
      *
      * Owned here rather than inside the table because two things read it: the table, which does not draw
      * them, and the export, which must not write them. A file that came back with the columns somebody had
      * just hidden would be an export of a screen they are not looking at.
      */
    val hiddenColumns: Var[Set[String]] = Var(Set.empty)

    // --- The browse, read out of the URL ----------------------------------------------------------

    /** Every parameter of a browse, assembled from the address bar.
      *
      * `seekTo` and `partition` repeat on the wire and `UrlParams` holds one value per name, so a stored
      * value carrying several is split on commas — the spelling the contract's own codec accepts alongside
      * the repeated form, so a URL written either way reads back the same.
      */
    val query: Signal[BrowseQuery] =
      Signal
        .combine(
          UrlParams.signal(BrowseAddress.SeekParam),
          UrlParams.signal(BrowseAddress.PartitionParam),
          UrlParams.signal(BrowseAddress.LimitParam),
          UrlParams.signal(BrowseAddress.QueryParam),
          UrlParams.signal(BrowseAddress.LiveParam),
          UrlParams.signal(BrowseAddress.KeySerdeParam),
          UrlParams.signal(BrowseAddress.ValueSerdeParam),
          UrlParams.signal(BrowseAddress.FilterIdParam),
          UrlParams.signal(BrowseAddress.FilterSourceParam)
        )
        .map((seek, partitions, limit, contains, live, keySerde, valueSerde, filterId, filterSource) =>
          BrowseQuery.fromParams(
            Map(
              BrowseAddress.SeekParam -> split(seek),
              BrowseAddress.PartitionParam -> split(partitions),
              BrowseAddress.LimitParam -> limit.toList,
              BrowseAddress.QueryParam -> contains.toList,
              BrowseAddress.LiveParam -> live.toList,
              BrowseAddress.KeySerdeParam -> keySerde.toList,
              BrowseAddress.ValueSerdeParam -> valueSerde.toList,
              BrowseAddress.FilterIdParam -> filterId.toList,
              BrowseAddress.FilterSourceParam -> filterSource.toList
            )
          )
        )

    /** Which view is on screen, read out of the URL like everything else this screen chooses.
      *
      * Anything that is not `table` is the list, including a value from a link written by a future version —
      * an unreadable setting should cost the recipient that setting and not the page.
      */
    val view: Signal[String] =
      UrlParams
        .signal(Messages.ViewParam)
        .map(raw => if raw.contains(Messages.ViewTable) then Messages.ViewTable else Messages.ViewList)

    val startKind: Signal[String] = query.map(current => BrowseQuery.startKind(current.seek))
    val running: Signal[Boolean] = session.running

    /** Changing anything about *where* to read stops whatever is running.
      *
      * A browse in flight is reading a different range from the one the controls now describe, and letting it
      * keep delivering into the table would mix two ranges in one list with nothing on screen to say why.
      */
    def rewrite(updates: Map[String, Option[String]]): Unit = {
      session.stop()
      UrlParams.set(updates)
    }

    val pressed = new EventBus[Unit]

    /** "Load more" is a second way to start a browse, so it gets a bus of its own rather than sharing the
      * Read button's: the two do different things with the rows already on screen — Read replaces them, this
      * appends to them — and one bus would have to carry which.
      */
    val more = new EventBus[Unit]

    div(
      cls := MessagesCss.Page,
      dataAttr("testid") := "page-messages",
      // The topic is the heading, not the word "Messages": the reader knows what screen they are on and what
      // they need to be sure of is which topic they are reading.
      h1(topic.value),
      controls(query, startKind, running, rewrite, session, pressed, draft, topic, view),
      // The smart filter, under the control bar rather than on it: it is a paragraph of expression with
      // its own help and its own failure, and a multi-line box wedged between two dropdowns would make the
      // bar unreadable for the sake of a control most browses do not use.
      FilterEditor(cluster, api, query.map(_.filterSource), rewrite),
      // The Read button's subscription. `session.start` returns the browse's own events and binding them to
      // this element is what gives them a lifetime — a stream nothing is subscribed to is a request opened
      // and then ignored.
      pressed.events.sample(query).flatMapSwitch(session.start) --> Observer[Unit](_ => ()),
      more.events.flatMapSwitch(_ => session.loadMore()) --> Observer[Unit](_ => ()),
      statusLine(session, running),
      // One set of rows, two renderings of it. The view switch does not touch the browse — the records
      // already on screen are the same records either way — which is why it writes the URL directly
      // instead of going through `rewrite`, whose job is to stop a browse that no longer matches its
      // controls.
      child <-- view.map {
        case Messages.ViewTable =>
          FlatTable(
            records = session.rows,
            zone = zone,
            empty = emptyState(session, running),
            hidden = hiddenColumns,
            testId = Some("messages-grid")
          )
        case _ =>
          RecordTable(
            records = session.rows,
            zone = zone,
            empty = emptyState(session, running),
            testId = Some("messages-table"),
            actions = record => recordActions(topic, record, draft, resendTarget)
          )
      },
      ProduceDrawer(cluster, draft, api),
      ResendDrawer(cluster, resendTarget, api),
      // Only after a browse has finished, and only when the *server* sent a continuation with it. It omits
      // one whenever asking again would be pointless, so this is the server's answer to "is there more"
      // rather than the browser guessing from a full page — which is the guess that would put this button
      // under the last page of every topic.
      child.maybe <-- session.canLoadMore.map(
        Option.when(_)(
          Button(
            label = Val(Messages.LoadMore),
            onClick = Observer[Unit](_ => more.writer.onNext(())),
            variant = ButtonVariant.Secondary,
            testId = Some("messages-load-more")
          )
        )
      ),
      // The export, under the table rather than on the control bar: it is about what has already been read
      // rather than about what to read next, and a control that exports an empty screen would be offering
      // to hand somebody a file with a header row in it.
      child.maybe <-- session.rows
        .combineWith(view)
        .map((rows, current) =>
          Option.when(rows.nonEmpty)(
            Button(
              label = Val(Messages.ExportCsv),
              onClick = Observer[Unit](_ => exportCsv(topic, rows, current, hiddenColumns.now())),
              variant = ButtonVariant.Secondary,
              testId = Some("messages-export")
            ).amend(title := Messages.ExportHint)
          )
        ),
      // Navigating away must not leave a Kafka consumer running on the service. This is the browser half of
      // the milestone's cancellation criterion.
      onUnmountCallback(_ => session.stop())
    )
  }

  /** The control bar: where to start, which partitions, what text must appear, follow, and Read. */
  private def controls(
      query: Signal[BrowseQuery],
      startKind: Signal[String],
      running: Signal[Boolean],
      rewrite: Map[String, Option[String]] => Unit,
      session: BrowseSession,
      pressed: EventBus[Unit],
      draft: Var[Option[ProduceDraft]],
      topic: TopicName,
      view: Signal[String]
  ): HtmlElement =
    div(
      cls := MessagesCss.Controls,
      dataAttr("testid") := "messages-controls",
      field(
        Messages.SeekLabel,
        select(
          cls := KernelCssField,
          dataAttr("testid") := "messages-start",
          StartKinds.map((value, text) => option(L.value := value, text)),
          // `controlled`, so a start arriving from the URL while the menu is being changed cannot leave the
          // DOM and the address bar disagreeing.
          controlled(
            L.value <-- startKind,
            onChange.mapToValue --> { kind =>
              rewrite(
                Map(
                  BrowseAddress.SeekParam -> seekParam(kind),
                  // Following is a start position of its own, so choosing any other start turns it off
                  // rather than sending a combination the server refuses.
                  BrowseAddress.LiveParam -> None
                )
              )
            }
          )
        )
      ),
      // The offset and time fields appear only for the start they belong to. A field that is present but
      // meaningless is one somebody fills in and then wonders why nothing happened.
      child.maybe <-- startKind.map(kind =>
        Option.when(kind == "offset")(
          field(
            "",
            textBox(
              value = query.map(current => BrowseQuery.offsetOf(current.seek).fold("")(_.toString)),
              placeholder = Messages.OffsetPlaceholder,
              testId = "messages-offset",
              onEntered = raw =>
                rewrite(
                  Map(BrowseAddress.SeekParam -> raw.trim.toLongOption.map(offset => s"offset::$offset"))
                )
            )
          )
        )
      ),
      child.maybe <-- startKind.map(kind =>
        Option.when(kind == "timestamp")(
          field(
            "",
            textBox(
              value = query.map(current => BrowseQuery.timestampOf(current.seek).fold("")(_.toString)),
              placeholder = Messages.TimestampPlaceholder,
              testId = "messages-timestamp",
              onEntered = raw =>
                rewrite(
                  Map(BrowseAddress.SeekParam -> parseTimestamp(raw).map(millis => s"timestamp::$millis"))
                )
            )
          )
        )
      ),
      field(
        Messages.PartitionsLabel,
        textBox(
          value = query.map(_.partitions.map(_.value.toString).mkString(",")),
          placeholder = Messages.PartitionsPlaceholder,
          testId = "messages-partitions",
          onEntered = raw => rewrite(Map(BrowseAddress.PartitionParam -> Option(raw.trim).filter(_.nonEmpty)))
        ).amend(title := Messages.PartitionsHint)
      ),
      field(
        Messages.FilterLabel,
        textBox(
          value = query.map(_.contains.getOrElse("")),
          placeholder = Messages.FilterPlaceholder,
          testId = "messages-contains",
          onEntered = raw => rewrite(Map(BrowseAddress.QueryParam -> Option(raw.trim).filter(_.nonEmpty)))
        )
      ),
      serdePicker(
        label = Messages.KeySerdeLabel,
        chosen = query.map(_.keySerde.map(_.value).getOrElse("")),
        testId = "messages-key-serde",
        onChosen = raw => rewrite(Map(BrowseAddress.KeySerdeParam -> Option(raw).filter(_.nonEmpty)))
      ),
      serdePicker(
        label = Messages.ValueSerdeLabel,
        chosen = query.map(_.valueSerde.map(_.value).getOrElse("")),
        testId = "messages-value-serde",
        onChosen = raw => rewrite(Map(BrowseAddress.ValueSerdeParam -> Option(raw).filter(_.nonEmpty)))
      ),
      L.label(
        cls := MessagesCss.ControlLabel,
        title := Messages.LiveHint,
        input(
          tpe := "checkbox",
          dataAttr("testid") := "messages-live",
          controlled(
            checked <-- query.map(_.live),
            onInput.mapToChecked --> { on =>
              rewrite(
                Map(
                  BrowseAddress.LiveParam -> Option.when(on)("true"),
                  // Following means "from the end", and saying so in the URL keeps the link honest.
                  BrowseAddress.SeekParam -> Option.when(on)("latest")
                )
              )
            }
          )
        ),
        Messages.LiveLabel
      ),
      // One button with two jobs, because Read and Stop are never both available and two buttons of which
      // one is always disabled is a bar with a dead control on it.
      Button(
        label = running.map(current => if current then Messages.Stop else Messages.Read),
        onClick = Observer[Unit](_ => ()),
        variant = ButtonVariant.Primary,
        testId = Some("messages-read")
      ).amend(
        // The click is routed by what the stream is doing at the moment of the press, sampled rather than
        // closed over, so a browse that finished between renders is not stopped instead of restarted.
        onClick.compose(_.sample(running)) --> { isRunning =>
          if isRunning then session.stop() else pressed.writer.onNext(())
        }
      ),
      // Pause is offered only while a tail is actually running, because it is meaningless anywhere else:
      // a bounded browse ends on its own within seconds, and pausing one would hold back records the user
      // is about to be shown anyway. A control that is present but inert is what this whole screen's
      // Follow checkbox used to be.
      child.maybe <-- running
        .combineWith(query.map(_.live), session.paused)
        .map((isRunning, isLive, isPaused) =>
          Option.when(isRunning && isLive)(
            Button(
              label = Val(if isPaused then Messages.Resume else Messages.Pause),
              onClick = Observer[Unit](_ => session.setPaused(!isPaused)),
              variant = ButtonVariant.Secondary,
              testId = Some("messages-pause")
            ).amend(title := Messages.PauseHint)
          )
        ),
      // The view switch. It is on the control bar with everything else that decides what is on screen,
      // and it is a pair of buttons rather than a menu because there are two of them and which one is on
      // has to be legible without opening anything.
      div(
        cls := MessagesCss.ViewSwitch,
        title := Messages.ViewHint,
        viewButton(Messages.ViewList, Messages.ViewListLabel, view, "messages-view-list"),
        viewButton(Messages.ViewTable, Messages.ViewTableLabel, view, "messages-view-table")
      ),
      // Publish is on the control bar and not beside Read, because it is the screen's other job rather
      // than a variant of its first one. Secondary, so that the primary action on a reading screen stays
      // the one that reads.
      Button(
        label = Val(Messages.Publish),
        onClick = Observer[Unit](_ => draft.set(Some(ProduceDraft.empty(topic)))),
        variant = ButtonVariant.Secondary,
        testId = Some("messages-publish")
      )
    )

  /** The two things worth doing to a record that is open on the screen.
    *
    * Both only fill in a `Var`; the drawers are already mounted and react to it. That is what keeps the
    * request logic out of the table and lets a row disappear — a live tail redrawing — without taking a
    * half-finished form with it.
    */
  private def recordActions(
      topic: TopicName,
      record: kui.message.contract.MessageDto,
      draft: Var[Option[ProduceDraft]],
      resendTarget: Var[Option[ResendTarget]]
  ): List[HtmlElement] =
    List(
      Button(
        label = Val(Messages.Republish),
        onClick = Observer[Unit](_ => draft.set(Some(ProduceDraft.of(topic, record)))),
        variant = ButtonVariant.Secondary,
        size = Size.Sm,
        testId = Some(s"record-${record.partition.value}-${record.offset.value}-republish")
      ).amend(title := Messages.RepublishHint),
      Button(
        label = Val(Messages.Resend),
        onClick = Observer[Unit](_ => resendTarget.set(Some(ResendTarget.of(topic, record)))),
        variant = ButtonVariant.Secondary,
        size = Size.Sm,
        testId = Some(s"record-${record.partition.value}-${record.offset.value}-resend")
      ).amend(title := Messages.ResendHint)
    )

  /** Hands the user the records on screen as a CSV file.
    *
    * Which shape depends on which view they are in, because the two views are two different answers to "what
    * is on screen": the list view exports the record, and the table view exports its grid — the columns the
    * flattener produced, minus the ones the user put away. Exporting the grid as raw JSON would throw away
    * the work of choosing those columns; exporting the list view as a hundred flattened columns would produce
    * a file that has nothing to do with the screen it came from.
    */
  private def exportCsv(
      topic: TopicName,
      rows: List[MessageDto],
      view: String,
      hidden: Set[String]
  ): Unit = {
    val content =
      if view == Messages.ViewTable then {
        val limits = FlattenLimits.Default
        val flattened =
          rows
            .take(limits.maxRows.max(0))
            .toVector
            .map(record => JsonFlattener.flatten(RecordSource.of(record), limits))

        val paths = JsonFlattener.columns(flattened, limits).filterNot(hidden.contains).toList

        RecordCsv.ofGrid(rows.take(limits.maxRows.max(0)), paths, limits)
      } else RecordCsv.ofRecords(rows)

    Download.text(
      name = RecordCsv.fileName(topic.value, Instant.now()),
      mediaType = Messages.CsvMediaType,
      content = content,
      // Excel on Windows reads a plain UTF-8 CSV as the system code page, which turns every non-ASCII
      // character in a Kafka payload into mojibake. A topic with German or Japanese text in it is the
      // ordinary case, and every other reader ignores the mark.
      withBom = true
    )
  }

  /** One of the two view buttons. The one that is already on is drawn as the primary, which is how the rest
    * of KUI shows a chosen option, and pressing it again is harmless.
    *
    * It writes the URL directly rather than through `rewrite`: switching view does not change what was asked
    * of Kafka, and stopping a running tail because somebody wanted to look at the same records in columns
    * would be a control with a hidden cost.
    */
  private def viewButton(
      value: String,
      label: String,
      view: Signal[String],
      testId: String
  ): HtmlElement =
    div(
      child <-- view.map(current =>
        Button(
          label = Val(label),
          onClick = Observer[Unit](_ => UrlParams.set(Map(Messages.ViewParam -> Some(value)))),
          // The view that is on is the primary one. KUI has no "pressed" button variant, and a pair in
          // which neither is emphasised is a pair whose state has to be worked out from the screen.
          variant = if current == value then ButtonVariant.Primary else ButtonVariant.Secondary,
          testId = Some(testId)
        )
      )
    )

  /** What the stream is doing, in words.
    *
    * The scanned count is on it whenever the service has sent one, and it is the number that makes a filtered
    * browse interpretable: without it, "read a million records and matched none of them" and "the topic is
    * empty" are the same screen.
    */
  private def statusLine(session: BrowseSession, running: Signal[Boolean]): HtmlElement =
    div(
      cls := MessagesCss.Status,
      dataAttr("testid") := "messages-status",
      // `role="status"`, so a screen reader hears the browse finish rather than having to go and look.
      role := "status",
      child.text <-- session.progress
        .combineWith(running, session.held)
        .map((progress, isRunning, held) => summary(progress, isRunning, held)),
      // A phase is what the stream is doing before the first record arrives — resolving a profile, seeking —
      // and on a large or a sick cluster each of those takes seconds. Without it, a slow browse and a hung
      // one look identical.
      child.maybe <-- session.progress.map(_.phase.map(name => span(cls := MessagesCss.StatusPhase, name))),
      child.maybe <-- session.progress.map(
        _.failure.map(failure => span(cls := MessagesCss.Error, sentence(failure)))
      )
    )

  private[messages] def summary(progress: browse.BrowseProgress, running: Boolean, held: Int = 0): String = {
    val state =
      progress.connection match {
        case SseConnection.Connecting => Messages.Connecting
        case SseConnection.Open => Messages.Streaming
        case SseConnection.Reconnecting(_) => Messages.Connecting
        case SseConnection.Closed(_) => if progress.records > 0 then Messages.Finished else ""
      }

    val counts =
      List(
        Option.when(progress.records > 0 || running)(Messages.delivered(progress.records)),
        // Only when something is actually being held: a "0 records waiting" on every unpaused screen is
        // noise, and the number is here to explain a table that has stopped moving on purpose.
        Option.when(held > 0)(Messages.waiting(held)),
        progress.consumed.map(consumed => Messages.scanned(consumed.records))
      ).flatten

    (state :: counts).filter(_.nonEmpty).mkString(" · ")
  }

  /** Which of the empty states to draw, which depends on what has happened and not only on the row count.
    *
    * "Press Read", "records were read and none matched" and "there are no records here" are three different
    * situations with three different next steps, and rendering all of them as "no messages" is how a user
    * concludes a topic is empty when their filter is simply too narrow.
    */
  private def emptyState(session: BrowseSession, running: Signal[Boolean]): Signal[HtmlElement] =
    session.progress
      .combineWith(running)
      .map { (progress, isRunning) =>
        val scanned = progress.consumed.map(_.records).getOrElse(0L)

        if isRunning then
          EmptyState(
            Messages.Connecting,
            description = progress.phase,
            testId = Some("messages-waiting")
          )
        else if progress.consumed.isEmpty then
          EmptyState(
            Messages.EmptyTitle,
            description = Some(Messages.EmptyDescription),
            testId = Some("messages-empty")
          )
        else if scanned > 0L then
          EmptyState(
            Messages.NothingMatchedTitle,
            description = Some(Messages.NothingMatchedDescription),
            testId = Some("messages-nothing-matched")
          )
        else
          EmptyState(
            Messages.ExhaustedTitle,
            description = Some(Messages.ExhaustedDescription),
            testId = Some("messages-exhausted")
          )
      }

  /** A labelled control. */
  /** One serde override, as a menu.
    *
    * Changing it rewrites the URL, which stops whatever browse is running — the same rule every other control
    * on this bar follows, and for the same reason: the records already in the table were decoded the old way,
    * and appending differently decoded ones to them would produce a list nothing on screen explains.
    */
  private def serdePicker(
      label: String,
      chosen: Signal[String],
      testId: String,
      onChosen: String => Unit
  ): HtmlElement =
    field(
      label,
      select(
        cls := KernelCssField,
        dataAttr("testid") := testId,
        Serdes.map((value, text) => option(L.value := value, text)),
        // `controlled`, so a serde arriving from a pasted URL while the menu is open cannot leave the DOM
        // and the address bar disagreeing.
        controlled(L.value <-- chosen, onChange.mapToValue --> { raw => onChosen(raw) })
      )
    )

  private def field(name: String, control: HtmlElement): HtmlElement =
    div(
      cls := MessagesCss.ControlGroup,
      Option.when(name.nonEmpty)(span(cls := MessagesCss.ControlLabel, name)),
      control
    )

  /** A text box whose value comes from the URL and whose changes go back to it.
    *
    * `onChange` and not `onInput`: these fields change *where a browse starts*, and rewriting the URL on
    * every keystroke would push a history entry per character and stop the browse four times while somebody
    * typed an offset. `change` fires once, when the field is left or Enter is pressed, which is the moment a
    * typed offset is actually meant.
    *
    * And therefore **not** `controlled`. Laminar's controlled inputs pair a value with the event that reports
    * every edit, and it rejects `change` on a text field at run time rather than at compile time:
    * `ObserverError: Can not add input controller (prop: value + event: change)`. That exception was thrown
    * while the page was mounting, so the whole message browser rendered as "Something went wrong" and not a
    * single record was ever drawn. A plain `value <-- signal` binding gives what is actually wanted here: the
    * URL writes the field, the field writes the URL when the user is done, and nothing tries to police the
    * keystrokes in between.
    */
  private def textBox(
      value: Signal[String],
      placeholder: String,
      testId: String,
      onEntered: String => Unit
  ): HtmlElement =
    input(
      tpe := "text",
      cls := KernelCssField,
      L.placeholder := placeholder,
      dataAttr("testid") := testId,
      L.value <-- value,
      onChange.mapToValue --> { raw => onEntered(raw) }
    )

  /** The `seekTo` value for a start that needs no number of its own.
    *
    * An offset or a time is not a complete seek until the user has typed one, and sending `offset::0` on
    * their behalf would silently read the whole topic from its beginning.
    */
  private[messages] def seekParam(kind: String): Option[String] =
    kind match {
      case "beginning" => Some("beginning")
      case "latest" => Some("latest")
      case _ => None
    }

  /** A time the user typed, as epoch milliseconds.
    *
    * Both spellings are accepted — epoch milliseconds, and the `2026-09-04T09:15` a browser's own date field
    * produces — because an operator correlating with a log line has one of them and an operator using a
    * picker has the other, and refusing either would be a difference with no meaning behind it. A local time
    * with no zone is read as UTC, which is what the rest of KUI defaults to and what the field's placeholder
    * shows.
    */
  private[messages] def parseTimestamp(raw: String): Option[Long] = {
    val trimmed = raw.trim

    if trimmed.isEmpty then None
    else
      trimmed.toLongOption.orElse {
        val withSeconds = if trimmed.count(_ == ':') == 1 then s"$trimmed:00" else trimmed
        val withZone = if withSeconds.endsWith("Z") then withSeconds else s"${withSeconds}Z"
        scala.util.Try(java.time.Instant.parse(withZone)).toOption.map(_.toEpochMilli)
      }
  }

  /** One stored parameter value, as the list of values it stands for. */
  private[messages] def split(raw: Option[String]): List[String] =
    raw.toList.flatMap(_.split(',').toList).map(_.trim).filter(_.nonEmpty)

  private def sentence(failure: SseError): String =
    failure match {
      case SseError.Decode(event, cause) => s"A '$event' event could not be read: $cause"
      case SseError.Transport(cause) => s"The stream stopped: $cause"
      // Not `envelope.message` verbatim. A broker that has gone away reaches this line as
      // "kafka answered with status 502", which names a status no Kafka broker can return and never
      // mentions the actual problem. The rule lives in the kernel so that the browse status line and every
      // other error surface say the same thing about the same failure.
      case SseError.Server(envelope) =>
        kui.ui.kernel.api.UserFacing.sentence(envelope.code, envelope.message)
    }

  /** The kernel's own control class, so these fields look like every other field in KUI rather than like a
    * second set of inputs with one user.
    */
  private def KernelCssField: String = kui.ui.kernel.css.KernelCss.FieldControl
}
