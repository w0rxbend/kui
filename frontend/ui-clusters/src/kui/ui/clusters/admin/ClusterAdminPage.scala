package kui.ui.clusters.admin

import com.raquo.laminar.api.L.*

import kui.cluster.contract.dto.{ClusterWriteRequest, ConnectivityDto}
import kui.contracts.cluster.ClusterRowDto
import kui.gateway.contract.dto.ClusterOverviewDto
import kui.kernel.ClusterId
import kui.ui.clusters.{ClustersCss, ClustersQueries, Messages}
import kui.ui.kernel.component.*
import kui.ui.kernel.query.QueryState

/** Adding a cluster to this KUI, changing one, and removing one.
  *
  * ==Why this screen did not exist, and what that cost==
  *
  * `PUT /internal/v1/clusters/{id}` shipped in M1, with a suite, an optimistic version check and a
  * demonstrably correct write path — and it was deliberately kept out of the endpoint list the gateway
  * derives its public routes from, because there was no screen to call it from. There was no delete endpoint
  * at all, though the store had supported one all along, and the connectivity probe adapter was constructed
  * nowhere.
  *
  * The consequence for a user was that a KUI deployment's cluster list could only be changed by editing a
  * file and restarting the process — in a product whose entire metadata store exists so that it need not be.
  *
  * ==The three things this screen is careful about==
  *
  * **Lost updates.** Every write carries the version it is replacing, in `If-Match`. Two operators editing
  * the same cluster produce one winner and one 409 naming the conflict, rather than one silent overwrite. The
  * version comes from the row the screen is displaying, so a form opened before somebody else's change is
  * refused rather than applied on top of it.
  *
  * **Clusters that live in a file.** A deployment can declare clusters in its configuration, and those cannot
  * be edited or removed here: the store record would go and the next resolve would put the configured profile
  * straight back. The screen knows this from the row's `origin` and says so *before* the operator opens a
  * form, instead of letting them type everything in and then showing them a 409.
  *
  * **A connection tested before it is saved.** The form's Test button sends the settings to the probe
  * endpoint, which opens one bounded connection and describes the cluster — DNS, TCP, TLS and SASL in one
  * call — and answers within five seconds. It distinguishes "could not reach it" from "reached it and it
  * refused our credentials", because those send an operator to two different fields.
  */
object ClusterAdminPage {

  /** Which form, if any, is open. One value, so the screen cannot be creating and editing at once. */
  enum Editing {
    case Closed
    case Creating

    /** Editing the cluster with this id, at this version. */
    case Existing(id: ClusterId, version: Long)
  }

  object Editing {
    given CanEqual[Editing, Editing] = CanEqual.derived
  }

