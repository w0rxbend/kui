package kui.ui.kernel.feature

import com.raquo.waypoint.Route
import io.circe.{HCursor, Json}

/** The half of a feature's registration that is **static data** (ADR-012 amendment 2).
  *
  * ## Why a feature is registered twice
  *
  * A feature is downloaded only when it is needed, which is the whole point of ADR-012. But three things
  * about a feature have to be known *before* it is downloaded, or the product misbehaves in ways the user
  * cannot work around:
  *
  *   - **Its URLs.** A bookmarked link to `/ui/clusters` must resolve on the first load, before any module
  *     has been fetched. If the router only learned the pattern once the feature had been imported, the first
  *     address it saw would be one it could not match and the user would get a 404 for a page that exists.
  *   - **Its sidebar entry and where that entry points.** The navigation is drawn on first paint, from
  *     capability state alone; nothing may be downloaded in order to draw a link.
  *   - **How its pages are written into `history.state`.** The browser hands back whatever was stored when
  *     the user presses Back, and it does so synchronously — there is no moment at which to await an import.
  *     Without this, Back onto a feature page decodes to "not found".
  *
  * All three are *data*: path shapes, a label, a JSON tag. Linking against them costs a few bytes in
  * `main.js` and pulls no feature code with them. What must never appear on this side of the line is the
  * feature's `KuiFeature` class — that is the dynamic half, named only inside a `js.dynamicImport`, and
  * `checkBundleShape` (BUILD-006) fails the build if it leaks.
  */
trait FeatureRoutes {

  def id: FeatureId

  /** The sidebar entry, drawn before anything is downloaded. */
  def nav: NavEntry

  /** Where the sidebar entry goes. The feature's front door.
    *
    * A `Page` rather than a URL, so the router builds the address and a changed route pattern cannot leave a
    * stale link behind.
    */
  def landing: Page

  /** Every URL this feature owns, as patterns. Registered with the router at start-up. */
  def routes: List[Route[? <: Page, ?]]

  /** This feature's contribution to the `history.state` codec.
    *
    * `None` for a page that is not this feature's, so the shell can try each contributor in turn. Encoding
    * and decoding are a pair: whatever `encodePage` writes under the `"page"` tag, `decodePage` is asked to
    * read back.
    */
  def encodePage(page: Page): Option[Json]

  /** @param tag
    *   the value of the stored `"page"` field.
    * @param cursor
    *   the rest of the stored object, for whatever else the page carries.
    */
  def decodePage(tag: String, cursor: HCursor): Option[Page]
}
