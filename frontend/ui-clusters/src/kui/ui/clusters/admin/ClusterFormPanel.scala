package kui.ui.clusters.admin

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.cluster.contract.dto.ConnectivityDto
import kui.ui.clusters.{ClustersCss, Messages}
import kui.ui.kernel.component.*
import kui.ui.kernel.css.KernelCss

/** The form itself: how to reach a cluster, and a button that finds out whether that works.
  *
  * ==Test before save, and the tick that must not go stale==
  *
  * The whole point of the connection test is that it answers *before* anything is written. That makes one
  * failure mode worth designing against: the operator tests, gets a tick, changes the host, and saves an
  * address nobody ever tested while a green tick is still on the screen. So every edit to any field clears
  * the verdict. The tick on screen is always about the values on screen.
  *
  * ==Which fields are shown depends on the protocol==
  *
  * A mechanism, a username and a password mean nothing on a `PLAINTEXT` connection, and hostname verification
  * means nothing where there is no certificate. Showing them greyed out would suggest they are settings this
  * cluster has and is ignoring; they are not settings it has at all.
  */
object ClusterFormPanel {

  def apply(
      form: Var[ClusterForm],
      creating: Boolean,
      problems: Signal[List[String]],
      verdict: Signal[Option[ConnectivityDto]],
      onTest: () => Unit,
      onSave: () => Unit,
      onCancel: () => Unit
  ): HtmlElement = {

    /** One text field, bound to one member of the form.
      *
      * The `Var[String]` is created here and written back into the form's `Var`, rather than the form being a
      * bag of separate `Var`s, because everything that reads the form — validation, the request, the
      * verdict-clearing below — wants one value and not eleven.
      */
    def field(
        label: String,
        read: ClusterForm => String,
        write: (ClusterForm, String) => ClusterForm,
        testId: String,
        hint: Option[String] = None,
        placeholder: String = ""
    ): HtmlElement = {
      val cell: Var[String] = Var(read(form.now()))

      TextInput(
        value = cell,
        label = label,
        placeholder = placeholder,
        hint = hint,
        testId = Some(testId)
      ).amend(
        cell.signal.changes --> { typed => form.update(current => write(current, typed)) }
      )
    }

    def toggle(
        label: String,
        read: ClusterForm => Boolean,
        write: (ClusterForm, Boolean) => ClusterForm,
        testId: String
    ): HtmlElement =
      L.label(
        cls := ClustersCss.AdminToggle,
        input(
          typ := "checkbox",
          checked <-- form.signal.map(read),
          dataAttr("testid") := testId,
          onInput.mapToChecked --> { value => form.update(current => write(current, value)) }
        ),
        span(label)
      )

    val protocol: Var[Option[String]] = Var(Some(form.now().protocol))
    val mechanism: Var[Option[String]] = Var(Some(form.now().mechanism))

    div(
      cls := ClustersCss.AdminForm,
      dataAttr("testid") := "admin-form",
      h2(if creating then Messages.AddCluster else Messages.EditCluster),

      field(
        Messages.FieldName,
        _.name,
        (current, value) => current.copy(name = value),
        "admin-form-name",
        hint = Some(Messages.FieldNameHint),
        placeholder = "production eu"
      ),
      field(
        Messages.FieldBootstrap,
        _.bootstrapServers,
        (current, value) => current.copy(bootstrapServers = value),
        "admin-form-bootstrap",
        hint = Some(Messages.FieldBootstrapHint),
        placeholder = "broker-1:9092,broker-2:9092"
      ),
      toggle(
        Messages.FieldReadOnly,
        _.readOnly,
        (current, value) => current.copy(readOnly = value),
        "admin-form-readonly"
      ),

      Select[String](
        options = Val(ClusterForm.Protocols),
        selected = protocol,
        label = Messages.FieldProtocol,
        testId = Some("admin-form-protocol")
      ).amend(
        protocol.signal.changes --> { chosen =>
          form.update(current => current.copy(protocol = chosen.getOrElse(ClusterForm.Plaintext)))
        }
      ),

      // The SASL half, present only when it means something.
      child.maybe <-- form.signal
        .map(_.isSasl)
        .distinct
        .map(
          Option.when(_)(
            div(
              cls := ClustersCss.AdminFormGroup,
              Select[String](
                options = Val(ClusterForm.Mechanisms),
                selected = mechanism,
                label = Messages.FieldMechanism,
                testId = Some("admin-form-mechanism")
              ).amend(
                mechanism.signal.changes --> { chosen =>
                  form.update(current => current.copy(mechanism = chosen.getOrElse("")))
                }
              ),
              field(
                Messages.FieldUsername,
                _.username,
                (current, value) => current.copy(username = value),
                "admin-form-username"
              ),
              passwordField(form),
              Option.when(!creating)(p(cls := ClustersCss.Note, ClusterForm.passwordWarning))
            )
          )
        ),

      child.maybe <-- form.signal
        .map(_.isTls)
        .distinct
        .map(
          Option.when(_)(
            div(
              cls := ClustersCss.AdminFormGroup,
              toggle(
                Messages.FieldVerifyHostname,
                _.verifyHostname,
                (current, value) => current.copy(verifyHostname = value),
                "admin-form-verify-hostname"
              ),
              p(cls := ClustersCss.Note, Messages.TlsMaterialNote)
            )
          )
        ),

      detailsTag(
        cls := ClustersCss.AdminFormGroup,
        summaryTag(Messages.AdminTuningHeading),
        p(cls := ClustersCss.Note, ClusterForm.tuningWarning),
        field(
          Messages.FieldTimeout,
          _.timeoutMs,
          (current, value) => current.copy(timeoutMs = value),
          "admin-form-timeout"
        ),
        field(
          Messages.FieldBatchSize,
          _.batchSize,
          (current, value) => current.copy(batchSize = value),
          "admin-form-batch"
        ),
        field(
          Messages.FieldParallelism,
          _.parallelism,
          (current, value) => current.copy(parallelism = value),
          "admin-form-parallelism"
        )
      ),

      child.maybe <-- verdict.map(_.map(verdictPanel)),
      child.maybe <-- problems.map(list =>
        Option.when(list.nonEmpty)(
          ul(
            cls := ClustersCss.Error,
            role := "alert",
            dataAttr("testid") := "admin-form-problems",
            list.map(message => li(message))
          )
        )
      ),

      div(
        cls := ClustersCss.AdminFormActions,
        Button(
          label = Val(Messages.TestConnection),
          onClick = Observer[Unit](_ => onTest()),
          testId = Some("admin-form-test")
        ),
        Button(
          label = Val(Messages.SaveCluster),
          onClick = Observer[Unit](_ => onSave()),
          variant = ButtonVariant.Primary,
          testId = Some("admin-form-save")
        ),
        Button(
          label = Val(Messages.Cancel),
          onClick = Observer[Unit](_ => onCancel()),
          testId = Some("admin-form-cancel")
        )
      )
    )
  }

