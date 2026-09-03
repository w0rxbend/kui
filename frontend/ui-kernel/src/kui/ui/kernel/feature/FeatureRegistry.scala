package kui.ui.kernel.feature

import scala.scalajs.js

import com.raquo.laminar.api.L.*

/** The set of features a build contains, and their download state.
  *
  * ## Why the thunks are shaped the way they are
  *
  * Each entry is `() => js.Promise[KuiFeature]`, and the body of that thunk must be
  * `js.dynamicImport(new kui.ui.clusters.ClustersFeature())` and nothing else. That expression is a *split
  * border* to the Scala.js linker: everything reachable only through it goes into a separate JavaScript
  * module, which the browser fetches when the thunk is first called.
  *
  * The border is easy to destroy by accident. Assigning the constructor to a `val`, mentioning the feature's
  * type in a signature, or so much as naming the class outside the import makes it reachable from the shell,
  * and the linker then puts it in `main.js` — where it is downloaded by everyone, always, including users who
  * are not allowed to see it. Nothing about the code looks different when that happens, which is why
  * `checkBundleShape` (BUILD-006) asserts the shape of the linked output rather than trusting a review to
  * spot it.
  *
  * @param thunks
  *   what this build can load. A parameter rather than a hard-wired map so that tests can supply stubs; the
  *   application uses `FeatureRegistry.default`.
  */
final class Features(thunks: Map[FeatureId, () => js.Promise[KuiFeature]]) {

  // One `LazyFeature` per id, created on first request and kept. If a fresh one were handed out per
  // call, the shell asking twice would import twice, and the memoisation inside `ImportedFeature`
  // would protect nothing.
  private var instances: Map[FeatureId, LazyFeature] = Map.empty

  private val loadedFeatures = Var(Map.empty[FeatureId, KuiFeature])

  /** Every feature that has finished loading.
    *
    * `FeaturePanel` renders from this and from nothing else, which is what makes "a host page never triggers
    * a download" true by construction rather than by discipline.
    */
  val loaded: Signal[List[KuiFeature]] = loadedFeatures.signal.map(_.values.toList.sortBy(_.nav.order))

  /** The loader for one feature. The same instance every time, for the whole life of the page. */
  def lazyFeature(id: FeatureId): LazyFeature =
    instances.getOrElse(
      id, {
        val created = thunks.get(id) match {
          case Some(thunk) => new ImportedFeature(id, thunk)
          case None => new MissingFeature(id)
        }
        instances = instances.updated(id, created)
        track(created)
        created
      }
    )

  /** Which ids this build knows about at all. The sidebar is built from these. */
  def known: Set[FeatureId] = thunks.keySet

  /** Keeps `loaded` in step with one feature's state.
    *
    * The subscription lives as long as the page, which is correct: a feature, once downloaded, is never
    * unloaded, and the registry itself is created once during start-up.
    */
  private def track(feature: LazyFeature): Unit =
    feature.state.foreach {
      case LoadState.Loaded(value) => loadedFeatures.update(_.updated(feature.id, value))
      case _ => loadedFeatures.update(_.removed(feature.id))
    }(using unsafeWindowOwner): Unit
}

/** The application's one registry.
  *
  * ## Why the map is installed rather than defined here
  *
  * The single place in the frontend where a feature class is named has to be a module that can *see* that
  * class, and this is not it: the kernel is below every feature, and `frontend.uiKernel` depending on
  * `frontend.uiClusters` would be a cycle. The shell is above both, so the shell owns the map
  * (`kui.ui.shell.FeatureRegistryImpl`) and hands it here during start-up. Each entry's body must read
  * exactly `js.dynamicImport(new kui.ui.clusters.ClustersFeature())` and nothing else; see `Features` above
  * for why "exactly" is not a stylistic preference.
  *
  * Anything asked for before [[install]] gets a registry with no features in it, which renders as "this build
  * has no such feature" rather than as a blank screen — the honest answer for a lookup that happened before
  * start-up finished.
  */
object FeatureRegistry {

  private var installed: Features = new Features(Map.empty)

  private var statics: List[FeatureRoutes] = Nil

  /** Called once, by the shell, before anything is rendered.
    *
    * @param thunks
    *   the dynamic half: what to import, per feature.
    * @param staticRoutes
    *   the static half: nav entries, route patterns and `history.state` codecs, all available before a byte
    *   of any feature has been downloaded (ADR-012 amendment 2).
    */
  def install(
      thunks: Map[FeatureId, () => js.Promise[KuiFeature]],
      staticRoutes: List[FeatureRoutes]
  ): Unit = {
    installed = new Features(thunks)
    statics = staticRoutes
  }

  /** The static registrations, in sidebar order. */
  def staticRoutes: List[FeatureRoutes] = statics.sortBy(_.nav.order)

  def lazyFeature(id: FeatureId): LazyFeature = installed.lazyFeature(id)

  def loaded: Signal[List[KuiFeature]] = installed.loaded
}
