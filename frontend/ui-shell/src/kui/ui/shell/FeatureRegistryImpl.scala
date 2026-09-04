package kui.ui.shell

import scala.scalajs.js

import kui.ui.kernel.feature.{FeatureId, FeatureRoutes, KuiFeature}

/** The single place in the whole frontend where a feature class is named (ADR-012).
  *
  * ## Read this before adding an entry
  *
  * A thunk's body must be `js.dynamicImport(new kui.ui.clusters.ClustersFeature())` and **nothing else**.
  * That expression is a *split border* to the Scala.js linker: everything reachable only through it is put in
  * a separate JavaScript module, which the browser fetches the first time the thunk is called.
  *
  * The border is easy to destroy by accident, and destroying it looks like nothing. Assigning the constructor
  * to a `val`, mentioning the feature's type in a signature, or so much as naming the class anywhere outside
  * the import makes the class reachable from the shell, and the linker then puts it in `main.js` — where it
  * is downloaded by every user on first paint, including users whose deployment does not have that service at
  * all. Nothing about the source looks different when that happens, which is why `checkBundleShape`
  * (BUILD-006) asserts the shape of the *linked output* rather than trusting a reviewer to spot it.
  *
  * [[staticRoutes]] is the other half, and it has the opposite rule: it is ordinary static data — a label, a
  * sort order, path shapes, a JSON tag — and the shell links against it normally, because all of it has to be
  * known before anything is downloaded (ADR-012 amendment 2). What must never appear there is the
  * `KuiFeature` class itself.
  */
object FeatureRegistryImpl {

  /** What this build can load, and how. */
  def thunks: Map[FeatureId, () => js.Promise[KuiFeature]] =
    Map(
      FeatureId.Clusters -> (() => js.dynamicImport(new kui.ui.clusters.ClustersFeature())),
      FeatureId.Topics -> (() => js.dynamicImport(new kui.ui.topics.TopicsFeature())),
      FeatureId.Messages -> (() => js.dynamicImport(new kui.ui.messages.MessagesFeature())),
      FeatureId.Consumers -> (() => js.dynamicImport(new kui.ui.consumers.ConsumersFeature()))
    )

  /** Each feature's navigation entry, route patterns and `history.state` codec.
    *
    * Named directly, unlike the thunks above, and that is correct rather than an inconsistency: all of this
    * is data, it must be available before anything is downloaded, and linking against it pulls no feature
    * code into `main.js`.
    */
  def staticRoutes: List[FeatureRoutes] =
    List(
      kui.ui.clusters.ClustersRoutes,
      kui.ui.topics.TopicsRoutes,
      kui.ui.messages.MessagesRoutes,
      kui.ui.consumers.ConsumersRoutes
    )
}
