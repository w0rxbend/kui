# Wave 3 — The screens that can now be honest

**Milestones covered:** M6 in [ROADMAP.md](ROADMAP.md), plus the halves of M2 and M4 that wave 2
could not close from inside a packet, plus every rule wave 2 shipped that no test can fail.

**Why these fourteen and not others.** Wave 1 built primitives nobody consumed. Wave 2 was the
wiring wave and consumed most of them — the frame is real and was driven in a browser against a
live stack — but it built a topic-tree fold nobody calls and left eight rules that a mutation can
break with the suite still green. Wave 3 has to be the wave that stops that pattern rather than the
wave that repeats it one level up, so **every packet here carries the ungated rule that lives in a
file it owns**, and no packet is allowed to defer one to "a later pass". That deferral is exactly
how the counts in `docs/FEATURE_MATRIX.md` drifted, how `--kui-color-series-6` acquired a second
owner, and how `topicTree` came to be exported and called by nothing.

The other reason for the shape of this wave is that wave 2's collisions were all in files **no
packet owned**: shared golden documents, the gateway's copies of them, two hard-coded endpoint
rosters in gateway test suites, and a hand-written `interface SubjectPage` mirroring a wire shape
somebody else widened. A disjoint partition does not prevent a packet from changing a *shape* that
an unowned file asserts. So this wave has two sections it did not have before: **the guard files**,
listed up front, and **the partition, checked**, which names the unowned files and says why each
needs no edit.

**Parallelism.** `Owns` is disjoint across every packet: no two tasks may edit the same file, and no
packet owns a directory containing another packet's file. Where two packets meet, the brief states
the exact contract both sides code against. Three dependencies are declared: W3-13 depends on W3-08,
W3-06 depends on W3-08's request and answer shape (stated below, not on its diff), and W3-09 and
W3-12 each carry one sentence supplied by another packet.

**A consequence of the split you must not "fix".** W3-08 adds two endpoints to the gateway, so
`./mill __.openApiCheck` will be **red on `services.gateway.api`** and green everywhere else until
W3-13 regenerates the three merged documents. That is the designed intermediate state, exactly as it
was last wave. Do not repair it by editing a file you do not own. Likewise `pnpm -C frontend
typecheck` will go red in `packages/shell` the moment W3-13 lands if anybody hand-declares a wire
shape; the fix is to read the generated type, not to widen a local interface.

**House rules that apply to every packet** — read them before starting.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first — a story per state — and browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what, at roughly the 25% density of
the surrounding code. There is no ESLint or Prettier; the codebase is hand-written at 100 columns
(Scala at 110, per `.scalafmt.conf`). **Do not reformat a file you are not otherwise changing.**

Four rules are new this wave, each because something went wrong without them.

1. **No new stylesheet files.** `build-tests`'s `CssReferencesSuite` requires every stylesheet on
   disk to be named exactly once in `frontend/packages/kernel/styles/index.css`, and that file has
   one owner. Extend a stylesheet your package already has. Every package that needs one has one.
2. **No new custom properties in `frontend/packages/kernel/styles/10-tokens.css`.** A Scala mirror of
   that file lives in `build-tests/src/kui/build/design/Tokens.scala`, which no packet owns. This is
   the collision wave 1 shipped; it stays closed by not reopening it.
3. **No new `ErrorCode`.** `tools/error-codes` generates `frontend/packages/api/src/constants.generated.ts`
   and `./mill frontend.apiConstants --check` compares it byte for byte. Neither file is owned this
   wave. Reuse the codes that exist.
