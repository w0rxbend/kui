# Adding a microfrontend

This document is written for somebody who has never seen this project. It describes what a
microfrontend is in KUI, the seven steps that add one, and the five rules that were each learned by
getting something wrong in a way nobody could see.

It describes **what `ui-topics` does**. `ui-clusters` came first, is nearly the same shape, and is
described here as the earlier approximation: where the two disagree, `ui-topics` is the pattern,
because it was written with `ui-clusters`' recorded deviations in front of it. A third feature (M3's
message explorer) will be the one that discovers whether any of this fits badly, which is why
nothing here has been extracted into a base class — an abstraction over two data points is a guess.

Its sibling is [`components.md`](components.md), which is about the shared *components* a feature
draws with. Neither document repeats the other.

---

## 1. What a microfrontend is here

A **feature** is a self-contained slice of the product: its own URLs, its own screens, its own state
and its own requests. Topics is a feature. Clusters is a feature. Messages, consumer groups, schemas
and connectors will be.

Concretely, a feature is:

- a **Scala.js class** implementing `kui.ui.kernel.feature.KuiFeature`,
- in a **Mill module of its own** (`frontend/ui-topics`),
- which the Scala.js linker emits as **its own JavaScript file**, separate from `main.js`,
- fetched by the browser **the first time somebody navigates to it**,
- and named in **exactly one place** in the whole application.

The reason for all of that is ADR-012. KUI is one application that can be deployed with different
services present: an installation without a schema registry has no schema service, and a user of
that installation should not download the schema screens. A user who only ever looks at the
dashboard should not download the topic explorer either. So the code is split at the feature
boundary and fetched on demand.

The mechanism is one expression, in `kui.ui.shell.FeatureRegistryImpl`:

```scala
FeatureId.Topics -> (() => js.dynamicImport(new kui.ui.topics.TopicsFeature()))
```

`js.dynamicImport` is a **split border** to the Scala.js linker. Everything reachable *only* through
it is put into a separate JavaScript module. Read §4 before you write one, because the border is
easy to destroy by accident and destroying it looks like nothing at all.

---

## 2. The registration checklist

Seven steps. Each one names the file. The exercise that produced this list was performed by adding a
throwaway feature and deleting it again, because a checklist nobody has followed is a checklist with
a missing step — and the first draft of this list was missing step 7.

### 1. The Mill module — `build.mill`

```scala
object uiTopics extends KuiFrontendModule {

  // Kebab-case on disk, camelCase on the command line, for the same reason as `uiKernel`.
  def moduleDir = super.moduleDir / os.up / "ui-topics"

  def moduleDeps = Seq(uiKernel, services.topic.contract.js, services.gateway.contract.js)

  object test extends ScalaJSTests with KuiJsDomTests {
    def mvnDeps = super.mvnDeps() ++ Seq(mvn"com.raquo::domtestutils::${Versions.domtestutils}")
  }
}
```

Depend on `ui-kernel` and on the **cross-compiled contract of every service whose documents you
decode**. Depend on the shell not at all: the shell depends on every feature, and the reverse edge
would be a cycle as well as the end of lazy loading. Depend on another *feature* not at all either —
if you need something a feature has, it belongs in the kernel (§5, rule 5).

### 2. The stylesheet directory — `frontend/ui-topics/resources/css/`, and `cssModules`

One directory, files numbered so the cascade is decided by the file order rather than by who nested
their selectors more deeply. The kernel owns 00–29, features start at 40. Add the module to
`cssModules` in `build.mill` or the stylesheet is silently not concatenated — `./mill frontend.css`
prints how many source files it found, which is how you check.

### 3. The feature id — `frontend/ui-kernel/src/kui/ui/kernel/feature/FeatureId.scala`

```scala
case Topics extends FeatureId("topics", "topic")
```

Two strings: the **feature** id, which appears in URLs and preferences, and the **service** id, which
is what the gateway's capability registry reports health under. They are not always the same word —
`topics` is a feature, `topic` is the service behind it — which is exactly why one is not derived
from the other.

### 4. The static half — `TopicsRoutes`

An `object` extending `FeatureRoutes`: the sidebar entry, the URL patterns, and the `history.state`
codec. See §3 for why these are separate from the feature class.

### 5. The dynamic half — `TopicsFeature`

A `final class` extending `KuiFeature`. Everything else in the module is reachable only from here.

### 6. Registration — `frontend/ui-shell/src/kui/ui/shell/FeatureRegistryImpl.scala`

Two lines, and they follow opposite rules:

```scala
// The thunk. Its body is `js.dynamicImport(new …)` and NOTHING else — see §4.
FeatureId.Topics -> (() => js.dynamicImport(new kui.ui.topics.TopicsFeature()))

// The static half, named directly, which is correct and not an inconsistency.
def staticRoutes: List[FeatureRoutes] = List(ClustersRoutes, TopicsRoutes)
```

