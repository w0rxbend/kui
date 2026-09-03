# CLUI-006 — Shell: cluster switcher, status dot, per-cluster colour tag

- **ID:** CLUI-006
- **Title:** Shell: cluster switcher, status dot, per-cluster colour tag
- **Milestone / Feature:** M1 / CL-009, CL-002
- **Owner role:** Frontend Architect
- **Size:** M
- **Dependencies / blocked by:** CLUI-003 (there has to be a cluster screen to switch to),
  CLAPI-008 (per-cluster capability entries in the registry). **Sequencing constraint:** this task
  edits `frontend/ui-shell`, which another swarm is restyling. Do not start until the restyle has
  landed on `main` (DEVPLAN §6.1, risk R-9); check `git log --oneline -- frontend/ui-shell` first.

## Goal (user value)

An operator running four clusters — production in two regions, staging, and a local one — must be
able to tell at a glance which one they are looking at, and must not be able to run a command
against production while believing they are on staging. The switcher makes the current cluster
permanently visible, the status dot says whether it is answering, and the colour tag lets a person
give production a colour they will notice. Kafbat found the colour tag worth building; the reason it
matters is that cluster names in real deployments differ by one character.

## Scope

1. **A cluster switcher in the navigation drawer**, directly under the wordmark and above the
   destinations, because from M2 onward every destination below it is scoped to the chosen cluster
   and the context has to sit above the things it scopes.
2. The trigger shows the current cluster's colour tag, its name and its status dot; opening it lists
   every cluster the registry knows, each with its own tag, name and dot. Choosing one sets
   `CurrentCluster` and navigates to that cluster's brokers page.
3. **The status dot** comes from the capability registry's per-cluster entry, folded through the
   rules the shell already implements: `Ready`, `Degraded(reason)`, `Unavailable(reason, since)`,
   `NotConfigured`. Its tooltip is the state word and the reason verbatim — ADR-032's single rule
   for reasons, already followed by the sidebar's feature entries.
4. **The colour tag** is a user preference, per cluster, stored in the browser. Six choices plus
   "none".
5. **Keyboard and screen-reader behaviour** equal to the existing `Select`: the trigger is a button
   with `aria-haspopup="listbox"`, the menu is a listbox, arrows move, Enter and Space choose, Escape
   closes and returns focus, and the current cluster is `aria-selected`.
6. **A cluster in the URL wins over the stored selection.** Landing on
   `/ui/clusters/prod-eu/brokers` sets the current cluster to `prod-eu`, whatever was stored. A
   pasted link must show what the sender saw.

## Where the cluster list comes from, and why it is not the cluster contract

The shell reads the **capability registry**, which it already subscribes to
(`CapabilityStore`, `/api/v1/capabilities` and its SSE stream), and which CLAPI-008 extends with a
per-cluster entry per service. It does not call the cluster service.

Three reasons, and they are the whole design of this task:

- The shell must not hold cluster *data*. `frontend/ui-shell` depends on `frontend/ui-clusters` at
  compile time only through `ClustersRoutes`, and every other reference goes through
  `js.dynamicImport` so the linker can keep the feature out of `main.js` (ADR-012,
  `checkBundleShape`). A shell that fetched cluster DTOs would need the cluster contract's decoders
  in the first bundle every user downloads, including deployments with no cluster service.
- The status dot is a *health* question, and health lives in the registry by construction
  (ADR-039). Reading it from a second place would give the switcher and the sidebar two different
  opinions about the same cluster.
- The registry stream is already open, already pushes changes, and already debounces transitions.
  The switcher gets live status for no new connection.

**Requirement on CLAPI-008, stated here because this is the consumer:** each per-cluster registry
entry must carry the cluster's **id** and its **display name**. Without the name the switcher can
only show slugs, and `prod-eu-1` is exactly the kind of string this feature exists to stop people
misreading. If the name is absent the switcher falls back to the id — it degrades, it does not
break — and the gap is reported to CLAPI rather than patched with a second call.

## The colour tag