4. **A gate you cannot make fail is not a gate.** Every packet's acceptance list has a
   **mutation line**: name one change to the shipped code that reverses the packet's headline rule,
   apply it, run the acceptance suite, and record which case went red. If none does, the packet is
   not finished, whatever the exit code says. Eight of wave 2's rules failed exactly this test after
   they shipped.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`,
with node at `~/.nvm/versions/node/v26.8.1/bin`.

**Running a browser suite.** `pnpm -C frontend e2e` drives a stack it does not start.
`deployment/quickstart/quickstart.sh` starts one — and it will happily reuse a container image built
from a tree that no longer exists. A wave-2 repair read red for an hour against a BuildKit-cached
frontend bundle from before the fix. So: `./mill deployment.docker.allinone.docker.build` and
`docker build --no-cache -f deployment/frontend/Dockerfile -t kui-frontend:0.1.0-SNAPSHOT .` **before**
`quickstart.sh`, every time the tree has changed.

---

## The guard files

Everything below asserts a shape, a count or a roster that this wave's work can invalidate. None of
them is owned by the packet most likely to break it — that is the point of listing them. If your
change makes one of these red, it is your change that is unfinished, and the repair goes in the
packet that owns the guard, named through `needsOutsideOwnership` if that is not you.

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `libs/contracts-core/test/resources/golden/*.json` **and** their Scala twins in `.../ClusterGoldenDocuments.scala`, `TopicGoldenDocuments.scala` | Every field of `ClusterSummaryDto`, `ClusterRowDto`, `TopicRowDto`, `TopicDetailDto`, byte for byte, in *two* places per document | any DTO widening (W3-08, W3-09) |
| `services/gateway/contract/test/resources/golden/{cluster,topic}-overview.json` + `.../GoldenDocuments.scala` | The same fields one aggregation layer out. Wave 2 forgot these four times | the same |
| `services/{cluster,consumer,topic,schema,metrics}/contract/test/resources/golden/*.json` | Each service's own wire, including `clusters-response.json`'s unreachable `99.98` | W3-09 |
| `services/gateway/api/test/.../TopicProxySuite.scala:133-148` | A **hard-coded list of the seven public topic addresses**, in `TopicEndpoints.all` declaration order | anyone adding a topic endpoint |
| `services/gateway/api/test/.../openapi/OpenApiMergeSuite.scala:61-77` | A **hard-coded sorted list** of the merged document's topic paths | the same |
| `services/gateway/api/test/.../openapi/MergedDocumentShapeSuite.scala` | `writes.size == 3` over cluster writes; distinct operationIds across the merged document | W3-08 |
| `services/gateway/api/test/.../routing/ServiceContractsSuite.scala` | A hard-coded `Set` of service ids | W3-08 if it adds a service (it must not) |
| `frontend/packages/api/src/constants.generated.ts` + `./mill frontend.apiConstants --check` | 31 error codes, byte for byte | house rule 3 forbids it |
| `docs/api/openapi.json`, `docs/api/openapi.browser.json` — `./mill __.openApiCheck` | A **byte** comparison against a fresh Tapir render. A single renamed key fails it | every backend packet; repaired only by W3-13 |
| `frontend/packages/api/src/schema.d.ts` + `ci.yml`'s `generate` + `git diff --exit-code` | The browser's types, regenerated from the browser document | W3-13 |
| `build-tests/src/kui/build/design/Tokens.scala`, `TokensSuite`, `ContrastSuite` | A Scala mirror of `10-tokens.css` and its contrast ratios | house rule 2 forbids it |
| `build-tests/.../CssReferencesSuite` | Every stylesheet on disk named once in `kernel/styles/index.css` | house rule 1 forbids it |
| `frontend/packages/*/src/recorded/*.json` + each package's `recorded.test.ts` | A **recorded** answer replayed against the mapping. Wave 2's `subjects.json` passed for a day against a wire the service no longer sent | every feature packet whose endpoint changed |
| `frontend/e2e/*.spec.ts` | Screen text, by role and by visible string. `features.spec.ts:13` is the only thing that caught the schemas screen rendering `[object Object]` | every feature packet |
| `docs/FEATURE_MATRIX.md` rows vs. its own prose, and `README.md:95` | 188 rows; 64 COMPLETE / 20 REVIEW; "64 of 177 — 36%" in two files. **Nothing compares them** | every packet that finishes a capability |
| `deployment/compose/smoke.sh` | The routed-service set, derived from `/api/v1/capabilities` | W3-12 |
| `scripts/run-tests.sh` | 63 modules, 55 with tests, and the eight with no test sources it names out loud | any new module |
| `docs/api/error-codes.md` via `libs/kernel`'s `ErrorCodeSuite` | The published code list | house rule 3 forbids it |

---

## W3-01 — Topics: the list, and the object screen whose Overview tab draws nothing

**Owns**
```
frontend/packages/feature-topics/**
frontend/e2e/topics.spec.ts
```

**Contract.** M5 filled the wire this screen has been waiting for. `TopicRowDto` now carries
`cleanupPolicy` and `produceRate`; `GET …/topics/statistics` answers a `Section`-wrapped
`{topicCount, partitionCount, sizeBytes, incompleteTopics}` for the whole cluster and **not** for the
filtered page — against the quickstart it reads `topicCount 10, partitionCount 86, sizeBytes 21913`;
`GET …/topics/names` answers a names-only index. Each total refuses independently: a topic the scrape
could not describe removes both sums and leaves the count. Render the refusal, do not render a zero.

What is missing on the screen, verified by reading it rather than by trusting the roadmap:
`TopicListPageProps` has no statistics, no facets, no selection, no bulk bar and no export;
`TopicsRoute.tsx:511` declares a tab `id: "overview"` and there is no `<Show when={tab() === "overview"}>`
anywhere in the file, so the tab strip offers a section with no body; and `TopicConsumers.tsx` carries
the visible `Activity` column heading that wave 2 shipped in place of the visually-hidden string the
design asked for, because `Column.header` is typed `string` and rendered as a bare text node.

`ClustersRoute`, `ConsumersRoute`, `SchemasRoute` and `TopicsRoute` each carry a hand-rolled
`useFetch`. `useQuery` exists, is tested, and has **no consumer outside the kernel**. This packet
migrates its own.

**Do**
1. The statistics region behind its switch, reading `/topics/statistics`. A refused total shows the
   `NotMeasured` sentence, never `0`, and never the page's own row count dressed as a cluster total.
2. The facet chip bar and the cleanup column. `cleanupPolicy` is `Option` on the wire: a row whose
   batch did not cover it shows nothing, not Kafka's `delete` default.
3. Card composition, sort and direction controls, row and card selection sharing **one** selection
   set, the bulk bar, and Export.
4. The topic object's Overview tab body. It is the tab the strip opens by default and it renders
   nothing today.
5. The coordinator column on the Consumers tab — `coordinatorHost:coordinatorPort`, which the wire
   now carries — an empty state for that tab, the in-content breadcrumb, and `Produce message` in
   the header.
6. A toast on every destructive success in this package.
7. Migrate this package's `useFetch` to `useQuery`.
8. **The ungated rule this packet owns.** Decide the `dormant` column's heading: either restore the
   visually-hidden string with the class this package's own stylesheet can now carry, or keep
   `Activity` and delete the comment that says it is a deviation. Whichever, add a case to
   `topics.test.tsx` that fails if the heading becomes empty again — the a11y sweep is a whole-tree
   gate and a unit case is what tells you *which* column.

**Acceptance**
```
pnpm -C frontend test packages/feature-topics
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'screens-topic|topics-'
grep -rl useFetch frontend/packages/feature-topics/src    # empty
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: the statistics region shows the cluster total and not the page's;
a refused total renders the sentence and not `0`; a row whose `cleanupPolicy` is absent renders
nothing in that column; row selection and card selection share one set; the Overview tab renders a
body; the consumers tab prints `host:port`.
**Mutation line:** make `/topics/statistics`'s `topicCount` be read from the current page's row count
instead of the document. Name the case that goes red.

---

## W3-02 — Consumer groups: the server's total, paging, and the coordinator's real address

**Owns**
```
frontend/packages/feature-consumers/**
frontend/e2e/consumers.spec.ts                              (new)
```

**Contract.** `GroupSummaryDto` now carries `coordinatorId`, `coordinatorHost` and `coordinatorPort`,
and the three are on the wire together or not at all — the contract has a property asserting it.
Against the quickstart the first row reads `coordinatorHost "kafka", coordinatorPort 9092`, so the
screen can print `kafka:9092` where it prints `broker 1` today. `GroupsResponse` carries a page
alongside `items`; `GroupListProps` has `rows` and no `page`, `totalItems` or `onPage` at all, which
is why the screen counts its own rows.

**Watch this one.** `feature-consumers/src/recorded.test.ts` replays a **recorded** answer. It is
green today because the recording predates nothing; the moment this packet changes what the mapping
reads, re-cut the recording from a live quickstart rather than editing it by hand. A recorded fixture
edited to match the code asserts the code against itself.

**Do**
1. Paging: the server's `totalItems`, a `Pagination` control, and a page size that is the API's, not
   the array's length.
2. The coordinator column printing `host:port`, with the address absent — not "broker 1" — when the
   wire did not carry it. `coordinatorsMissing` already exists and already means this.
3. A toast on every destructive success in this package (offset reset, group delete).
4. Migrate `ConsumersRoute.tsx`'s `useFetch` to `useQuery`.

**Acceptance**
```
pnpm -C frontend test packages/feature-consumers
pnpm -C frontend typecheck
pnpm -C frontend build-storybook   # then serve and sweep 'screens-consumer|consumers-'
pnpm -C frontend e2e
```
Required cases: the count beside the heading is the server's total and differs from the rows on
screen when there is more than one page; a group with no coordinator on the wire renders no address
and no invented broker id; page 2 asks the server for page 2.
**Mutation line:** replace `totalItems` with `rows.length`. Name the case that goes red.

---

## W3-03 — Schema registry: two panes, and the row facts the wire now carries

**Owns**
```
frontend/packages/feature-schemas/**
frontend/e2e/features.spec.ts
```

**Contract.** This is the packet with the freshest scar. W2-09 widened
`GET …/schemas/subjects` from `items: string[]` to a summary row per subject —
`{subject, format, versionCount, compatibility{level, inheritedFromGlobal}}`, confirmed live — and
the screen rendered `[object Object]` in a link href for a day. `tsc` could not see it, because
`data.ts:101` casts the answer (`answer.value as unknown as SubjectsPayload`); the package's own
tests could not see it, because the recorded fixture still held bare strings; and the only thing that
caught it was `frontend/e2e/features.spec.ts:13`, which this packet now owns. Integration repaired
the mapping and re-cut the recording. `SubjectListResult.subjects` is still `readonly string[]` and
the four extra fields are mapped away one line after they arrive.

`features.spec.ts` also holds the message-tracking tests. Leave them where they are; W3-04 writes its
new assertions in a file of its own rather than moving them.

**Do**
1. The two-pane master–detail: the subject list beside the selected subject, one address per
   selection so a pasted link opens the pane it names.
2. Subject row facts — format, version count, compatibility level, and whether the level is the
   subject's own or inherited from the global one. `inheritedFromGlobal` is on the wire and is the
   distinction the screen exists to make; a subject showing the global level as if it were its own is
   the defect.
3. `Register schema`.
4. Delete the `as unknown as SubjectsPayload` cast. Read the generated type from `@kui/api`. That
   cast is the reason a wire change was invisible, and this packet is the one that paid for it.
5. A toast on every destructive success.
6. Migrate `SchemasRoute.tsx`'s `useFetch` to `useQuery`.

**Acceptance**
```
pnpm -C frontend test packages/feature-schemas
pnpm -C frontend typecheck
grep -c 'as unknown as' frontend/packages/feature-schemas/src/data.ts      # 0
pnpm -C frontend build-storybook   # then serve and sweep 'schemas-|screens-schema'
pnpm -C frontend e2e
```
Required cases: a subject whose level is inherited says so and a subject with its own does not; the
list renders the subject's name and not its object; selecting a subject changes the address.
**Mutation line:** map `row` instead of `row.subject` into the list. `features.spec.ts` must go red,
and so must a unit case — if only the browser catches it, the unit case is missing.

---

## W3-04 — The message browser: the topic's real partition count, and predicates

**Owns**
```
frontend/packages/feature-messages/**
frontend/e2e/messages.spec.ts                               (new)
```

**Contract.** `MessagesRoute.tsx:124` reads `const [partitionCount] = createSignal(0);` — a
hard-coded zero, passed to three children including `ResendDialog`, whose sentence
"`${topic} has ${partitionCount} partitions`" is therefore a false statement on every screen it
appears on. The real figure is on the wire twice over: `TopicDetailDto` carries it, and
`/topics/statistics` carries the cluster's.

The message endpoints that exist are `POST …/topics/{t}/messages` (browse),
`…/messages/stream` (ADR-035), `…/messages/filters`, `…/messages/filters/test` and
`…/messages/track`. Nothing new is needed from the backend for this packet.

**Do**
1. The topic's real partition count, fetched once with the topic and threaded to the three children
   that take it. A dialog that cannot know the count must not claim one.
2. The time window and the offset range.
3. Typed key and value predicates, and filter presets over the endpoints that already exist.
4. A toast on every destructive success in this package (purge, resend, produce).

**Acceptance**
```
pnpm -C frontend test packages/feature-messages
pnpm -C frontend typecheck
grep -n 'createSignal(0)' frontend/packages/feature-messages/src/MessagesRoute.tsx   # gone
pnpm -C frontend build-storybook   # then serve and sweep 'messages-|screens-message'
pnpm -C frontend e2e
```
Required cases: the resend dialog quotes the topic's real partition count; a topic whose count is
not known yet renders no sentence claiming one; a predicate on the key and a predicate on the value
reach the request separately.
**Mutation line:** re-hard-code the count to `0`. Name the case that goes red.

---

## W3-05 — Brokers: four stat tiles, a real disk percentage, and a voice line that is true

**Owns**
```
frontend/packages/feature-clusters/**
frontend/e2e/brokers.spec.ts                                (new)
```

**Contract.** M5 filled this screen's numbers and they were checked against a real broker:
`.brokers.data[0]` answers `partitionCount 86, leaderCount 86, replicaCount 86, replicaSkewPercent 0.0,
diskUsageBytes 95320`, and `leaderSkewPercent` and `segmentCount` are still `null` by design —
`leaderSkewPercent` is now trivially derivable from `leaderCount` by the arithmetic already used for
replicas, which is a backend change and belongs to W3-09, not here. Render `null` as the sentence.

The voice line is the specific thing to get right. It currently claims zero under-replicated
partitions rather than reading one; `underReplicatedPartitionCount` is now a number on the wire and
is `0` on a healthy cluster — so the line must distinguish "measured, and it is zero" from "not
measured", which is precisely the distinction that makes the sentence worth printing.

**Do**
1. The four stat tiles, per-broker disk percentage, and the version and uptime tags.
2. Lazily-fetched configs on expand — one request per expansion, not one per row on render.
3. The voice line reading the real under-replication count, with a different sentence when the count
   is absent.
4. A toast on every destructive success.
5. Migrate `ClustersRoute.tsx`'s `useFetch` to `useQuery`.

**Acceptance**
```
pnpm -C frontend test packages/feature-clusters
pnpm -C frontend typecheck
pnpm -C frontend build-storybook   # then serve and sweep 'clusters-|screens-broker'
pnpm -C frontend e2e
```
Required cases: a broker whose disk was not measured shows the sentence and no percentage; expanding
one broker issues one config request and expanding none issues none; the voice line over an
unmeasured cluster does not claim zero.
**Mutation line:** make the voice line read a literal `0`. Name the case that goes red.

---

## W3-06 — The frame: the tree that has no caller, and three wirings nothing can see

**Owns**
```
frontend/packages/shell/src/App.tsx
frontend/packages/shell/src/app.render.test.tsx
frontend/packages/shell/src/shell.test.tsx
frontend/packages/shell/src/chrome/**
frontend/packages/shell/src/nav/**
frontend/packages/shell/src/data/**
frontend/packages/shell/src/routing/**
frontend/packages/shell/src/index.ts
frontend/packages/shell/styles/**
frontend/e2e/shell.spec.ts
frontend/e2e/dashboard.spec.ts                              (new)
frontend/e2e/search.spec.ts                                 (new)
```

**Contract — read this twice.** The frame works. Driven at
`/ui/clusters/quickstart/dashboard/overview` it draws the cluster block (`healthy · 4.3 · 1 broker`),
`Add a cluster` → `/ui/clusters/manage`, badges carrying real counts, and a storage meter reading
`23% · 47.0 GB of 203.2 GB`. Nothing here is a rebuild. Three things are wrong and all three are the
same kind of wrong.

* `nav/topicTree.ts` is written, tested and exported, and `grep -rn topicTree frontend/packages` finds
  it in exactly one place outside its own file and test: `src/index.ts:95`, the barrel that exports
  it. The drawer does not nest.
* Cutting the store from the drawer's badges — `countFor: countLookup(readingValue(facts.counts))`
  → `countFor: () => undefined` at `App.tsx:595` — leaves **all 281 shell tests green**. Re-verified
  here. The three badge cases compose `countLookup` and `navigationGroups` by hand inside
  `shell.test.tsx` and never observe `App`'s use of either. The same is true of `onCreateTopic` and
  of `onSelect={switchEnvironment}`; `EnvRailProps.onSelect` is optional, so an unwired rail is not
  even a type error.
* Replacing `glyphOf` in `chrome/Notifications.tsx` with `SEVERITY_GLYPH[notice.severity]` — the
  exact pre-wave-2 defect — leaves 281 green, because the only thing asserting the per-category glyph
  is a Storybook story, and a story is not an assertion. Removing `.toUpperCase()` from
  `nav/navigation.ts:139` is green for the same reason: the one casing test feeds a fixture whose
  headings are already uppercase.

Two smaller ones, both verified: `chrome/AppearancePopover.tsx:69` labels the `auto` theme "Auto"
while `pages/SettingsPage.tsx:59` labels the same value "Match the system" — two words for one
preference, which is the failure the popover's own brief named. That table is duplicated across two
packages' files with no shared constant; `SettingsPage.tsx` belongs to W3-07, so publish the constant
from here and let W3-07 consume it. And the frame's own `onCreateTopic` navigates to the topics list
rather than opening the create dialog.

**The search field.** W3-08 ships `GET /api/v1/search?q=…`. Its answer is
`{"results": {"topics": [...], "groups": [...], "subjects": [...]}, "partial": [<serviceId>...]}`,
where `partial` names the services that could not be asked — the distributed stack routes no schema
service today, so `subjects` being absent is a normal answer and not an error. Code against that
shape, not against W3-08's diff.

**Do**
1. Call `topicTree`. The drawer nests: favourites, prefix groups, `internal`. The fold is capped
   already; do not re-fold names in a component and do not re-implement the cap.
2. Gate the three wirings, in `app.render.test.tsx` where the real `App` is mounted, not in
   `shell.test.tsx` where the pieces are composed by hand. The fixture is already there:
   the stub answers `/topics` with `page.totalItems: 128`, so
   `expect(drawer?.textContent).toContain("128")` beside the existing storage assertions closes the
   badge seam in one line.
3. Gate the notice glyph: two notices, same severity, different category, two different marks. Gate
   the heading fold: a fixture whose group headings arrive lower-case.
4. One shared appearance vocabulary, exported from this package, so `auto` has one label.
5. Wire the top-bar search field to W3-08's endpoint, debounced. A service in `partial` is reported
   in the result list as unavailable, not omitted silently.
6. Write `dashboard.spec.ts`: open `/ui/clusters/<id>/dashboard/overview`, assert the drawer head
   names the cluster and shows a broker count, expand the topic tree, click `+`, land on
   `/ui/clusters/manage`. This is **M4's exit criterion**, unwritten since M4 was planned because no
   wave-2 packet owned `frontend/e2e/**`.
7. `clusterStore.ts:154` hands `undefined` to `summaryOf`, whose first statement reads `row.summary`,
   so a 200 whose body is not the cluster envelope throws inside a memo — a render-time crash of the
   frame. Guard it the way the neighbouring reading is guarded.

**Acceptance**
```
pnpm -C frontend test packages/shell/src/chrome packages/shell/src/nav packages/shell/src/data packages/shell/src/routing
pnpm -C frontend test packages/shell
pnpm -C frontend typecheck
node frontend/scripts/boundaries.mjs
pnpm -C frontend build-storybook   # then serve and sweep 'chrome-|shell-'
grep -rn topicTree frontend/packages/shell/src | grep -v 'topicTree.ts\|index.ts'   # non-empty
pnpm -C frontend e2e
```
**Mutation lines — all four, each recorded:** `countFor: () => undefined`; `onCreateTopic` to a
no-op; `glyphOf` to `SEVERITY_GLYPH[notice.severity]`; `.toUpperCase()` removed. Each must redden a
named case. If any is still green, the packet is not done.

---

## W3-07 — The dashboard body, and the subtree that rebuilds itself

**Owns**
```
frontend/packages/shell/src/overview/**
frontend/packages/shell/src/pages/**
```

**Contract.** Both tabs render and both are honest: the Overview tab draws brokers online, topics,
partitions-in-sync, consumer lag and broker health from measured figures, and prints the
`NotMeasured` sentence for throughput, latency and produce rate, which is correct until M7's adapter
exists. The Storage tab attributes bytes by topic prefix, skipping unmeasured directories in the
capacity *and* in the attribution. None of that is being rebuilt.

One regression, found by probing rather than by reading. `Overview.tsx:205` renders
`{bodyFor(tab(), props.model)}` and `bodyFor` at `:228` passes `model` on as a plain captured value,
so the JSX compiler wraps the call in a tracked computation: any change to the model replaces the
**entire** subtree, and the children get a static snapshot. Measured — moving `LOADING → HEALTHY`
keeps the tab-invariant `StatRow`'s DOM node and replaces the body's. At `HEAD` the panels were
inline JSX bindings and updated fine-grained. It voids the file's own argument at `:451-452` that
"a broker that keeps its place keeps its DOM node and its bar does not restart its transition on
every poll", drops focus inside the body every time data lands, and becomes per-poll churn the
moment anyone adds a refetch interval.

**Do**
1. Repair the tab dispatch so the body updates fine-grained. The keying argument at `:451-452` has
   to become true, not just written.
2. `StorageByBroker.tsx:155-156` claims `detailOf` "is the same arithmetic `diskPercentOf` does …
   so the number here and the bar cannot disagree." They disagree on a zero-byte disk: `isMeasured`
   admits `totalBytes: 0`, so `diskPercentOf` returns `unknown("this broker reported a zero-byte
   disk")` and draws no fill with a reason, while `detailOf` prints `0 B of 0 B` with no percentage
   and no explanation. Make one of the two sentences true.
3. Consume W3-06's shared appearance vocabulary in `SettingsPage.tsx` so `auto` has one label.
4. `overview/load.ts:203` dereferences `detail.value.cluster.summary` unguarded.
5. `overview.render.test.tsx` disposes at the end of each `it` body rather than from an `afterEach`,
   so one real failure leaks a mounted container and cascades into spurious
   `landmark-no-duplicate-banner` failures. Move it.

**Acceptance**
```
pnpm -C frontend test packages/shell/src/overview packages/shell/src/pages
pnpm -C frontend typecheck
pnpm -C frontend build-storybook   # then serve and sweep 'screens-|overview-'
```
Required case, by name: mounting the dashboard and moving the model from loading to healthy keeps
the body's DOM node. That case is the whole of item 1 and it does not exist today.
**Mutation line:** re-introduce the captured-value dispatch. The new case must go red.

---

## W3-08 — Cross-entity search at the gateway

**Owns**
```
services/gateway/**
docs/adr/ADR-049-cross-entity-search.md                     (new)
```

**Contract.** `docs/api/openapi.json` has **no path containing `search`**; this is the only piece of
M6 that is an endpoint rather than a screen. It is a fold at the gateway over list endpoints that
already exist — `GET …/topics/names` (built in wave 2 for exactly this), the consumer-groups list,
and the schema subjects list — so **no service module is edited by this packet**, and no new service
is registered. `ServiceContracts.byService` already holds six services and its suite pins that set;
leave it alone.

ADR-039's capability fold is the design here, not an afterthought. The distributed stack routes five
services and `schema` is not one of them — `/api/v1/capabilities` answers
`cluster consumer message metrics topic` — while the all-in-one routes six. So a search that cannot
ask the schema service must say so rather than return fewer results silently. That is what `partial`
is for.

**House rule 3 applies hard here:** reuse `KUI-VALIDATION` for a bad `q`; do not add an error code.

**The shape both sides code against**, stated here because W3-06 codes against it and must not read
this packet's diff:
```
GET /api/v1/search?q=<string, 1..200>&limit=<1..50, default 10>
200 {"results": {"topics":[{"cluster","name"}],
                 "groups":[{"cluster","groupId"}],
                 "subjects":[{"cluster","subject"}]},
     "partial": ["schema"]}
400 KUI-VALIDATION with details[0].field == "q"
```

**Do**
1. The endpoint, in `services/gateway/api`, folding the three lists. One request per service per
   cluster, bounded — a search must not become a request per topic.
2. A service that cannot be reached contributes its id to `partial` and nothing else. It is not a
   failure of the search.
3. Add the two hard-coded gateway rosters this touches to your own diff if they move —
   `MergedDocumentShapeSuite`'s distinct-operationId assertion is derived and will hold; check it
   rather than assume it.
4. ADR-049 recording the decision: why the fold is at the gateway and not a seventh service, and why
   `partial` is a list of service ids rather than a boolean.
5. **Supply one sentence to W3-09** for `ARCHITECTURE.md`'s §9 table describing the search fold.
   State it in your closing note; do not edit `ARCHITECTURE.md`.

**Acceptance**
```
./mill services.gateway.__.test
./mill services.gateway.api.openApiCheck        # this module's own document
./mill checkArchitecture
./mill services.gateway.__.checkFormat
```
`./mill __.openApiCheck` is **expected red on the gateway** until W3-13 runs.
Required cases: a query matching a topic, a group and a subject returns all three; a service that
answers an error appears in `partial` and the other two still return; a 201-character `q` is a 400
naming the field; the fold issues one request per service and not one per result.
**Mutation line:** make an unreachable service contribute an empty list without appearing in
`partial`. Name the case that goes red.

---

## W3-09 — The two adapters nobody has ever tested, and one number that cannot exist

**Owns**
```
services/cluster/**
services/topic/**
services/consumer/**
libs/contracts-core/**
libs/cache/**
ARCHITECTURE.md
docs/adr/ADR-016-caching-strategy.md
docs/domain/cluster.md
docs/domain/topic.md
```

**Contract.** M5's numbers are correct on a real broker — I checked — and the code that produces them
is untested. `KafkaPartitionSweeper.sweep` has **no test at all**: `KafkaPartitionSweeperSuite`
exercises only the pure `placementOf` and `censusOf`; the three testcontainer live suites merely
*construct* the sweeper to satisfy `ClusterAdminAdapter.create` and never call `sweepPartitions`; and
the line `unreadable = batch.skipped.keySet` — the one that makes the whole refuse-rather-than-sum
design fire against a cluster where KUI lacks DESCRIBE on a namespace — is asserted nowhere.
`KafkaTopicAdmin.cleanupPolicies` is in the same position: the grouping, the per-key failure
isolation and the whole-batch fallback are argued at length in a twelve-line scaladoc and no test
enters any of it, and W2-08's "a `describeConfigs` failure costs the column, not the page" case is
met by a hand-built snapshot with `policy = None` rather than by a call that fails.
`services/cluster/infrastructure/test/.../RecordingAdminPool.scala` is the counting-fake pattern both
need and it already exists.

`99.98 %` is written as the worked controller-uptime example in `ControllerUptime`'s scaladoc, in
`ControllerUptimeDto`'s, in `ControllerUptimeSuite.aSingleMissedControllerRoundsToTwoDecimals` (whose
comment says "5,999 of 6,000 samples", sixteen times what the window retains) and in
`services/cluster/contract/test/resources/golden/clusters-response.json:32`. The shipped window is 6h
at a 1min step — 360 buckets — so the only values `percentage` can emit are k/360: `100.00` and
`99.72` bracket it. Two decimals of precision is itself a claim 360 buckets cannot back. Choose:
change the window, change the precision, or change the example. Say which in the scaladoc.

Four comments in files you own assert things the code does not do, and each was found by a reader
who checked: `libs/cache/.../SeriesWindowCell.scala:96-99` condemns `updated.accepts(at)` as
reporting "every refusal as a success", but `SeriesWindow.record` is `if !accepts(at) then this`, so
the two expressions are equivalent in every case; `ADR-016:56` says "Each primitive sits behind a
small trait" and `SeriesWindow[A]` is a `final case class` behind none; `ARCHITECTURE.md:685` says
"one bucket per scrape", which is false whenever the scrape interval is shorter than `step` — at the
30s cadence stated one row above with a 1min step, always; and `TopicSnapshot.scala`'s `@param
incomplete` at :21 and the new `names` scaladoc at :47 assert opposite things about whether the two
collections are disjoint, with `topicCount = names.size` resting on whichever is true.

**Do**
1. A test for `KafkaPartitionSweeper.sweep` over `RecordingAdminPool`: a sweep that describes
   everything fills the census; a sweep with one topic in `batch.skipped` withholds **all five**
   figures together; the call count is bounded by the batch size and not by the topic count.
2. A test for `KafkaTopicAdmin.cleanupPolicies`: a `describeConfigs` that fails for one key leaves
   that row's policy `None` and the others filled; a whole-batch failure leaves them all `None` and
   the page still returns; ten thousand topics issue fifty calls and not ten thousand.
3. Settle `99.98`, in all four places, including the golden.
4. Correct the four comments above. A comment claiming an approach the code does not take is worse
   than no comment, which is this repository's own rule and the reason wave 2 opened two of these.
5. `leaderSkewPercent` is `None` at `ClusterMapping.scala:184` and is now derivable from the filled
   `leaderCount` by the same `BrokerLoad.withSkew` arithmetic already used for replicas. Fill it, or
   write down why not — W3-05 renders whichever you choose.
6. `ClusterTopology.topics` is filled and read only by a DEBUG log line. Either give it a consumer or
   say in the scaladoc that it has none.
7. `LiveTopicSnapshots` has **no test at all** — `grep -rn LiveTopicSnapshots --include=*.scala` finds
   only the object and `TopicWiring`. The per-cluster `Ref` and its stated rule that "only a scrape
   that produced a snapshot becomes the next one's predecessor" is what makes the produce rate
   correct in production, and `TopicSnapshot.of`'s own tests do not exercise the threading.
8. `ControllerUptime` refuses two ways and neither covers the third: a window that spanned six hours
   having been successfully scraped **once** answers `percent: 100.0, windowSeconds: 21600,
   coverageSeconds: 21600`, byte-identical to a fully observed window at 100%. That is a decision —
   `aGapIsNotACountedAbsence` asserts it — but `ControllerUptimeDto` carries no observation count, so
   no client can tell a percentage over one sample from one over 360 while being told "over the last
   6h". Either carry the count or write down why it does not matter.
9. `services/consumer/contract/test/.../ConsumerContractSuite.scala:163-165` justifies its new
   property with "the round-trip property below … never asks which of the three went missing on the
   way out". That is false: `roundTripsForAnyGroupSummary` compares whole records, so a dropped port
   fails it — proven by mutation. The property still earns its place, because it is the only test
   pinning the literal wire key names, so a symmetric rename passes the round trip and fails it. Say
   *that* instead. This is the only reason `services/consumer/**` is in this packet's `Owns`; change
   nothing else there.
10. Paste W3-08's `ARCHITECTURE.md` sentence. Do not invent it.

**Acceptance**
```
./mill services.cluster.__.test
./mill services.topic.__.test
./mill services.consumer.__.test
./mill libs.contractsCore.jvm.test libs.cache.test
./mill '{services.cluster.api,services.topic.api}.openApiCheck'
./mill checkArchitecture
grep -rn '99\.98' services/ libs/ | wc -l      # must equal what item 3 decided
```
**Mutation line:** `TopicSweep.complete` from `Option.when(isComplete)(census)` to `Some(census)` —
the partial sum the design forbids. A case in the **new sweeper suite** must go red, not only the
existing domain cases.

---

## W3-10 — Schema: the short-circuit its own test name claims to assert

**Owns**
```
services/schema/**
```

**Contract.** W2-09's headline rule — a page of N subjects costs at most N enrichment calls, so a
registry with two thousand subjects does not become two thousand calls because somebody opened a
list — is real and is properly gated. Two things around it are not.

`SubjectSummarySuite.scala:67`, "a page with no rows asks the registry nothing beyond the list
itself", asserts only `assertEquals(asked, Nil)`, and `asked` is updated in `summary` and nowhere
else — `SchemaRig.globalCompatibility` records nothing. So the `if page.isEmpty then … else`
short-circuit in `SchemaReads.enrich`, the thing that stops an empty page still paying for the
registry-wide compatibility call, is unguarded: changing `if page.isEmpty` to `if false` leaves the
suite green. The test's name claims more than the test enforces.

And the fan-out is bounded by the page, but the page is bounded at `PageSize.Max = 500`
(`libs/kernel/src/kui/kernel/paging.scala:44`) and nothing narrows it here. A single
`?pageSize=500` is 1 list + 1 global config + 1500 registry requests at concurrency 8 — about 188
sequential rounds against a single-writer JVM. That is inside the stated rule and outside anything
the comments defending the design reason about, all of which reason from twenty-five.

There is a live cost, too: `frontend/packages/shell/src/data/clusterStore.ts` fetches this endpoint
with `pageSize: 1` purely to read `page.totalItems` for the drawer's schema badge, and that request
now costs five registry round-trips instead of one, every time the badge refetches.

**Do**
1. Make the empty-page short-circuit observable: have the rig record the global-compatibility call
   too, so the case named for it can fail.
2. Decide and enforce a bound for this endpoint's page size, or state in the endpoint's scaladoc why
   500 is acceptable against a registry.
3. A route- or HTTP-level test for the half of W2-09's acceptance that was never asserted: one
   enrichment failure, and the page still answers **200** with the bare row in it. Today only
   `result.isRight` is checked at the use-case layer.
4. The cheap path for a count-only request: `pageSize=1` should not enrich. Either the store asks
   differently or the endpoint answers a count without a fan-out; say which in a comment and add the
   case.

**Acceptance**
```
./mill services.schema.__.test
./mill services.schema.api.openApiCheck
./mill checkArchitecture
./mill services.schema.__.checkFormat
```
**Mutation line:** `if page.isEmpty` → `if false`. The case at `SubjectSummarySuite.scala:67` must go
red. It does not today.

---

## W3-11 — Kernel: the rule nothing can see, and two comments that are wrong

**Owns**
```
frontend/packages/kernel/src/components/Monogram.tsx
frontend/packages/kernel/src/components/StatCard.tsx
frontend/packages/kernel/src/components/controls.test.tsx
frontend/packages/kernel/src/components/surfaces.test.tsx
frontend/packages/kernel/src/data/query/useQuery.ts
frontend/packages/kernel/styles/27-primitives-v3.css
```

**Contract.** Wave 2 repaired `Monogram`'s missing `.kui-monogram--md` rule and made the situation
worse. The rule now exists at `27-primitives-v3.css:670`, and deleting it whole leaves 399/399 kernel
tests green, Storybook building and nine monogram stories clean under axe — `vitest.config.ts`
deliberately loads no CSS and axe does not measure size. Worse, `width`, `height` and `font-size`
were **moved out of** the `.kui-monogram` base into the modifier, so a monogram that lost its size
class used to render at 32px and now has no size at all. `Monogram.tsx` always emits the class today
(Solid's `merge` skips an explicit `size={undefined}` and defaults to `"md"`), so this is not a
defect on screen; it is a regression in what the suite can protect, which is the thing this wave
exists to stop shipping.

Two comments in owned files are wrong. `useQuery.ts:9` says "ADR-032's four-state rendering"; ADR-032
defines a five-state `FeatureState`, the same file's line 2 calls the answer "the six-case answer",
and `data/fetched.ts` gives `Fetched` six kinds. `StatCard.tsx`'s header asserts "Six of the design's
eight cards carry both" a pill and a visual, while the `WithVisuals` story wave 2 rebuilt draws both
on all eight and `SCREENS-V4.md §3.2` mentions no pill at all. One of the two is wrong and nobody has
said which.

House rule 2 applies: `--kui-monogram-size` and its siblings live in `27-primitives-v3.css`, which you
own, and **not** in `10-tokens.css`, which is mirrored in Scala. Keep it that way.

**Do**
1. Put the default size back in the `.kui-monogram` base so a tile with no modifier has a size, and
   keep `--sm` as the only override. Then make the rule observable: a case that renders a monogram
   and asserts the class is emitted is the cheapest honest gate available, since layout is checked in
   Storybook by looking rather than by asserting.
2. Say which four states `useQuery.ts:9` means, or name the rendering rules instead of counting them.
3. Settle the `StatCard` pill sentence against `SCREENS-V4.md §3.2` and the story, and correct
   whichever is wrong.

**Acceptance**
```
pnpm -C frontend test packages/kernel
pnpm -C frontend typecheck
pnpm -C frontend build-storybook   # then serve and sweep 'primitives-|surfaces-|charts-'
```
**Mutation line:** delete the `.kui-monogram--md` block. Something must go red. Today nothing does,
and that is the whole packet.

---

## W3-12 — The stack CI cannot build, and the assertion that cannot see its own defect

**Owns**
```
.github/workflows/ci.yml
deployment/**
apps/allinone/**
```

**Contract.** M2 does not close and this packet is why. Three things, each verified here.

* `.github/workflows/ci.yml:272` builds five images —
  `{gateway,cluster,topic,message,consumer}` — and `deployment/compose/docker-compose.yml` now runs
  six backend containers. `kui-metrics` has an `image:` and no `build:`, and
  `docker compose -f deployment/compose/docker-compose.yml pull kui-metrics` answers
  `pull access denied for kui-metrics, repository does not exist`. The stack comes up on a developer
  machine only because somebody ran `./mill deployment.docker.metrics.docker.build` by hand.
* `smoke.sh`'s new capability check compares `docker compose config --services` against
  `/api/v1/capabilities`. The capability document reflects `kui.gateway.services` — the **addresses**.
  The defect it was written for is a **contract** with no address, and `ServiceContracts.byService` is
  on neither side of the equality. `services/schema` is in that map, has a `deployment.docker.schema`
  target, and appears in neither `kui.yaml` nor `docker-compose.yml`; `/api/v1/capabilities` on the
  distributed stack answers `cluster consumer message metrics topic`. The identical defect survives
  one service over and the assertion is blind to it, because both sets simply omit it.
* Replacing `config.metrics` with `MetricsConfig.Default` at `AllInOneWiring.scala:136` —
  reintroducing the exact defect the packet closed — leaves `./mill apps.allinone.test` SUCCESS with
  8/8 in `AllInOneWiringSuite`. Re-verified here. The new case asserts the config **slice**, which its
  own name admits, and never that the wiring hands the sliced value to `MetricsWiring`.

Two smaller ones: `smoke.sh:20` says the target builds "the backend's six" and it builds eight;
`smoke.sh:89` says the loop "named four services while the gateway held a fifth contract" and the
gateway holds six. And `smoke.sh` failed once in three runs here at the recovery step
(`cluster capability was 'degraded' after 90s`) on a busy machine — the script's own comment records
that this ceiling was already raised from 40s for the same reason.

**Do**
1. Add `deployment.docker.metrics` to the `compose` job's image list. Then make the list impossible
   to get wrong again: derive it, or assert in `smoke.sh` that every image the stack names exists
   locally before it starts anything, so the failure says "you did not build kui-metrics" rather
   than "pull access denied".
2. Route the schema service in the distributed stack — a `kui-schema` container and a
   `kui.gateway.services.schema` address — or record in `docker-compose.yml` why the sixth contract
   is deliberately unrouted. Whichever, the smoke check must compare against the **contract** set,
   not only the address set. The gateway can publish it; deciding how is part of this packet.
3. Gate `AllInOneWiring`: assert the wiring passes the configured `MetricsConfig` through, not that
   the config slice exists. The capability reason is the observable — a metrics section configured
   with a source must not report `not_configured`.
4. Correct the two counts in `smoke.sh`, and raise or re-shape the recovery wait so a busy machine
   does not teach people to re-run the smoke test.
5. Paste W3-14's `ci.yml` step for the feature-matrix count check. Do not invent it.

**Acceptance**
```
./mill deployment.docker.metrics.docker.build
docker compose -f deployment/compose/docker-compose.yml config -q
docker compose -f deployment/compose/docker-compose.yml up -d --wait
curl -sf localhost:8090/ui/ >/dev/null && curl -sf localhost:8080/api/v1/health/ready
./deployment/compose/smoke.sh          # three consecutive runs, three passes
docker compose -f deployment/compose/docker-compose.yml down -v
./mill apps.allinone.test
./mill checkArchitecture
```
**Mutation lines, both:** delete the `metrics:` block from `deployment/compose/kui.yaml` — `smoke.sh`
must fail naming it, as it already does; and `config.metrics` → `MetricsConfig.Default` at
`AllInOneWiring.scala:136` — `./mill apps.allinone.test` must fail, which it does not today.

---

## W3-13 — The published wire documents  ·  depends on W3-08

**Owns**
```
docs/api/openapi.json
docs/api/openapi.browser.json
frontend/packages/api/src/schema.d.ts
```

**Contract.** These three are merged documents derived from every service's contract at once, so they
belong to exactly one packet that runs after the backend ones. Write no Scala and no TypeScript: the
deliverable is generator output, and `./mill __.openApiCheck` is a **byte** comparison that fails on a
single renamed key.

The lesson from wave 2 is about the fourth acceptance command, not the first three.
`pnpm -C frontend typecheck` reported green over a regenerated `schema.d.ts` that in fact broke
`packages/shell`, because `schema.d.ts` is a *source* `.d.ts` that `packages/api` never emits into
`.tsbuild/`, so the api project's output signature does not change when it is regenerated and
`tsc --build` judges every dependent project up to date. **Delete `frontend/.tsbuild/` before you
believe a green typecheck.** Then, if it is red, read the error: a hand-declared local interface
mirroring a wire shape is the packet-owner's to widen, and the escape hatch for "a feature package
breaks because a field became optional" does not cover a type substitution in the shell.

**Do**
1. `./mill __.openApi` for every service, then regenerate the browser types.
2. Verify idempotence: run the generator twice and compare checksums.
3. Confirm the frontend typechecks **from a cold build cache**, and report any breakage to the packet
   that owns the file rather than repairing it yourself.
4. `frontend/packages/api/README.md:31` and `ADR-048:129` call an operation count a path count —
   `X-Kui-Principal` was on 42 operations over 32 paths before this wave. Those files are not yours;
   name them in `needsOutsideOwnership` for W3-14.

**Acceptance**
```
./mill __.openApiCheck                                    # green everywhere, gateway included
cd frontend && pnpm --filter @kui/api run generate        # idempotent: md5 unchanged on a second run
rm -rf frontend/.tsbuild && pnpm -C frontend typecheck    # exit 0, from cold
./mill frontend.apiConstants --check
```
**Mutation line:** rename one path key in `docs/api/openapi.browser.json`.
`./mill services.gateway.api.openApiCheck` must fail naming that file.

---

## W3-14 — Say what is true, and make the counts checkable

**Owns**
```
docs/FEATURE_MATRIX.md
README.md
DEPENDENCY_MATRIX.md
TECH_DEBT.md
docs/ROADMAP-SOLID.md
frontend/packages/api/README.md
docs/adr/ADR-021-rbac-model.md
docs/adr/ADR-048-solidjs-typescript-vite-frontend.md
scripts/feature-matrix-check.sh                             (new)
```

**Contract.** W2-12 made `docs/FEATURE_MATRIX.md` say only what its own rows say — 188 rows, 64
COMPLETE, 20 REVIEW, "64 of 177 — 36%" in two files — and re-derived the per-commit history that
W1-12 had invented. All of it still holds; I recounted. **And nothing can tell if it stops holding.**
Setting one row's State from COMPLETE to REVIEW makes four statements false and all six of that
packet's acceptance commands still pass, because three of them merely *print* numbers and the fourth
greps for the prose the packet itself wrote. It detects the prose being edited; it never detects the
prose being wrong. Wave 3 will move a large number of rows, so this is the wave where an unchecked
count drifts.

The recount command the matrix publishes at line 531 still uses `$8`, the Milestone column, while the
paragraph it is offered to recompute is about the State counts — the exact column confusion the
document warns about, published in the file a reader is told to trust.

`ADR-048:398-403` and `frontend/packages/api/README.md:31` carry counts of the merged document that
this wave's search endpoint will move, and one of them calls an operation count a path count.

**Do**
1. Write `scripts/feature-matrix-check.sh`: recount the rows and **compare** them to the prose in
   `docs/FEATURE_MATRIX.md` and `README.md`, exiting non-zero when they disagree. Fix the published
   recount's column while you are there.
2. **Supply the `ci.yml` step to W3-12** in your closing note. You do not own `ci.yml`.
3. Bring the rows this wave finished to their true state, using the checker rather than arithmetic
   done by hand.
4. Correct the operation/path counts in `ADR-048` and `frontend/packages/api/README.md` from the
   figures W3-13 reports.
5. `ADR-021`'s Decision bullet qualifies its resource list — "Kafbat's eleven, verbatim; plus Metrics
   and Alerts … no longer Kafbat's verbatim" — and leaves the other half unqualified: "actions and
   `implies` per `research/scala/security-research.md` §2.2". §2.2 gives KSQL a single EXECUTE action
   with no dependencies; the code has `KsqlView` and `KsqlExecute => Set(KsqlView)`. Amendment 3
   further down explains it, but the bullet still makes an unqualified claim, which is the species of
   sentence this document exists to remove.
6. `TECH_DEBT.md`: TD-016 is closed with its guard explicitly not built. Either open a row for the
   guard or say in TD-016 that the convention is the control. A closed row whose exit condition was
   half met is the shape this register exists to prevent.

**Acceptance**
```
./scripts/feature-matrix-check.sh                          # exit 0
awk -F'|' 'NF>=12 && $2 ~ /^ *[A-Z][A-Z]-[0-9]/ {gsub(/ /,"",$10); print $10}' docs/FEATURE_MATRIX.md | sort | uniq -c
grep -c '64 of 177' README.md docs/FEATURE_MATRIX.md       # or whatever the new figure is, in both
```
**Mutation line:** change one row's State cell. `./scripts/feature-matrix-check.sh` must exit
non-zero. Nothing in the repository does this today.

---

## Where the packets meet

Every pair below shares a boundary. The contract is stated on both sides so that neither has to read
the other's diff. Wave 2's collisions were all in files nobody owned; this table is the other half of
the defence.

| Pair | The contract both sides code against |
| --- | --- |
| W3-06 ↔ W3-08 | The search wire. `GET /api/v1/search?q=&limit=` answers `{"results":{"topics":[],"groups":[],"subjects":[]},"partial":[<serviceId>]}` and a bad `q` is a 400 `KUI-VALIDATION` with `details[0].field == "q"`. W3-06 codes the field against that shape and owns `search.spec.ts`; W3-08 owns the endpoint and its Scala tests. Neither waits on the other's diff. |
| W3-06 ↔ W3-07 | The appearance vocabulary. The theme/accent/density label tables exist twice today and already disagree — `auto` is "Auto" in `chrome/AppearancePopover.tsx:69` and "Match the system" in `pages/SettingsPage.tsx:59`. W3-06 publishes one constant from `packages/shell/src/index.ts`; W3-07 consumes it and deletes its copy. |
| W3-06 ↔ W3-01 | `prefixes()` and `topicTree()` stay in `nav/` and are consumed, not re-implemented. W3-01 folds no topic names in a component; the drawer's tree is W3-06's and the list's grouping is the fold's. |
| W3-06 ↔ every feature packet | The shell's route table is **not** widened. A feature reaches its own pages through `kui.paths.*`; no feature edits `routing/**`. `KuiPaths` gains no member this wave. |
| W3-07 ↔ W3-04 | The dashboard's `NotMeasured` sentences for throughput, latency and produce rate stay until M7's adapter exists. W3-04 does not "fill" them from message data; a rate computed from a browse is not a broker metric. |
| W3-08 ↔ W3-09 | `ARCHITECTURE.md`. W3-09 owns the file and pastes the §9 sentence W3-08 writes in its closing note. W3-08 edits no `.md` outside its own new ADR. |
| W3-08 ↔ W3-13 | W3-08 regenerates **only** `services/gateway/api/openapi.json` and leaves `docs/api/**` and `schema.d.ts` alone. `./mill __.openApiCheck` is red on the gateway module until W3-13 runs; that is the designed intermediate state, not a breakage. |
| W3-08 ↔ the guard files | Adding two gateway endpoints moves `MergedDocumentShapeSuite`'s operationId set and may move `OpenApiMergeSuite`'s path list. Both are inside `services/gateway/**` and therefore W3-08's own — unlike last wave, when the same suites were unowned and cost the integration phase two repairs. |
| W3-09 ↔ W3-13 | `controllerUptime`, `produceRate` and `cleanupPolicy` are already on the wire; W3-09 changes no DTO field this wave. If item 5 fills `leaderSkewPercent`, that is a value change and not a shape change, so the merged documents do not move — but the goldens in `libs/contracts-core` and `services/gateway/contract` do, and both are W3-09's. |
| W3-09 ↔ W3-05 | `leaderSkewPercent` and `segmentCount`. W3-09 decides whether each is filled; W3-05 renders the sentence for whichever stays `None` and does not derive a figure in the browser from `leaderCount`. |
| W3-10 ↔ W3-06 | The schema badge. `clusterStore.ts` asks this endpoint with `pageSize: 1` for `page.totalItems` alone. W3-10 makes a count-only request cheap; W3-06 owns the store and changes the call only if W3-10's answer requires it. State the final shape in W3-10's closing note. |
| W3-12 ↔ W3-14 | `ci.yml`. W3-12 owns the file and pastes the feature-matrix step W3-14 writes in its closing note. W3-14 touches no workflow. |
| W3-12 ↔ W3-08 | The distributed stack routes five services and the all-in-one six. W3-08's search must degrade rather than fail when `schema` is unrouted; W3-12 may make it routed. Neither depends on the other's choice — `partial` is correct either way. |
| W3-13 ↔ everyone | Nobody hand-declares a wire shape. If a generated type breaks your file, widen your file. `frontend/packages/shell/src/data/clusterStore.ts`'s `interface SubjectPage` is the standing example of what not to write, and it is W3-06's to remove. |
| W3-11 ↔ every feature packet | Kernel components reach features only through `@kui/kernel`. W3-11's changes are a CSS rule moving into a base and three comments; no call site changes and no prop is added or removed. |

## The partition, checked

**Frontend.** Inside `frontend/packages/shell/src/`, no packet owns `**`: `App.tsx`, its two test
files, `chrome/`, `nav/`, `data/`, `routing/`, `index.ts` and `styles/` are W3-06's; `overview/` and
`pages/` are W3-07's. `features/`, `messages.ts`, `bootstrap.ts`, `health.ts` and `index.tsx` are
**owned by nobody and need no edit** — `features/registry.ts` enumerates the five feature
registrations and this wave adds no feature, only screens inside the five that exist; the other four
are the boot path, which nothing here changes.

Inside `frontend/packages/kernel/`, W3-11 owns six named files — not `kernel/**`. The barrels
(`components/index.ts`, `src/index.ts`) need no edit because `Monogram`, `StatCard` and `useQuery`
are all already exported. `charts/**`, `data/query/cache.ts`, `data/permissions/**` and every other
kernel file are unowned and need no edit: no chart primitive changes, the cache's behaviour is
settled, and `data/permissions/store.ts`'s "eleven"→"thirteen" was already repaired at integration.

The five `frontend/packages/feature-*/` trees are one packet each, styles included. `frontend/e2e/`
is allocated **per file**: `topics.spec.ts` → W3-01, `features.spec.ts` → W3-03,
`shell.spec.ts`/`dashboard.spec.ts`/`search.spec.ts` → W3-06, and one new file each for W3-02,
W3-04 and W3-05. `fixtures.ts`, `globalSetup.ts` and `playwright.config.ts` are unowned and need no
edit: the fixtures already expose `CLUSTER`, and the config already points at 8090/8080 and refuses
to run against a stack that is not there.

`frontend/packages/api/src/constants.generated.ts`, `frontend/package.json`,
`frontend/scripts/*.mjs` and `frontend/vitest.config.ts` are unowned and need no edit — house rule 3
forbids a new error code, no script changes, and the deliberate decision that vitest loads no CSS is
the reason W3-11's item 1 is shaped the way it is rather than something to reverse.

**Backend.** `services/cluster`, `services/topic` and `libs/contracts-core` are W3-09's together,
because the goldens are shared between the first two and live in the third — the single most common
collision of wave 2, now inside one boundary. `services/schema` is W3-10's, `services/gateway` is
W3-08's, `services/metrics` is unowned and needs no edit (its adapter is M7).
`services/message` is unowned and needs no edit: no message endpoint changes.
`services/consumer/**` is W3-09's for **one comment** and nothing else — the coordinator's address
already reaches the wire correctly, and the packet's Do list says so in as many words.
`libs/kafka`, `libs/kernel`, `libs/security-core`, `libs/config`, `libs/filter`, `libs/serde-*` and
`tools/error-codes` are unowned and need no edit — no vocabulary changes, no error code is added, and
`PageSize.Max` is *read* by W3-10 and not moved.

`build-tests/**` is unowned and must not be edited. It was wave 1's only two-sided collision and
house rules 1 and 2 exist to keep it that way: no new stylesheet file, so `CssReferencesSuite` cannot
move; no new custom property in `10-tokens.css`, so `Tokens.scala` cannot drift.

**Build and deployment.** `.github/workflows/ci.yml`, `deployment/**` and `apps/allinone/**` are
W3-12's alone. `build.mill` and `scripts/run-tests.sh` are unowned and need no edit: every image this
wave needs already has a `deployment.docker.*` target — including `deployment.docker.schema`, which
exists and is simply never built — and no Mill module is added, so the runner's module list does not
move.

**Documents.** `docs/api/**` and `schema.d.ts` are W3-13's alone. `ARCHITECTURE.md`, ADR-016 and
`docs/domain/{cluster,topic}.md` are W3-09's; ADR-049 is W3-08's new file; `docs/FEATURE_MATRIX.md`,
`README.md`, `DEPENDENCY_MATRIX.md`, `TECH_DEBT.md`, `docs/ROADMAP-SOLID.md`,
`frontend/packages/api/README.md`, ADR-021 and ADR-048 are W3-14's. Every other ADR, `docs/ROADMAP.md`,
`docs/testing.md` and `docs/domain/*` other than the two named are unowned and need no edit.
`docs/plan/ROADMAP.md` is unowned this wave: it is edited when a milestone's *shape* changes, and
finishing M6 does not change its shape. `docs/plan/WAVE-03.md` is this file; the wave's closing act
deletes it.

Two directories have two owners at **file** level and neither is owned as a tree, which is legal
under the parallelism rule and is stated here so nobody assumes otherwise.
`frontend/packages/api/` holds W3-13's `src/schema.d.ts` and W3-14's `README.md`; nothing owns
`frontend/packages/api/**`, and `src/index.ts`, `src/probes.ts` and `src/types.test.ts` are unowned
and need no edit. `scripts/` holds W3-14's new `feature-matrix-check.sh`; `run-tests.sh` and every
other script there are unowned and need no edit.

**Two nesting checks, done rather than assumed.** `docs/` is not owned by anyone as a tree — W3-13
owns `docs/api/**`, W3-09 owns four named documents and one ADR, W3-08 owns one new ADR, W3-14 owns
six named documents, and nothing above them is claimed. And `frontend/packages/shell/src/` is not
owned as a tree either: W3-06 and W3-07 divide it by named subdirectory with five files left over,
and those five are named in the paragraph above rather than left to inference.

## What wave 4 will be, so nobody builds it here

Wave 4 is **M7**: the metrics adapter. The service, its module, its image, its `ServiceContracts`
entry, its RBAC resources, its config section and one honest `not_configured` endpoint all exist
already — what is missing is the thing that reads a broker: a JMX client or a Prometheus/JMX-exporter
reader, a retention buffer over `SeriesWindow` (whose three refusals are already the ones M7 needs),
four more `Section`-wrapped endpoints, and then the Traffic tab and the cards that keep their
`NotMeasured` sentence until it lands. Do not stub a metric in wave 3. A card that says it cannot
measure something is the correct rendering and the whole design rests on it staying true.

Two things will also carry into wave 4 unless a packet here finishes them: the `BundleShape` guard
that TD-016 was closed without, and whatever W3-12 decides about the sixth contract with no
container. Both are named in their packets; neither is optional twice.