### 7. The linker and the check — `build.mill`, twice more

```scala
// KuiFrontendModule: the packages the linker emits one small module per class for.
def moduleSplitStyle = ModuleSplitStyle.SmallModulesFor("kui.ui.clusters", "kui.ui.topics")

// uiShell: what checkBundleShape asserts is actually split out.
def bundleFeatures = Seq(
  BundleShape.Feature("kui.ui.clusters.ClustersFeature", "kui.ui.clusters"),
  BundleShape.Feature("kui.ui.topics.TopicsFeature", "kui.ui.topics")
)
```

**This is the step that is easy to miss**, and it was missing from the first version of this list. A
package absent from `moduleSplitStyle` still works perfectly — it is simply not splittable, and the
whole feature ends up in `main.js`. `checkBundleShape` is what catches it:

```
$ ./mill frontend.uiShell.checkBundleShape
checkBundleShape: 1 problem(s):
  no module file matching kui.ui.topics*.js was linked, so kui.ui.topics.TopicsFeature cannot be
  loaded lazily
```

Finish with the whole set. Each frontend command is its own line for the reason in §8.

```
$ ./mill frontend.uiTopics.compile
$ ./mill frontend.uiTopics.test
$ ./mill frontend.uiShell.checkBundleShape     # must report one more feature module than before
$ ./mill frontend.css                          # must report one more source file than before
$ ./mill checkArchitecture
```

---

## 3. The static half, and why a feature is registered twice

Three things about a feature have to be known **before** its JavaScript has been downloaded, and
each misbehaves visibly without it:

- **Its URLs.** A bookmarked link to `/ui/clusters/prod-eu/topics` must resolve on the first load.
  If the router only learned the pattern once the feature had been imported, the first address it
  saw would be one it could not match, and the user would get a 404 for a page that exists.
- **Its sidebar entry, and where that entry points.** The navigation is drawn on first paint. If
  drawing a link required fetching the feature, the whole arrangement would be pointless.
- **How its pages are written into `history.state`.** The browser hands that back *synchronously*
  when the user presses Back. There is no moment at which to await an import. Without it, Back onto
  a feature page decodes to "not found".

All three are **data** — a label, a sort order, path shapes, a JSON tag — so linking against them
from the shell costs a few bytes in `main.js` and pulls no feature code with them.

A **page** is data too. `TopicsPageId.Detail(clusterId, topic, tab)` carries what the URL is built
from and parsed into, and nothing about how anything is drawn. That is what lets the shell hold a
route without holding the code that renders it.

Two details worth copying:

- **Identifiers on a page are `String`, not `ClusterId`.** A page has to survive a round trip through
  `history.state`, and a URL can hold anything a user types. It is validated where it is *used*, so a
  value that will not parse renders the page's own fallback rather than failing to decode the whole
  history entry — which would strand the Back button.
- **Every pattern ends with `endOfSegments`.** Without it a pattern claims its own sub-paths, so
  `/ui/clusters/x/topics` also matches `/ui/clusters/x/topics/anything` and a mistyped URL never
  404s: it silently resolves to the page above it.

---

## 4. The split border, and how it is destroyed

The thunk's body must be

```scala
() => js.dynamicImport(new kui.ui.topics.TopicsFeature())
```

and nothing else. Assigning the constructor to a `val`, naming the feature's type in a signature, or
so much as mentioning the class anywhere outside that import makes it reachable from the shell, and
the linker then puts the whole feature into `main.js` — where every user downloads it on first
paint, including users whose deployment has no topic service at all.

**Nothing about the source looks different when that happens.** That is why `checkBundleShape`
asserts the shape of the *linked output* rather than trusting a reviewer to spot it.

### And the check passes today while the promise is broken

Read this before concluding from a green build that a feature is lazily loaded. **TD-016**: the
clusters microfrontend *is* linked into a module of its own, and `main.js` then **statically
imports** that module — so the browser downloads it during the first paint anyway. `checkBundleShape`
is not wrong: it checks that the feature's code is not *copied into* `main.js*`, which is true.
Nothing yet checks that `main.js` does not *import the split file eagerly*, which is what a browser
acts on. The debt is open, it names its own exit condition, and until it is closed "the module is
split out" and "the module is not downloaded" are two different statements.

---

## 5. State, pages and navigation

### The feature builds its own client and its own state

`js.dynamicImport` cannot pass constructor arguments, so a feature takes none. It reaches for the
kernel's singletons — the bootstrap block the gateway injected, the session — exactly as the shell
does, and constructs its own `Queries` object:

```scala
final class TopicsFeature extends KuiFeature {
  private val queries = new TopicsQueries(TopicsFeature.api)
  private val favourites = new Favourites("kui.topics.favourites")
}
```