- **Where it is stored.** `localStorage`, key `kui.cluster.color.<clusterId>`, through the same
  mechanism `Theme`, `Accent` and `Density` already use (`RootPreference.persisted`, which tolerates
  storage being unavailable and behaves as an in-memory value in a private window). The feature
  matrix already says "colour stored client-side"; nothing is sent to the server, so two operators
  can colour the same cluster differently, which is correct — it is a personal marker, not a
  property of the cluster.
- **The palette, and a decision no ADR covers.** Kafbat offers ten arbitrary colours. KUI offers
  **six, expressed as existing semantic tokens** — none, info, success, warning, danger, tertiary —
  and no raw colour value is written in Scala or introduced into the token set. ADR-024 forbids
  component-scoped tokens and forbids computing colour in Scala, and `research/design/REFERENCE.md`
  defines no colour-tag palette at all: it gives four *seed* palettes, which are the accent, not a
  per-object marker. Inventing ten hexes would put ten values in the product that no theme controls,
  and they would be wrong in one of the two themes. Six token-backed choices are enough to
  distinguish four clusters, and they are correct in light and dark by construction because each is
  a container colour with a paired text colour.
- **The tag renders as a small filled chip**, not a bare dot, matching the design's rule that status
  is a filled chip using a container colour and its paired text colour. The *status* dot is a
  separate mark, and the two must be visually distinguishable: the tag is a rounded rectangle, the
  status a circle.
- **Choosing a colour** is a small menu on the switcher's row for each cluster, not a separate
  settings screen: it is a per-cluster property and the switcher is where clusters are listed.

## Non-goals

- **No cluster creation, editing or deletion.** M8.
- **No per-cluster nav sub-tree.** Kafbat nests Brokers / Topics / Consumers under each cluster in
  the sidebar. KUI has one cluster-scoped feature in M1; a tree with one leaf per cluster is a
  structure built for M2 to inherit, and building it now means designing it without its second
  occupant. The destinations stay flat and the switcher scopes them.
- **No colour synchronisation between browsers**, no server storage of the tag.
- **No new capability endpoint, no new stream, no polling.**
- **No change to how `FeatureState` is folded.** The switcher renders the shell's existing
  derivation; it does not add a rule.

## Design references

- **ADR-032** — the state vocabulary, reasons rendered verbatim, `NotConfigured` hidden,
  `Unavailable` shown and clickable, and Amendment 2 (a not-yet-polled cluster is
  `Degraded(Starting)`, never `Unavailable` — so a freshly started gateway must not paint every
  cluster red for one polling interval).
- **ADR-039** — the capability fold, including the sticky `since` and the asymmetric debounce the
  dot inherits for free.
- **ADR-012** — why the shell must not name feature code, and what `checkBundleShape` asserts.
- **ADR-024** — semantic tokens, no colour in Scala, class names as constants.
- **ADR-031** — cluster ids are slugs of the configured name, so an id is URL-safe and stable, and
  the colour key can be built from it.
- **`research/kafbat/ui-analysis.md`** "Layout: PageContainer, NavBar, Sidebar" — `ClusterMenu`, its
  persisted open state and colour key, the status dot with a `<title>`, and the colour picker; IA.2
  for how the registry drives navigation.
- **`research/design/REFERENCE.md`** — the 272-pixel drawer holding the wordmark and the primary
  destinations, the active destination marked by the secondary container colour, and status as a
  filled chip.
- `frontend/ui-shell/src/kui/ui/shell/layout/Sidebar.scala` and
  `frontend/ui-kernel/src/kui/ui/kernel/state/CurrentCluster.scala` — the structures this extends.
  Describe intent, not line numbers: the restyle will have moved them.

## Files to create

```
frontend/ui-shell/src/kui/ui/shell/nav/ClusterSwitcher.scala
frontend/ui-shell/src/kui/ui/shell/nav/ClusterEntry.scala
frontend/ui-kernel/src/kui/ui/kernel/state/ClusterColors.scala
frontend/ui-shell/test/src/kui/ui/shell/nav/ClusterSwitcherSuite.scala
frontend/ui-kernel/test/src/kui/ui/kernel/state/ClusterColorsSuite.scala
```