  def apply(
      queries: ClustersQueries,
      backHref: String
  ): HtmlElement = {
    val state: Signal[QueryState[ClusterOverviewDto]] = queries.clusters.state(())

    val rows: Signal[List[ClusterRowDto]] =
      state.map(_.lastGood.toList.flatMap(_.clusters.toOption.toList.flatten).map(_.cluster))

    val editing: Var[Editing] = Var(Editing.Closed)
    val form: Var[ClusterForm] = Var(ClusterForm.Empty)

    /** What went wrong, whether the form refused it or the server did.
      *
      * One place, because from the operator's side "I filled this in wrongly" and "the cluster refused" are
      * the same question — what do I do now — and two places to look for the answer is one too many.
      */
    val problem: Var[List[String]] = Var(Nil)

    val notice: Var[Option[String]] = Var(None)

    /** The probe's answer, cleared whenever the form changes so that a green tick cannot outlive the address
      * it was about. That is the whole failure mode of a "test connection" button: it says the connection
      * works, the operator edits the host, and the tick is still there.
      */
    val verdict: Var[Option[ConnectivityDto]] = Var(None)

    /** The request the operator has asked to be sent, set from a click and consumed by a subscription the
      * element owns. A stream started inside a click handler has no owner and is never cancelled.
      */
    val saving: Var[Option[(ClusterId, Long, ClusterWriteRequest)]] = Var(None)
    val probing: Var[Option[ClusterWriteRequest]] = Var(None)
    val removing: Var[Option[(ClusterId, Long)]] = Var(None)

    val confirmingDelete: Var[Boolean] = Var(false)
    val pendingDelete: Var[Option[(ClusterId, Long)]] = Var(None)

    def close(): Unit = {
      editing.set(Editing.Closed)
      problem.set(Nil)
      verdict.set(None)
    }

    /** Reads the form, and either reports every problem or sends the write. */
    def submit(): Unit = {
      val current = form.now()

      current.toRequest match {
        case Left(problems) => problem.set(problems)
        case Right(request) =>
          problem.set(Nil)

          // The id comes from the name, exactly as the server derives it (ADR-031), so a rename is a
          // create-plus-delete rather than a `PUT` that would leave a record whose key and name disagree.
          // On an edit the id is the one being edited: a changed name that slugs differently is refused by
          // the server, and that refusal is the honest answer rather than a silent second cluster.
          editing.now() match {
            case Editing.Creating =>
              ClusterId.from(ClusterAdminPage.slugOf(request.name)) match {
                case Right(id) => saving.set(Some((id, 0L, request)))
                case Left(error) => problem.set(List(error.message))
              }
            case Editing.Existing(id, version) => saving.set(Some((id, version, request)))
            case Editing.Closed => ()
          }
      }
    }

    div(
      cls := ClustersCss.Page,
      dataAttr("testid") := "page-clusters-admin",
      Breadcrumbs(
        Val(List(Crumb(Messages.Title, Some(backHref)), Crumb(Messages.AdminTitle, None))),
        testId = Some("admin-breadcrumbs")
      ),
      h1(Messages.AdminTitle),
      p(cls := ClustersCss.Note, Messages.AdminDescription),
      child.maybe <-- notice.signal.map(
        _.map(message =>
          p(
            cls := ClustersCss.Notice,
            role := "status",
            dataAttr("testid") := "admin-notice",
            message
          )
        )
      ),
      div(
        cls := ClustersCss.AdminControls,
        Button(
          label = Val(Messages.AddCluster),
          onClick = Observer[Unit] { _ =>
            form.set(ClusterForm.Empty)
            problem.set(Nil)
            verdict.set(None)
            notice.set(None)
            editing.set(Editing.Creating)
          },
          variant = ButtonVariant.Primary,
          testId = Some("admin-add")
        )
      ),
      child <-- rows.map(list =>
        clusterRows(list, editing, form, problem, verdict, notice, confirmingDelete, pendingDelete)
      ),
      child.maybe <-- editing.signal.map(current =>
        Option.when(current != Editing.Closed)(
          ClusterFormPanel(
            form = form,
            creating = current == Editing.Creating,
            problems = problem.signal,
            verdict = verdict.signal,
            onTest = () =>
              form.now().toRequest match {
                case Left(problems) => problem.set(problems)
                case Right(request) =>
                  problem.set(Nil)
                  probing.set(Some(request))
              },
            onSave = () => submit(),
            onCancel = () => close()
          )
        )
      ),
      ConfirmDialog(
        open = confirmingDelete,
        title = Val(Messages.DeleteClusterConfirmTitle),
        message = Val(Messages.DeleteClusterConfirmMessage),
        onConfirm = Observer[Unit](_ => removing.set(pendingDelete.now())),
        confirmLabel = Messages.DeleteCluster,
        testId = Some("admin-delete-confirm")
      ),

      // ------------------------------------------------------------------------------ the requests
      saving.signal.changes
        .collect { case Some(pending) => pending }
        .flatMapSwitch((id, version, request) => queries.putCluster(id, version, request)) --> { outcome =>
        saving.set(None)
        outcome match {
          case Right(written) =>
            close()
            notice.set(Some(Messages.clusterSaved(written.name)))
          case Left(error) => problem.set(List(error.userMessage))
        }
      },
      probing.signal.changes
        .collect { case Some(request) => request }
        .flatMapSwitch(queries.probeCluster) --> { outcome =>
        probing.set(None)
        outcome match {
          case Right(answer) => verdict.set(Some(answer))
          case Left(error) => problem.set(List(error.userMessage))
        }
      },
      removing.signal.changes
        .collect { case Some(pending) => pending }
        .flatMapSwitch((id, version) => queries.deleteCluster(id, version)) --> { outcome =>
        removing.set(None)
        pendingDelete.set(None)
        outcome match {
          case Right(()) => notice.set(Some(Messages.ClusterDeleted))
          case Left(error) => problem.set(List(error.userMessage))
        }
      },
      // A verdict must never outlive the values it was about. Editing any field clears it, so the tick on
      // screen is always a statement about the address on screen — which is the one failure mode a "test
      // connection" button has.
      form.signal.changes --> { _ => verdict.set(None) }
    )
  }

