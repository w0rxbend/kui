# Adding a microfrontend

A *microfrontend* here is one slice of the product — clusters, topics, messages, schemas — compiled
into its own JavaScript module and downloaded only when it is actually needed. This page is the
recipe. It is four steps.

## Why it works this way

The shell must never download a feature the user cannot use. Not as an optimisation: a feature can be
unavailable because the service behind it is down, not configured in this deployment, or something
this user has no permission to see, and shipping its code to everyone anyway is both wasteful and, in
the last case, a small information leak.

Scala.js can split a program into several JavaScript modules, and the border it splits on is
`js.dynamicImport`. Everything reachable *only* through such an expression is emitted as a separate
module and fetched by the browser at the moment the expression runs. So the shell holds a map of
thunks, and calling one is what causes the download (ADR-012).

Route *patterns* are the exception, and they have to be. A bookmarked deep link into a feature has to
resolve before that feature's module exists, or the very first URL the router sees is one it cannot
match and the user gets a 404 for a page that does exist. Patterns are data — path shapes — so they
cost a few bytes in `main.js` and do not drag the feature's code in with them (ADR-012 amendment 2).

## The four steps

### 1. Implement `KuiFeature`

```scala
package kui.ui.topics

final class TopicsFeature extends KuiFeature {
  def id: FeatureId = FeatureId.Topics

  def nav: NavEntry =
    NavEntry(FeatureId.Topics, "Topics", () => Icon.menu, order = 20, requiresCluster = true)

  def routes: List[Route[? <: Page, ?]] = TopicsRoutes.all

  def render(page: Page): HtmlElement = page match {
    case topicList: TopicListPage => TopicListView(topicList)
    case detail: TopicDetailPage  => TopicDetailView(detail)
    case other                    => EmptyState(s"Unknown page: $other")
  }

  def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement =
    TopicsUnavailable(reason, retry)

  override def panels: List[PanelContribution] = Nil
}
```

`unavailableView` is not boilerplate. ADR-032 requires four things on that panel, and the fourth is
the reason it belongs to the feature rather than to a generic kernel component: the reason, when it
started, a working retry, and **what still works**. "You can still browse messages; only the schema
names are missing" is a sentence the schema feature can write and the shell cannot.

### 2. Add a `FeatureId` case

```scala
enum FeatureId(val value: String, val serviceId: String) {
  case Clusters extends FeatureId("clusters", "cluster")
  case Topics   extends FeatureId("topics", "topic")   // new
}
```

The service id is the name the gateway's capability registry uses. Carrying it here means "is this
feature's service healthy?" is a field access rather than a lookup table maintained in two places.
The two words are not always the same, which is why guessing one from the other would not work.

### 3. Add the thunk to `FeatureRegistry.default`

```scala
def default: Map[FeatureId, () => js.Promise[KuiFeature]] = Map(
  FeatureId.Topics -> (() => js.dynamicImport(new kui.ui.topics.TopicsFeature()))
)
```

**Write it exactly like that.** The body of the thunk must be the `js.dynamicImport` expression and
nothing else. Assigning the constructor to a `val`, mentioning `TopicsFeature` in a type signature,
or naming the class anywhere outside this import makes it reachable from the shell, and the linker
then puts the whole feature into `main.js` — downloaded by everyone, always. Nothing about the code
looks different when that happens, which is why it is checked mechanically rather than by review.

### 4. Register the package for splitting, and for the check

In `build.mill`:

```scala
trait KuiFrontendModule extends KuiJsModule {
  def moduleSplitStyle = ModuleSplitStyle.SmallModulesFor(List("kui.ui.clusters", "kui.ui.topics"))
}
```

and add the feature to the shell module's `bundleFeatures`, so that `checkBundleShape` asserts a
module was emitted for it and that no symbol of it leaked into `main.js`:

```scala
def bundleFeatures = Seq(BundleShape.Feature("kui.ui.topics.TopicsFeature", "kui.ui.topics"))
```

Then:

```bash
./mill frontend.uiShell.fullLinkJS
./mill frontend.uiShell.checkBundleShape
```

## Loading states

`FeatureRegistry.lazyFeature(id)` returns a `LazyFeature`, whose `state` is one of:

| State | Meaning |
| --- | --- |
| `NotLoaded` | nothing has been requested; not a byte of this feature has been fetched |
| `Loading` | the import is in flight |
| `Loaded(feature)` | ready |
| `Failed(cause)` | the import failed, and `retry()` will try again |

`load()` is idempotent — ten calls import once — and the memoisation is deliberately "do not call the
thunk again while a call is outstanding or has succeeded" rather than "remember the promise", because
remembering the promise would make a failed import permanent.

`Failed` is not an edge case to shrug at. A dynamic import is an HTTP request made minutes after the
page loaded, over whatever connection the user has now, and it fails often enough that "the route
renders nothing" would be a real user experience. The shell renders the feature's fallback with a
working retry instead.

An id with no registered thunk yields a `Failed` state immediately, saying the feature is not part of
this build. That is a definite answer the shell can render, where a route that stays blank forever is
not.

## Cross-feature panels

The topic page wants a "Consumers" tab, and only the consumers feature knows how to draw one. The
topics feature must not import it — that would make every visit to a topic page download the
consumers module, for every user, including users with no permission to see consumer groups.

So the dependency is inverted. The host declares a named slot; contributing features register a
`PanelContribution` for `(host, slot)`; the host renders whatever turns up:

```scala
// in the consumers feature
override def panels: List[PanelContribution] =
  List(PanelContribution(FeatureId.Topics, "topic.tabs", ctx => ConsumersForTopic(ctx)))

// in the topics feature's page
FeaturePanel(FeatureRegistry.loaded, FeatureId.Topics, "topic.tabs", PanelContext(cluster, params))
```

`FeaturePanel` renders from the **loaded** features and from nothing else. That is what makes "a host
page never triggers a download" true by construction rather than by discipline. The consequence is
worth stating plainly: a panel appears once its feature has been loaded for some other reason — the
user visited it, or the shell preloaded it because its capability is available — and until then the
slot is simply empty. That is the intended behaviour.

`PanelContext` is deliberately narrow: the cluster and a few string parameters. Passing the host
page's state object would make the two features share a type, which is the coupling this whole
arrangement exists to avoid.

## Route patterns are registered before your feature is downloaded

A bookmarked link to one of your pages has to work on the first load, when your module has not been
fetched yet. So the shell needs your **route patterns** — which URLs you own — eagerly, and your
**render functions** only once a route matches (ADR-012 amendment 2).

That means a registry entry carries two things, and they must stay separate:

```scala
// Data. Path shapes. Linked against normally, and it costs a few bytes in main.js.
def routes: List[Route[? <: Page, ?]]

// Code. Everything reachable only through this thunk goes into your own JavaScript module.
FeatureId.Topics -> (() => js.dynamicImport(new kui.ui.topics.TopicsFeature()))
```

If you put a route pattern inside the thunk, a deep link to your feature 404s until somebody has
already navigated into it by hand — which nobody will notice in development, because you always
arrive from the sidebar. `checkBundleShape` (BUILD-006) catches the opposite mistake: a class
reference leaking out of the thunk, which puts your whole feature in `main.js` for every user.

Your pages also need a `history.state` codec. Until UI-012 adds the first one, `PageCodec` in the
shell only knows the shell's own pages, and a feature page serializes as `unknown` and comes back as
`NotFound` — which means Back onto one re-parses the URL rather than restoring the state. Adding the
first feature is where that gap is closed.