`ClusterColors` is in the **kernel** rather than the shell because the tag is drawn in two places —
the switcher and, from M2, page headings — and because it is a stored preference, which is where the
kernel already keeps `Theme`, `Accent` and `Density`. That makes it one of the "named additions in
`frontend/ui-kernel`" this lane is allowed (DEVPLAN §6.5, extended by this task from CLUI-001's
list; recorded here so the boundary is explicit rather than assumed).

## Files to change

```
frontend/ui-shell/src/kui/ui/shell/layout/Sidebar.scala        (mount the switcher above the destinations)
frontend/ui-shell/src/kui/ui/shell/ShellRouter.scala           (a cluster in the URL sets CurrentCluster)
frontend/ui-shell/src/kui/ui/shell/Messages.scala
frontend/ui-shell/resources/css/31-shell-nav.css               (switcher, dot, tag)
frontend/ui-kernel/src/kui/ui/kernel/state/CurrentCluster.scala (persist the last choice)
frontend/ui-kernel/src/kui/ui/kernel/css/KernelCss.scala        (tag class names)
docs/frontend/README.md
```

## Public Scala signatures to implement

```scala
package kui.ui.kernel.state

/** A per-cluster colour marker, chosen by the user and stored in this browser. */
enum ClusterColor(val storageValue: String, val label: String) {
  case None extends ClusterColor("none", "No colour")
  case Info extends ClusterColor("info", "Blue")
  case Success extends ClusterColor("success", "Green")
  case Warning extends ClusterColor("warning", "Amber")
  case Danger extends ClusterColor("danger", "Red")
  case Tertiary extends ClusterColor("tertiary", "Teal")
}

object ClusterColor {
  /** Anything unrecognised reads as `None`: a corrupted stored value must not blank the sidebar. */
  def fromStorage(raw: String): ClusterColor
  given CanEqual[ClusterColor, ClusterColor] = CanEqual.derived
}

object ClusterColors {
  val StorageKeyPrefix: String = "kui.cluster.color."

  /** The chosen colour for one cluster. Writable; writing persists. One `Var` per cluster id,
    * memoised, so two components watching the same cluster see one value.
    */
  def of(clusterId: String): Var[ClusterColor]

  /** The class name the stylesheet keys off, for example `kui-cluster-tag--danger`. */
  def className(colour: ClusterColor): String
}
```

```scala
package kui.ui.shell.nav

/** One cluster as the switcher shows it: identity from the registry, health from the same fold the
  * sidebar uses, colour from this browser.
  */
final case class ClusterEntry(
    clusterId: String,
    displayName: String,
    state: FeatureState
)

object ClusterEntry {
  /** Every cluster the capability registry knows, sorted by display name, with `NotConfigured`
    * clusters dropped — the same rule the sidebar applies to a feature entry.
    */
  def of(snapshot: CapabilitySnapshot): List[ClusterEntry]
}

object ClusterSwitcher {
  /** @param entries  from `CapabilityStore`, live.
    * @param current  the kernel's `CurrentCluster`.
    * @param open     what a choice does: set the current cluster and navigate to its brokers page.
    */
  def apply(
      entries: Signal[List[ClusterEntry]],
      current: Var[Option[ClusterId]],
      open: ClusterId => Unit,
      testId: Option[String] = None
  ): HtmlElement
}
```

`CurrentCluster` gains persistence: its `Var` is backed by `localStorage` under `kui.cluster.current`
through the same helper the theme uses, so a reload returns to the cluster the operator was on. The
URL still wins on load — `ShellRouter` sets it from the route before anything reads it.

## Library coordinates

None new. `com.raquo::laminar::17.2.1`, `com.raquo::airstream::17.2.1`,
`com.raquo::waypoint::9.0.0` and `org.scala-js::scalajs-dom::2.8.1` are already on both modules
(`DEPENDENCY_MATRIX.md`, `frontend/*`); `com.raquo::domtestutils::19.0.0` is already on both test
modules.

