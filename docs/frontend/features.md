# Adding a microfrontend

This document is written for somebody who has never seen this project. It describes what a
microfrontend is in KUI, the seven steps that add one, and the five rules that were each learned by
getting something wrong in a way nobody could see.

It describes **what `@kui/feature-topics` does**. `@kui/feature-clusters` came first, is nearly the
same shape, and is described here as the earlier approximation: where the two disagree,
`feature-topics` is the pattern, because it was written with `feature-clusters`' recorded deviations
in front of it. Nothing here has been extracted into a base component — an abstraction over two data
points is a guess.

> **A note on this document.** KUI's browser code was Scala.js and Laminar until 2026-09-05
> (ADR-048). The *policy* below — the two-halves registration, the split border, the four failure
> states, the five rules — was ported rather than redesigned, which is why it is still here. Sections
> 1 to 4 and section 8's harness facts have been reconciled against the TypeScript. Later sections
> still show Scala in their examples: read the rule and not the syntax, and check the package for the
> current spelling.

Its sibling is [`components.md`](components.md), which is about the shared *components* a feature
draws with. Neither document repeats the other.

---

## 1. What a microfrontend is here

A **feature** is a self-contained slice of the product: its own URLs, its own screens, its own state
and its own requests. Topics is a feature. Clusters is a feature. Messages, consumer groups, schemas
and connectors will be.

Concretely, a feature is:

- a **package of its own** in the pnpm workspace (`frontend/packages/feature-topics`, published to
  the workspace as `@kui/feature-topics`),
- whose **default export** is the root SolidJS component the shell renders for every one of its
  routes,
- which **Vite emits as its own chunk**, separate from the entry chunk,
- fetched by the browser **the first time somebody navigates to it**,
- and named in **exactly one place** in the whole application.

The reason for all of that is ADR-012. KUI is one application that can be deployed with different
services present: an installation without a schema registry has no schema service, and a user of
that installation should not download the schema screens. A user who only ever looks at the
dashboard should not download the topic explorer either. So the code is split at the feature
boundary and fetched on demand.

The mechanism is one expression, in `frontend/packages/shell/src/features/registry.ts`:

```ts
load: () => import("@kui/feature-topics").then(featureModule),
```

A dynamic `import()` with a literal specifier is a **split border** to the bundler. Everything
reachable *only* through it goes into a chunk of its own. Read §4 before you write one, because the
border is easy to destroy by accident and destroying it looks like nothing at all.

---

## 2. The registration checklist

Seven steps. Each one names the file. The exercise that produced this list was performed by adding a
throwaway feature and deleting it again, because a checklist nobody has followed is a checklist with
a missing step — and the first draft of this list was missing step 7.

### 1. The package — `frontend/packages/feature-topics/`

A `package.json` naming it `@kui/feature-topics`, a `tsconfig.json` extending the workspace's, a
`src/` and a `styles/`. Copy `feature-clusters`; there is nothing bespoke in the scaffolding.

Depend on `@kui/kernel` and `@kui/api`. Depend on the **shell** not at all: the shell depends on
every feature, and the reverse edge would be a cycle as well as the end of lazy loading. Depend on
another *feature* not at all either — if you need something a feature has, it belongs in the kernel
(§5, rule 5).

`pnpm install` after adding the package, so the workspace links it.

### 2. The stylesheet — `frontend/packages/feature-topics/styles/`, and `index.css`

Files numbered so the cascade is decided by file order rather than by who nested their selectors more
deeply. The kernel owns 00–29; features start at 40.

Then add each file to the `@import` list in `frontend/packages/kernel/styles/index.css`, which is
what Vite inlines. A hand-written list can be forgotten, so `kui.build.CssReferences` (in
`build-tests/`) fails the build if a `styles/*.css` file in the workspace is missing from that list
or named by it twice.

### 3. The feature id — `frontend/packages/kernel/src/feature/registration.ts`

```ts
export type FeatureId = "clusters" | "topics" | "messages" | "consumers" | "schemas";
```

A string union rather than an enum, so a registration table is checked exhaustively and a bookmark
naming a feature this build does not have simply fails to match.