**Feature state is a class holding `Var`s, never a global** (PLAN §21). A global cache is shared by
every instance of the feature and outlives all of them, so two tabs would fight over one list and a
test would inherit the previous test's rows.

`TopicsQueries` is the **only file in the feature that issues a request**. That is what makes the
health-reporting rule checkable by reading rather than by grepping: every call goes through one
private method that reports `CallScope.Feature` — never `CallScope.Shell`, because a failure here
means this feature cannot show its data and must never be able to take the whole application away
from the user (ADR-032). That is the difference between a dimmed sidebar entry and a full-screen
"cannot reach the gateway".

### A page is a function from signals to an element

```scala
object TopicListPage {
  def apply(
      cluster: ClusterId,
      queries: TopicsQueries,
      favourites: Favourites,
      navigate: (ClusterId, String) => Unit,
      hrefFor: (ClusterId, String) => String,
      zone: Signal[String]
  ): HtmlElement
}
```

No router, no globals, no service locator. Everything it needs is a parameter, which is what makes
it drivable from a suite. Note `now: () => Instant` and the various `Var`s with defaults on the real
signatures: each exists because something in the page is otherwise untestable, and each is documented
where it is declared.

### A row model is a pure function — the highest-value convention here

```scala
final case class TopicRow(name: String, internal: Boolean, /* … */) {
  def missingCountReason: Option[String]
}

object TopicRow {
  def of(page: PageDto[TopicRowDto], favourites: Set[String]): List[TopicRow]
  def pin(rows: List[TopicRow]): List[TopicRow]
}
```

Every rendering rule the table obeys is decided by a total function from the response to a plain
value with no `Signal` in it. So each rule is **one row in a test table** rather than a rendering to
squint at, and the sharp ones — "an absent message count with offline partitions says why, and never
`0`" — are asserted in a suite that mounts nothing. Both features do this. Do it.

### Navigation: a real link, and a callback

A row is a real `<a>` with a real `href`, so copy, bookmark and open-in-new-tab all work. An ordinary
click is intercepted, the URL pushed, and the page swapped. The page takes `navigate` and `hrefFor`
rather than reaching for a router.

**TD-020** is what is still missing: `KuiFeature` has no navigation port, so a feature calls
`history.pushState` itself and the pushed entry carries no `history.state`. Back onto a feature page
is therefore resolved from the URL rather than from stored state. If you find `history.pushState`
in a feature and expect a router, this is why.

### Rule 5: a feature never depends on another feature

If two features need the same thing, it goes in `ui-kernel`. `Bytes` — the byte formatter — lived in
`ui-clusters` until the topic list became its fourth caller, at which point the choice was to promote
it or to copy it, and two copies of a rounding rule are two answers to "is this 1.0 MiB". It was
promoted, and `ui-clusters` keeps a two-line re-export at the old path so that its call sites and its
suite are untouched — which is the evidence that it was a move and not a rewrite.

The exception is not an exception: one feature's *panel* on another feature's page goes through
`FeatureSlots` and `GuestTabs`, where neither side can see the other and the slot id is a kernel
constant. See §7.

---

## 6. Failure rendering: the four states

ADR-032 gives a response four possible shapes, and each has exactly one rendering. The topic list's
row model is the worked example:

| Response state | Table | Controls | Overlay |
| --- | --- | --- | --- |
| `Ok(page)` | the rows | enabled | none |
| `Stale(page, fetchedAt, reason)` | the rows, dimmed | refresh **disabled**; search and sort still work | dimmed, badged with `fetchedAt` |
| `Unavailable`, previous rows held | the previous rows, dimmed | refresh disabled | dimmed, badged, reason shown |
| `Unavailable`, nothing held | error region with the reason and "Try again" | disabled | none — there is nothing under it |
| `Forbidden` | an empty state: "you may not view topics on this cluster" | disabled | none |
| `NotConfigured` | **nothing at all** | — | — |

Three rules that are easy to get wrong:

- **`Forbidden` is not an error.** The request worked; the answer is that this user may not see it. A
  "Try again" button invites them to press something that will do exactly the same thing.
- **`NotConfigured` renders nothing.** This deployment does not have that thing. A permanent
  "unavailable" panel for a service that does not exist trains an operator to ignore the colour that
  matters, including on the day one of them means something.
- **The reason is rendered verbatim.** An operator whose cluster is down needs the string they can
  search for or paste into a ticket, not a friendlier paraphrase of it.

And the rule underneath all of them: **an empty result and a failed request are different answers.**
An empty page from a cluster with ten thousand topics is a lie that looks like data.

---

## 7. Cross-feature panels