## Acceptance criteria

```
$ ./mill frontend.uiShell.compile
$ ./mill frontend.uiKernel.test
$ ./mill frontend.uiShell.test
$ ./mill frontend.uiShell.checkBundleShape
$ ./mill frontend.uiShell.checkFormat && ./mill frontend.uiShell.fix --check
```

All clean. `checkBundleShape` is the load-bearing one: it must still report
`kui.ui.clusters.ClustersFeature` in a JavaScript module of its own, proving the switcher did not
introduce a static reference from the shell into the feature.

```
$ grep -rn "kui.ui.clusters" frontend/ui-shell/src | grep -v ClustersRoutes | grep -v dynamicImport
```

Expected: no matches.

Manual, once, against CFGOP-006's Compose stack with three clusters, one of them pointed at a closed
port:

- The drawer shows the switcher under the wordmark with the current cluster's name.
- Opening it lists three clusters; the dead one carries an amber or red dot whose tooltip names the
  reason, and choosing it still navigates.
- Giving one cluster a colour and reloading keeps the colour.
- Pasting `/ui/clusters/<other>/brokers` into a fresh tab shows that cluster as current, overriding
  what was stored.

## Tests required

`ClusterColorsSuite` (jsdom, kernel):

- `aChosenColourPersistsAndIsReadBack`.
- `anUnknownStoredValueReadsAsNone`.
- `twoCallersForOneClusterShareOneVar` — otherwise the tag and the picker disagree.
- `storageBeingUnavailableStillYieldsAWorkingVar` — the private-window case the theme preference
  already handles; the switcher must not throw when `localStorage` refuses.
- `keysAreNamespacedPerCluster` — colouring `prod` does not colour `prod-eu`.

`ClusterSwitcherSuite` (jsdom, shell):

- `everyConfiguredClusterIsListedAndNotConfiguredOnesAreNot`.
- `theCurrentClusterIsMarkedSelected`.
- `choosingAClusterSetsCurrentClusterAndNavigatesOnce`.
- `anUnavailableClusterIsStillChoosable` — the switcher's version of the dashboard's criterion: a
  dead cluster must remain reachable, because its own page is where the explanation is.
- `theDotsTooltipIsTheStateWordAndTheReasonVerbatim`.
- `aClusterWithNoDisplayNameFallsBackToItsId`.
- `keyboardOperationMatchesTheListboxContract` — arrows move, Enter chooses, Escape closes and
  restores focus to the trigger.
- `aNotYetPolledClusterShowsDegradedNotUnavailable` — ADR-032 Amendment 2, asserted where an
  operator would otherwise see a wall of red after every gateway restart.
- `theColourTagAndTheStatusDotAreDistinctElements` — one carries the colour class, the other the
  state class; a single element doing both would make a red "production" tag indistinguishable from
  a failing cluster.

`ShellRouterSuite` (existing suite, new case):

- `aClusterInTheUrlOverridesTheStoredSelection`.

## Observability

- The switcher sends no request of its own; it reads `CapabilityStore`. Nothing is reported to
  `HealthReporting` from here.
- `data-testid` hooks: `cluster-switcher`, `cluster-switcher-trigger`,
  `cluster-switcher-option-<clusterId>`, `cluster-switcher-dot-<clusterId>`,
  `cluster-tag-<clusterId>`. CFGOP-007 asserts the shell stays usable with the cluster service
  stopped through the first two.