### 4. The routes — `frontend/packages/shell/src/routing/routes.tsx`

The patterns are literals in the shell's one route table, not in the feature. That is what makes the
router's typed `paths` proxy work — `paths.clusters("prod").topics()` exists and a renamed segment is
a compile error — and it is what lets a deep link resolve before the feature's chunk exists. See §3.

### 5. The root component — `frontend/packages/feature-topics/src/index.ts(x)`

A `default` export: the component the shell renders for every one of that feature's routes.
Everything else in the package is reachable only from there.

### 6. Registration — `frontend/packages/shell/src/features/registry.ts`

One entry in `featureRegistry`, and its two halves follow opposite rules:

```ts
{
  id: "topics",
  serviceId: "topic",          // the service the capability registry reports health under
  viewAction: Actions.TopicView,  // from the generated RBAC vocabulary, never a hand-typed string
  label: "Topics",
  icon: "topics",
  group: "Cluster",
  order: 200,
  requiresCluster: true,
  sidebar: true,
  // The dynamic half. The body is a bare `import()` with a literal specifier — see §4.
  load: () => import("@kui/feature-topics").then(featureModule),
}
```

`serviceId` is carried rather than guessed from `id`, because the two are not always the same word:
`topics` is a feature and `topic` is the service behind it. `viewAction` is likewise stated rather
than derived — the shell once asked `permits("topic", "view", …)` against a server whose vocabulary
spells them `TOPIC` and `VIEW`, matching is by exact string, and every entry in the drawer went dim
on a deployment with authentication disabled. Typing it as `PermissionAction` from `Actions` is what
stops that returning.

### 7. The bundle-shape check

Nothing needs registering for the split itself — Vite splits at every dynamic `import()` — but the
check that the split *survived* has to know about the new feature. It reads the build manifest's
module graph and asks whether the feature's module is in the entry chunk's `imports` (static, and
therefore downloaded by everyone) or its `dynamicImports` (split, and therefore downloaded on
demand). See §4 for why that is a fact about the emitted graph rather than something a review can
see.

Finish with the whole set, from `frontend/`:

```bash
$ pnpm install
$ pnpm typecheck
$ pnpm test
$ pnpm build          # must emit one more feature chunk than before
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
from the shell costs a few bytes in the entry chunk and pulls no feature code with them.

A **page** is data too. `TopicsPageId.Detail(clusterId, topic, tab)` carries what the URL is built
from and parsed into, and nothing about how anything is drawn. That is what lets the shell hold a
route without holding the code that renders it.

Two details worth copying:

- **Identifiers on a page are `String`, not `ClusterId`.** A page has to survive a round trip through
  `history.state`, and a URL can hold anything a user types. It is validated where it is *used*, so a
  value that will not parse renders the page's own fallback rather than failing to decode the whole
  history entry — which would strand the Back button.
- **A pattern matches a whole path, never a prefix.** A pattern that claimed its own sub-paths would
  make `/ui/clusters/x/topics` also match `/ui/clusters/x/topics/anything`, and a mistyped URL would
  never 404: it would silently resolve to the page above it. Add the catch-all `*` route deliberately
  or not at all.

---

## 4. The split border, and how it is destroyed

The thunk's body must be

```ts
() => import("@kui/feature-topics").then(featureModule)
```

and nothing else that *statically* names the feature. A top-level `import` of the package, a type
annotation naming its root component, a value pulled out "for convenience" — any of them makes the
feature reachable from the entry chunk, and the bundler then ships it to every user on first paint,
including users whose deployment has no topic service at all. The specifier must stay a literal:
`import(somePath)` cannot be split, because the bundler cannot know at build time what it names.

Chaining `featureModule` onto the import is safe — it names no feature and the specifier stays
literal. It exists because TypeScript synthesises a `default` for a module that has none, so "does
this chunk export a root component" cannot be asked of the type, only of the value.

**Nothing about the source looks different when the split is destroyed.** That is why the check reads
the *build manifest's module graph* rather than trusting a reviewer to spot it.

### "Split out" and "not downloaded" are two different statements

Read this before concluding from a green build that a feature is lazily loaded. Under Scala.js the
two came apart: the clusters feature *was* linked into a module of its own, and `main.js` then
statically imported it, so the browser fetched it during first paint anyway (**TD-016**). The check
of the day was not wrong — it asked whether the feature's code had been *copied into* the entry, and
it had not — it simply asked a question a browser does not act on.

The lesson survived the rewrite even though the mechanism did not. A Vite manifest distinguishes an
entry chunk's `imports` from its `dynamicImports`, and only the second is a download deferred to
first navigation. Assert against `dynamicImports`; a feature that appears under `imports` is shipped
to everybody, including users whose deployment has no such service. TD-016 is marked superseded in
`TECH_DEBT.md` with re-checking it against a real manifest as its exit condition.

---

## 5. State, pages and navigation

### The feature builds its own client and its own state

A dynamic `import()` fetches a module, not a configured object, so a feature is handed nothing at
import time. It reaches for the kernel's singletons — the bootstrap block the gateway injected, the session — exactly as the shell
does, and constructs its own `Queries` object:

```scala
final class TopicsFeature extends KuiFeature {
  private val queries = new TopicsQueries(TopicsFeature.api)
  private val favourites = new Favourites("kui.topics.favourites")
}
```

**Feature state is a class holding `Var`s, never a global**. A global cache is shared by
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

If two features need the same thing, it goes in `@kui/kernel`. The byte formatter lived in the
clusters feature until the topic list became its fourth caller, at which point the choice was to
promote it or to copy it, and two copies of a rounding rule are two answers to "is this 1.0 MiB". It
was promoted, and the clusters feature kept a re-export at the old path so that its call sites and
its suite were untouched — which is the evidence that it was a move and not a rewrite.

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

Vitest under jsdom by default (`environment: "jsdom"` in `frontend/vitest.config.ts`), because a
feature's sharpest claims are claims about what ends up in the DOM. Plain `.test.ts` suites for row
models and formatters — they need no DOM and should not pay for one.

**Recorded responses, never hand-written fixtures, for anything that crosses a process boundary.**
Here is why, stated as it happened:

> M1's dashboard declared `ClustersResponse` while the gateway sends `ClusterOverviewDto`. The
> decoder defaulted a missing `items` field to `Nil`, so every response decoded *successfully* into
> zero rows. The page rendered "No clusters yet" under a "last updated just now" timestamp, against
> a working broker, with no error anywhere. Both modules' own suites were green, because each tested
> itself against its own idea of the payload.

A hand-written fixture is a description of what the author *believed* the server sends, which is the
belief that was wrong. So `TopicsApiSuite` decodes `GoldenDocuments` — the topic contract's own
committed sample documents — rather than by copying them. One artefact, read from both sides, cannot
drift. Since ADR-048 the browser cannot depend on a Scala test module for them, so the same property
is carried by the generated types: `@kui/api`'s `schema.d.ts` comes from
`docs/api/openapi.browser.json`, which the Scala build regenerates from the gateway's own endpoints,
and a suite that decodes a shape the server does not send fails to typecheck. Test modules are exempt from the architecture
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
- **Mill runs none of this.** The suites are Vitest, run with `pnpm test` from `frontend/`, and the
  backend's `./mill __.test` neither builds nor runs them. The two builds share no lock, so they can
  run at the same time.

---

## 9. The five rules, and the incident behind each

1. **The thunk body is a bare `import("@kui/feature-…")` and nothing else.** Naming a feature's
   module anywhere else in the shell puts the whole feature in the entry chunk, and nothing about the
   source looks different. (ADR-012 as amended by ADR-048 §4; the bundle-shape check exists for
   this.)
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

- **TD-016** — the entry statically imported the split feature module, so ADR-012's promise was not
  kept for any user. Superseded by ADR-048; re-check it against a Vite build manifest. §4.
- **TD-020** — a feature had no navigation port and called `history.pushState` directly, so Back was
  resolved from the URL rather than from stored state. Superseded by ADR-048; re-check whether any
  feature package still touches `history` directly. §5.
- **TD-015** — "the microfrontend pattern is decided against a real screen and this document records
  it" — is **closed** by this document.
