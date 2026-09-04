package kui.ui.messages.produce

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.kernel.{ClusterId, Offset, TopicName}
import kui.message.contract.{MessageDto, OffsetRangeDto, ResendRequestDto, ResendResultDto}
import kui.ui.kernel.api.ApiClient
import kui.ui.kernel.component.*
import kui.ui.kernel.css.KernelCss
import kui.ui.messages.{Messages, MessagesCss}

/** Which records a resend copies. */
final case class ResendTarget(source: TopicName, partition: Int, from: Long, until: Long, destination: String)

object ResendTarget {

  /** One record, addressed as the half-open window that holds exactly it.
    *
    * `[offset, offset + 1)` rather than a special "one record" request, because the server takes ranges and a
    * second shape for the single-record case would be a second code path that could disagree with the first
    * about which record it meant.
    */
  def of(source: TopicName, record: MessageDto): ResendTarget =
    ResendTarget(
      source = source,
      partition = record.partition.value,
      from = record.offset.value,
      until = record.offset.value + 1L,
      destination = ""
    )

  given CanEqual[ResendTarget, ResendTarget] = CanEqual.derived
}

/** Copying records into another topic, as a drawer.
  *
  * ## Why this is a different action from Republish, and not a variant of it
  *
  * They look similar and they are not the same operation, and confusing them is how a replay silently stops
  * being a replay.
  *
  *   - **Resend** copies the *bytes*. Nothing is decoded and nothing is re-encoded, so what lands in the
  *     destination is byte-for-byte what the original producer wrote, headers included. A topic KUI cannot
  *     decode can still be resent — which is the case an operator most often needs this for.
  *   - **Republish** takes what is on the screen, lets you change it, and encodes it again. It is a new
  *     record, and it is yours.
  *
  * So the two live behind two buttons with two verbs, and this one deliberately offers no editing at all
  * beyond the destination. A field here that changed a payload would turn a byte-exact copy into something
  * else while still being called a resend.
  *
  * This is the feature Kouncil has and the other reference products do not.
  */
object ResendDrawer {

  def apply(
      cluster: ClusterId,
      target: Var[Option[ResendTarget]],
      api: ApiClient
  ): HtmlElement = {
    val problem: Var[Option[String]] = Var(None)
    val result: Var[Option[ResendResultDto]] = Var(None)
    val sending: Var[Boolean] = Var(false)
    val sent = new EventBus[ResendTarget]

    val open: Var[Boolean] = Var(false)

    val binding = target.signal --> Observer[Option[ResendTarget]] { current =>
      open.set(current.isDefined)
      if current.isEmpty then {
        problem.set(None)
        result.set(None)
      }
    }

    def body(): HtmlElement =
      div(
        cls := MessagesCss.Form,
        dataAttr("testid") := "resend-form",
        // What is being copied, as a sentence and not as three editable numbers. The range came from the
        // record the operator opened; letting them retype it here would let them copy a different record
        // than the one they were looking at, which is the one mistake this drawer must not make possible.
        p(
          cls := MessagesCss.FormNote,
          dataAttr("testid") := "resend-source",
          child.text <-- target.signal.map(_.fold("")(describe))
        ),
        div(
          cls := MessagesCss.ControlGroup,
          L.label(
            cls := MessagesCss.ControlLabel,
            Messages.ResendDestinationLabel,
            input(
              tpe := "text",
              cls := KernelCss.FieldControl,
              L.placeholder := Messages.ResendDestinationPlaceholder,
              dataAttr("testid") := "resend-destination",
              L.value <-- target.signal.map(_.fold("")(_.destination)),
              onInput.mapToValue --> { raw => target.update(_.map(_.copy(destination = raw))) }
            )
          )
        ),
        p(cls := MessagesCss.FormNote, Messages.ResendExplanation),
        div(
          cls := MessagesCss.FormActions,
          Button(
            label = Val(Messages.Resend),
            onClick = Observer[Unit](_ => target.now().foreach(sent.writer.onNext)),
            variant = ButtonVariant.Primary,
            loading = sending.signal,
            testId = Some("resend-submit")
          )
        ),
        child.maybe <-- problem.signal.map(
          _.map(message =>
            p(cls := MessagesCss.Error, role := "alert", dataAttr("testid") := "resend-error", message)
          )
        ),
        child.maybe <-- result.signal.map(
          _.map(answer =>
            p(
              cls := MessagesCss.FormResult,
              role := "status",
              dataAttr("testid") := "resend-result",
              // Read and written are both reported, because they differ whenever retention removed part of
              // the source under the copy — and "copied nothing because there was nothing left" and
              // "copied nothing because it failed" must not look the same.
              Messages.resent(answer.read, answer.written, answer.toTopic.value)
            )
          )
        ),
        sent.events.flatMapSwitch(send(cluster, api, problem, sending)) --> Observer[
          Either[String, ResendResultDto]
        ] {
          case Left(message) =>
            sending.set(false)
            result.set(None)
            problem.set(Some(message))
          case Right(answer) =>
            sending.set(false)
            problem.set(None)
            result.set(Some(answer))
        }
      )

    div(
      binding,
      Drawer(
        open = open,
        title = Val(Messages.Resend),
        body = () => body(),
        width = "30rem",
        onClose = Observer[Unit](_ => target.set(None)),
        testId = Some("resend-drawer")
      )
    )
  }

  // -----------------------------------------------------------------------------------------------

  private def send(
      cluster: ClusterId,
      api: ApiClient,
      problem: Var[Option[String]],
      sending: Var[Boolean]
  )(target: ResendTarget): EventStream[Either[String, ResendResultDto]] =
    request(target) match {
      case Left(message) => EventStream.fromValue(Left(message))
      case Right(document) =>
        sending.set(true)
        problem.set(None)
        api
          .call(ProduceApi.resend, (cluster, target.source, document))
          .map(_.left.map(_.userMessage))
    }

  /** The wire document, or the reason it is not ready.
    *
    * The destination has to be a topic name and it has to be a different topic: copying a partition onto
    * itself appends every record it reads and then reads what it appended. The server refuses that too — it
    * has to, since a browser is not a security boundary — and this refusal exists so that the answer appears
    * next to the field rather than after a round trip.
    */
  private[messages] def request(target: ResendTarget): Either[String, ResendRequestDto] =
    TopicName.from(target.destination.trim) match {
      case Left(_) => Left(Messages.ResendDestinationRequired)
      case Right(destination) if destination.value == target.source.value =>
        Left(Messages.ResendSameTopic)
      case Right(destination) =>
        for {
          partition <- kui.kernel.PartitionId
            .from(target.partition)
            .left
            .map(_ => Messages.ResendRangeInvalid)
          from <- Offset.from(target.from).left.map(_ => Messages.ResendRangeInvalid)
          until <- Offset.from(target.until).left.map(_ => Messages.ResendRangeInvalid)
        } yield ResendRequestDto(destination, List(OffsetRangeDto(partition, from, until)))
    }

  private[messages] def describe(target: ResendTarget): String =
    Messages.resendSource(target.source.value, target.partition, target.from, target.until)
}