- The status dot has a text alternative (a `<title>` inside it, as Kafbat's does), so the state is
  available to a screen reader and not only to a colour.

## Degraded behavior

- **The cluster service is stopped.** The switcher still renders every cluster the registry knows,
  each with an `Unavailable` dot, and choosing one still navigates — to a page that shows the
  feature fallback. This is the shell half of CFGOP-007's headline scenario and it is the reason the
  switcher reads the registry rather than the cluster service: a switcher that needed the cluster
  service would vanish exactly when the user needs it to explain what is missing.
- **The capability stream is disconnected.** The switcher shows the last snapshot, unchanged. The
  shell's existing connection indicator already reports the disconnection; the switcher does not
  duplicate it and does not clear itself.
- **The registry knows no clusters at all** — the switcher renders a single non-interactive row
  reading "No clusters configured" with a link to the dashboard, rather than an empty menu.
- **`localStorage` unavailable** — colours and the remembered cluster silently become
  session-scoped. Nothing fails, nothing is reported to the user: a private window is not an error
  state.

## Docs to update

- `docs/frontend/README.md` — the switcher's data source and the rule it establishes: shell chrome
  reads the capability registry, never a feature's service. It is the pattern M2's cluster-scoped
  navigation will follow.
- `docs/frontend/tokens.md` — the colour-tag chip's foreground/background pairs, added to the
  contrast table so `ContrastSuite` checks them. Six choices means six rows.

## Deviations

Commit `929b189`.

1. **CLAPI-008 has not landed, so there are no per-cluster registry entries yet and no display
   name.** The switcher is written against `CapabilityKey.cluster`, which already exists, and renders
   the slug where a name would go — the degradation the spec asks for. It is live and correct the
   moment the gateway starts publishing per-cluster entries. **Owed by CLAPI-008:** a display name on
   the entry. `prod-eu-1` is exactly the string this feature exists to stop people misreading, so the
   fallback is a stopgap rather than an answer.

2. **The palette is `None, Primary, Success, Warning, Danger, Accent`, not the spec's
   `None, Info, Success, Warning, Danger, Tertiary`.** `--kui-color-info` has no container token and
   there is no `tertiary`; the six chosen are exactly the container/paired-text pairs the token set
   defines and the contrast table already checks. The user-facing labels stay colours ("Blue",
   "Teal") rather than semantic names, because offering somebody "Warning" as a marker would suggest
   the cluster is in a warning state — which is what the dot beside the tag means.

3. **`docs/frontend/tokens.md` gains a note, not six rows.** The tag is a small filled square with no
   text on it, so it introduces no new foreground/background pair; every fill it uses is already a
   checked row. The note records why, so the absence does not read as an omission.

4. **Choosing a colour is a native `<select>` on the row**, not a bespoke swatch menu. It is
   keyboard- and screen-reader-correct for free, and a second custom popup inside an open listbox is
   a focus-management problem with no user-visible gain.

5. **`ClusterEntry.of` takes the registry's `Map[CapabilityKey, CapabilityState]`**, which is what
   `CapabilityStore` exposes live, rather than a `CapabilitySnapshot`. It also folds a cluster's
   several service entries to the **worst** of them, which the spec does not state: a dot reporting
   the best of a cluster's services would be reassuring and wrong.

6. **`ShellRouter.clusterInUrl` parses the path rather than decoding a page**, because the shell may
   not name a feature's page types. The switcher navigates by asking the feature's own
   `history.state` codec to build a brokers page from a tag — the same mechanism the Back button
   already uses before a feature has been downloaded — so no feature class is named statically;
   `checkBundleShape` still reports the feature in a module of its own and
   `grep -rn "kui.ui.clusters" frontend/ui-shell/src` (excluding `ClustersRoutes` and the dynamic
   import) is empty.

7. **`CurrentCluster.selected` became `lazy`** now that it touches `localStorage`, so importing the
   object does not.

## Implementation report

```
./mill frontend.uiShell.compile           SUCCESS
./mill frontend.uiKernel.test             SUCCESS (ClusterColorsSuite 7)
./mill frontend.uiShell.test              SUCCESS (ClusterSwitcherSuite 13, ShellRouterSuite +3)
./mill frontend.uiShell.checkBundleShape  1 feature module split out, main.js 966079 B of 1500000 B
./mill frontend.uiShell.checkFormat       SUCCESS
./mill frontend.uiShell.fix --check       SUCCESS
./mill frontend.uiKernel.fix --check      SUCCESS
./mill checkArchitecture                  75 modules, no layering violations
grep -rn "kui.ui.clusters" frontend/ui-shell/src | grep -v ClustersRoutes | grep -v dynamicImport
                                          no matches
```