  /** The password box, kept separate because it is the one field that is never pre-filled. */
  private def passwordField(form: Var[ClusterForm]): HtmlElement = {
    val cell: Var[String] = Var("")

    div(
      cls := KernelCss.Field,
      L.label(cls := KernelCss.FieldLabel, Messages.FieldPassword),
      input(
        typ := "password",
        cls := KernelCss.FieldControl,
        dataAttr("testid") := "admin-form-password",
        controlled(value <-- cell.signal, onInput.mapToValue --> cell.writer)
      ),
      cell.signal.changes --> { typed => form.update(current => current.copy(password = typed)) }
    )
  }

  /** What the probe said, in the three shapes it can say it.
    *
    * Three renderings and not two, because "could not reach it" and "reached it and it refused our
    * credentials" send an operator to two different fields — the address and the password — and a single red
    * cross would send half of them to the wrong one.
    */
  private def verdictPanel(answer: ConnectivityDto): HtmlElement =
    div(
      cls := (if answer.reachable then ClustersCss.VerdictGood else ClustersCss.VerdictBad),
      role := "status",
      dataAttr("testid") := "admin-form-verdict",
      dataAttr("verdict") := answer.status,
      strong(
        if answer.reachable then Messages.VerdictReachable
        else if answer.status == ConnectivityDto.AuthenticationFailed then Messages.VerdictRefused
        else Messages.VerdictUnreachable
      ),
      answer.detail.map(sentence => span(" ", sentence))
    )
}
