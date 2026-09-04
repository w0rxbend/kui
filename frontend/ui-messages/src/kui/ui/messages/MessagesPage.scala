package kui.ui.messages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.kernel.TopicName
import kui.message.contract.BrowseAddress
import kui.ui.kernel.component.*
import kui.ui.kernel.query.UrlParams
import kui.ui.kernel.sse.{SseConnection, SseError}
import kui.ui.messages.browse.{BrowseQuery, BrowseSession}
import kui.ui.messages.row.RecordTable

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
  * ## What is deliberately absent
  *
  * No Produce, no Resend, no Purge. Those are mutations governed by ADR-045's plan-token confirmation and
  * ADR-047's read-only refusal and audit trail, and the message service serves none of them yet. No button is
  * rendered for them, not even a disabled one: a disabled control for something that cannot be honoured end
  * to end is a promise with a date on it (DEVPLAN §10 D8).
  */
object MessagesPage {

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
      zone: Signal[String],
      session: BrowseSession
  ): HtmlElement = {

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
          UrlParams.signal(BrowseAddress.LiveParam)
        )
        .map((seek, partitions, limit, contains, live) =>
          BrowseQuery.fromParams(
            Map(
              BrowseAddress.SeekParam -> split(seek),
              BrowseAddress.PartitionParam -> split(partitions),
              BrowseAddress.LimitParam -> limit.toList,
              BrowseAddress.QueryParam -> contains.toList,
              BrowseAddress.LiveParam -> live.toList
            )
          )
        )

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

    div(
      cls := MessagesCss.Page,
      dataAttr("testid") := "page-messages",
      // The topic is the heading, not the word "Messages": the reader knows what screen they are on and what
      // they need to be sure of is which topic they are reading.
      h1(topic.value),
      controls(query, startKind, running, rewrite, session, pressed),
      // The Read button's subscription. `session.start` returns the browse's own events and binding them to
      // this element is what gives them a lifetime — a stream nothing is subscribed to is a request opened
      // and then ignored.
      pressed.events.sample(query).flatMapSwitch(session.start) --> Observer[Unit](_ => ()),
      statusLine(session, running),
      RecordTable(
        records = session.rows,
        zone = zone,
        empty = emptyState(session, running),
        testId = Some("messages-table")
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
      pressed: EventBus[Unit]
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
        .combineWith(running)
        .map((progress, isRunning) => summary(progress, isRunning)),
      // A phase is what the stream is doing before the first record arrives — resolving a profile, seeking —
      // and on a large or a sick cluster each of those takes seconds. Without it, a slow browse and a hung
      // one look identical.
      child.maybe <-- session.progress.map(_.phase.map(name => span(cls := MessagesCss.StatusPhase, name))),
      child.maybe <-- session.progress.map(
        _.failure.map(failure => span(cls := MessagesCss.Error, sentence(failure)))
      )
    )

  private[messages] def summary(progress: browse.BrowseProgress, running: Boolean): String = {
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
    * typed an offset.
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
      controlled(L.value <-- value, onChange.mapToValue --> { raw => onEntered(raw) })
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
      case SseError.Server(envelope) => envelope.message
    }

  /** The kernel's own control class, so these fields look like every other field in KUI rather than like a
    * second set of inputs with one user.
    */
  private def KernelCssField: String = kui.ui.kernel.css.KernelCss.FieldControl
}