  /** The rows, each with what can be done to it.
    *
    * A table rather than cards, because the question this screen answers first is "which of these is the one
    * I need to change", and a name in a column is found faster than a name on a card.
    */
  private def clusterRows(
      rows: List[ClusterRowDto],
      editing: Var[Editing],
      form: Var[ClusterForm],
      problem: Var[List[String]],
      verdict: Var[Option[ConnectivityDto]],
      notice: Var[Option[String]],
      confirmingDelete: Var[Boolean],
      pendingDelete: Var[Option[(ClusterId, Long)]]
  ): HtmlElement =
    if rows.isEmpty then
      EmptyState(
        Messages.AdminEmptyTitle,
        description = Some(Messages.AdminEmptyDescription),
        testId = Some("admin-empty")
      )
    else
      div(
        cls := ClustersCss.AdminList,
        dataAttr("testid") := "admin-list",
        rows.map(row =>
          div(
            cls := ClustersCss.AdminRow,
            dataAttr("testid") := s"admin-row-${row.id.value}",
            div(
              cls := ClustersCss.AdminRowIdentity,
              span(cls := ClustersCss.AdminRowName, row.name),
              span(cls := ClustersCss.AdminRowAddress, row.bootstrapServers),
              span(cls := ClustersCss.AdminRowOrigin, originLabel(row))
            ),
            div(
              cls := ClustersCss.AdminRowActions,
              // A cluster the configuration file names gets a sentence instead of buttons. Offering an
              // edit that the server will refuse teaches an operator that KUI's refusals are noise.
              if !ClusterRowDto.isEditable(row) then
                span(
                  cls := ClustersCss.Note,
                  dataAttr("testid") := s"admin-row-${row.id.value}-static",
                  Messages.ClusterIsStatic
                )
              else
                div(
                  Button(
                    label = Val(Messages.EditCluster),
                    onClick = Observer[Unit] { _ =>
                      form.set(ClusterForm.of(row))
                      problem.set(Nil)
                      verdict.set(None)
                      notice.set(None)
                      editing.set(Editing.Existing(row.id, row.version.getOrElse(0L)))
                    },
                    testId = Some(s"admin-row-${row.id.value}-edit")
                  ),
                  Option.when(ClusterRowDto.isRemovable(row))(
                    Button(
                      label = Val(Messages.DeleteCluster),
                      onClick = Observer[Unit] { _ =>
                        pendingDelete.set(Some((row.id, row.version.getOrElse(0L))))
                        notice.set(None)
                        confirmingDelete.set(true)
                      },
                      variant = ButtonVariant.Danger,
                      testId = Some(s"admin-row-${row.id.value}-delete")
                    )
                  )
                )
            )
          )
        )
      )

  private def originLabel(row: ClusterRowDto): String =
    row.origin match {
      case ClusterRowDto.OriginStored => Messages.OriginStored
      case ClusterRowDto.OriginStaticThenStored => Messages.OriginStaticThenStored
      case _ => Messages.OriginStatic
    }

  /** The id a name will produce, mirroring `ClusterConfig.slug`.
    *
    * A copy of a server rule, which is normally forbidden here, and it is a copy on purpose: this one decides
    * which *path* the create is sent to, so the browser has to compute it before the request exists. The
    * server derives the same id from the same name and refuses a mismatch by name — so a drift between the
    * two is a 400 the operator can read, not a cluster written under the wrong key.
    */
  def slugOf(name: String): String = {
    val collapsed = name
      .toLowerCase(java.util.Locale.ROOT)
      .replaceAll("[^a-z0-9]+", "-")
      .replaceAll("(^-+)|(-+$)", "")

    // The same 64-character bound the server applies, with the dash a truncation can leave behind
    // trimmed, because `production-eu-` is not a legal id. `ClusterId`'s own limit is not published by
    // the opaque type, which is why both sides spell it out.
    collapsed.take(MaxIdLength).reverse.dropWhile(_ == '-').reverse
  }

  /** `ClusterId`'s upper bound, matching `kui.config.ClusterConfig.MaxIdLength`. */
  private val MaxIdLength: Int = 64
}