The topic page has an Overview tab and a Settings tab of its own. M3 wants a Messages tab on it and
M4 wants a Consumers tab, and neither may edit a file in the topics feature — that is the whole point
of ADR-012's inversion.

The host offers a slot; guests register into it:

```scala
// The kernel, visible to both: FeatureSlots.TopicTabs, FeatureSlots.TopicParam.
// The guest, in its own feature class:
override def panels: List[PanelContribution] = List(
  PanelContribution(
    host = FeatureId.Topics,
    slot = FeatureSlots.TopicTabs,
    tabLabel = Some("Consumers"),
    render = context => ConsumersPanel(context.params(FeatureSlots.TopicParam))
  )
)

// The host:
GuestTabs.merged(ownTabs, FeatureRegistry.loaded, FeatureId.Topics, FeatureSlots.TopicTabs, context)
```

The slot id is a **kernel constant and never a string literal on either side**. Written as a literal
twice, a typo has a failure mode no test on either side can see: the guest registers, the host
renders, and the tab simply never appears, with no error anywhere.

Two behaviours that surprise people:

- **A guest never causes a download.** The tab list is derived from the *loaded* features. A feature
  that has not been downloaded contributes no tab and is not fetched to find out — a host page is
  never a reason to load another feature. Its tab appears once its feature has loaded for some other
  reason.
- **A slot that nothing fills renders one tab fewer**, never a disabled tab and never a placeholder
  promising a later milestone. On the topic detail page the four future sections are empty
  containers with stable `data-testid`s, so a registration is visible in a test the moment it lands
  and takes no space until then.

---

## 8. Testing

jsdom by default (`KuiJsDomTests`), because a feature's sharpest claims are claims about what ends up
in the DOM. Pure suites for row models and formatters — they need no DOM and should not pay for one.
`domtestutils` for the rest.

**Recorded responses, never hand-written fixtures, for anything that crosses a process boundary.**
Here is why, stated as it happened:

> M1's dashboard declared `ClustersResponse` while the gateway sends `ClusterOverviewDto`. The
> decoder defaulted a missing `items` field to `Nil`, so every response decoded *successfully* into
> zero rows. The page rendered "No clusters yet" under a "last updated just now" timestamp, against
> a working broker, with no error anywhere. Both modules' own suites were green, because each tested
> itself against its own idea of the payload.

A hand-written fixture is a description of what the author *believed* the server sends, which is the
belief that was wrong. So `TopicsApiSuite` decodes `GoldenDocuments` — the topic contract's own
committed sample documents — by depending on `services.topic.contract.js.test` rather than by copying
them. One artefact, read from both sides, cannot drift. Test modules are exempt from the architecture
rules (`ArchitectureRules.isTestModule`), so that dependency is legal.

Assert the paths too. A segment renamed in a contract while a feature keeps calling the old address
compiles perfectly — a segment is a `String` — and 404s in production. `TopicsApiSuite` builds every
expected URL out of the contract's own constants.

Two harness facts:

- **jsdom performs no layout.** Every element reports a `clientHeight` of zero, forever. A component
  that could only measure itself would render an empty window in every test and the tests would pass
  while asserting nothing. `VirtualizedTable` and the pages that use it therefore take a
  `viewportHeight: Var[Int]` which the component fills in from the real element in a browser and a
  suite sets by hand.
- **Two Scala.js test modules cannot be named in one Mill invocation.** That is why every frontend
  command in this repository is on its own line. It is recorded in `STATUS.md`.

---

## 9. The five rules, and the incident behind each

1. **The thunk body is `js.dynamicImport(new …)` and nothing else.** Naming a feature class anywhere
   else in the shell puts the whole feature in `main.js`, and nothing about the source looks
   different. (ADR-012; `checkBundleShape` exists for this.)
2. **A decoder must not default a missing list to empty.** M1's dashboard rendered "No clusters yet"
   against a working broker because a wrong document decoded successfully into no rows.
3. **One string typed in two files will drift.** Slot ids, class names, path segments, row heights:
   declare each once, where both sides can see it, so a typo is a compile error. The M0 review's
   second process finding.
4. **An empty result and a failed request are different answers**, and so are "not configured" and
   "unavailable", and so are "you may not look" and "there is nothing here". Every pair has a
   different next action for the user.
5. **A feature never depends on another feature.** Promote to the kernel, or go through
   `FeatureSlots`. Never copy.

---

## 10. What is still owed

- **TD-016** — `main.js` statically imports the split feature module, so ADR-012's promise is not yet
  kept for any user. Open. §4.
- **TD-020** — `KuiFeature` has no navigation port, so features call `history.pushState` directly and
  Back is resolved from the URL rather than from stored state. Open. §5.
- **TD-015** — "the microfrontend pattern is decided against a real screen and this document records
  it" — is **closed** by this document.
