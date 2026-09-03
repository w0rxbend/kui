package kui.ui.kernel.state

import scala.collection.mutable

import com.raquo.airstream.web.{WebStorageBuilder, WebStorageVar}
import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.theme.RootPreference

/** A colour somebody gives one cluster so they can tell it apart at a glance.
  *
  * ## Why this exists at all
  *
  * Cluster names in real deployments differ by one character — `prod-eu-1` and `prod-eu-2` — and the cost of
  * reading the wrong one is running a command against the wrong cluster. A colour is a second, non-textual
  * signal that a person picks themselves, on the cluster *they* find dangerous.
  *
  * It is a personal marker rather than a property of the cluster, which is why it lives in this browser and
  * is never sent anywhere. Two operators colouring the same cluster differently is correct.
  *
  * ## Six choices, and why not ten
  *
  * The reference product offers ten arbitrary colours. These six are existing semantic tokens — every one a
  * container colour that already has a paired text colour and a row in the contrast table — so no raw colour
  * value is written in Scala and nothing is added to the token set (ADR-024). Ten invented hexes would be ten
  * values no theme controls, and they would be wrong in one of the two themes. Six is enough to tell four
  * clusters apart, which is the problem being solved.
  *
  * The names shown to the user are colours ("Blue", "Amber") and the tokens behind them are semantic
  * ("primary", "warning"). That mismatch is deliberate: a person picking a marker is picking a colour, and
  * offering them "Warning" would suggest the cluster *is* in a warning state, which is what the status dot
  * next to the tag actually means.
  */
enum ClusterColor(val storageValue: String, val label: String) {
  case None extends ClusterColor("none", "No colour")
  case Primary extends ClusterColor("primary", "Blue")
  case Success extends ClusterColor("success", "Green")
  case Warning extends ClusterColor("warning", "Amber")
  case Danger extends ClusterColor("danger", "Red")
  case Accent extends ClusterColor("accent", "Teal")
}

object ClusterColor {

  given CanEqual[ClusterColor, ClusterColor] = CanEqual.derived

  /** Anything unrecognised reads as `None`.
    *
    * `localStorage` outlives upgrades, so a value written by a later build can be read by an earlier one. A
    * corrupted preference must leave the sidebar working, not blank it.
    */
  def fromStorage(raw: String): ClusterColor =
    values.find(_.storageValue == raw).getOrElse(None)
}

object ClusterColors {

  /** The `localStorage` key prefix. The cluster id completes it, and an id is a slug (ADR-031), so the key is
    * well-formed by construction and colouring `prod` cannot colour `prod-eu`.
    */
  val StorageKeyPrefix: String = "kui.cluster.color."

  private val cache: mutable.Map[String, Var[ClusterColor]] = mutable.Map.empty

  /** The chosen colour for one cluster. Writing persists.
    *
    * Memoised per cluster id, so the tag that displays the colour and the menu that changes it are watching
    * one value. Two `Var`s over one key would look identical until somebody changed the colour and only half
    * the screen moved.
    */
  def of(clusterId: String): Var[ClusterColor] =
    cache.getOrElseUpdate(
      clusterId,
      persisted(WebStorageVar.localStorage(keyOf(clusterId), syncOwner = scala.None))
    )

  def keyOf(clusterId: String): String = s"$StorageKeyPrefix$clusterId"

  /** The class the stylesheet keys off. No colour is decided here; this only says which one. */
  def className(colour: ClusterColor): String =
    colour match {
      case ClusterColor.None => KernelCss.ClusterTagNone
      case ClusterColor.Primary => KernelCss.ClusterTagPrimary
      case ClusterColor.Success => KernelCss.ClusterTagSuccess
      case ClusterColor.Warning => KernelCss.ClusterTagWarning
      case ClusterColor.Danger => KernelCss.ClusterTagDanger
      case ClusterColor.Accent => KernelCss.ClusterTagAccent
    }

  private[kernel] def persisted(storage: WebStorageBuilder): Var[ClusterColor] =
    RootPreference.persisted(storage, _.storageValue, ClusterColor.fromStorage, ClusterColor.None)

  /** Forgets the memoised `Var`s.
    *
    * For tests, which must not inherit the previous test's colours. Not `private[kernel]`: the switcher that
    * draws these lives in the shell, so its suite needs it too.
    */
  def reset(): Unit = cache.clear()
}
