# `@kui/api` — the contract seam

This package is the only place in the browser that knows what the server's shapes are. Everything
in it is either generated from a committed contract or is the small amount of runtime needed to talk
to one. **Nothing here is a hand-written mirror of a server type.** The second half of that sentence
used to read *and nothing outside here may be*, and it was not true when it was written: seven
interfaces in `frontend/packages/shell/src/overview/metrics.ts` — `LatencyBucket`, `LatencySeries`,
`PurgatoryQueue`, `HandlerDocument`, `TopicProducerEntry`, `ProducerDocument` and
`RecordSizeDocument` — are hand-transcribed from `services/metrics/contract`'s DTOs, because generation gives the browser
**nothing** for those five payloads. The rule and the reason it cannot be kept are in the
*Known gap* section below and in `TECH_DEBT.md` TD-024; two of those seven were transcribed wrongly and drew a
false sentence on a shipped screen for a whole milestone, which is the cost the gap has already had.

That rule is what replaces the guarantee ADR-011 got for free. Under Scala.js the browser and the
server compiled against the same Tapir endpoint values, so renaming a field broke both halves at
once. ADR-048 gave that up and buys it back with generation plus three gates — one on each side of
the seam, because there are now two builds and neither can fail the other's.

## The chain, end to end

```
a Tapir endpoint changes shape
  └─ ./mill __.openApiCheck                     fails until docs/api/openapi.json and
     │              (Mill, backend)             docs/api/openapi.browser.json are regenerated
     └─ pnpm --filter @kui/api run generate     rewrites src/schema.d.ts from the browser document;
        │           (pnpm, frontend)            a stale committed file shows up as a diff
        └─ pnpm typecheck                       fails at every call site that used the old shape
                    (pnpm, frontend)
```

Three build failures, and a browser never enters into it. Measured, not asserted: renaming
`GroupSummaryDto.totalLag` in the document and regenerating produced
`probes.ts(61,23): error TS2339: Property 'totalLag' does not exist`, `tsc exit=1`.

## Why the browser has its own document

<!-- checked: merged-document -- verified by ./scripts/feature-matrix-check.sh -- claims: principal-operations, principal-paths, csrf-operations, if-match-operations -->
`docs/api/openapi.json` describes the contract KUI's *services* speak, and that contract requires
`X-Kui-Principal` on 52 of its 68 operations, across 41 of its 57 paths. Those are two figures and
not one: a path with a `GET` and a `DELETE` carries the header on both operations and is still one
path. This paragraph used to pair the *operation* count with the *path* total as though they were
the same denominator, which is why it is now checked rather than maintained. The gateway mints that
header and strips every inbound `X-Kui-*` from browsers at the edge (ADR-020, ADR-040) — so
generating a browser client from it produces types that oblige every call site to send an internal
trust header the browser must never send. The type system would enforce the exact inverse of the
security boundary.

`docs/api/openapi.browser.json` is the edge view: the same document with those headers removed, by
the same rule `EdgeHeaders.isForbidden` applies at runtime, in the same module, from the same list.
`X-Csrf-Token` on 21 operations and `If-Match` on 2 operations stay, because the browser really does
send them and the types should force it to. It is computed, never maintained: `BrowserProjection` in
`services/gateway/api` produces it and `openApiCheck` keeps it honest.
<!-- /checked -->

## Regenerating

```
./mill services.gateway.api.openApi              # both documents, from the Tapir endpoints
./mill frontend.apiConstants                     # src/constants.generated.ts, from the Scala constants
pnpm --filter @kui/api run generate              # src/schema.d.ts, from the browser document
```

The two Mill commands take `--check`, which fails when the committed file no longer matches the
Scala it came from. `schema.d.ts` has no `--check`: regenerate it and let a non-empty `git diff` be
the failure. Both generated files live in `src/` and must not be edited by hand.

The split is deliberate. `apiConstants` runs on the backend's side because it reads Scala
definitions and writes a TypeScript file, and it needs no Node; `generate` runs on the frontend's
side because it reads a committed JSON document and needs no JVM. Neither build depends on the
other's toolchain, which is the whole arrangement ADR-048 describes.

`constants.generated.ts` exists because two strings never appear in an OpenAPI document and have
both caused shipped defects: the CSRF header's name (the browser once sent `X-Kui-Csrf` while the
gateway read `X-Csrf-Token`, so every mutation returned 403) and the vocabulary of the error
envelope's `code` field, which the interface is required to branch on rather than on messages.

## Using it

```ts
const api = createApiClient({ bootstrap, origin: location.origin, csrf, onUnauthorized });

const answer = await api.get("/api/v1/clusters/{clusterId}/topics", {
  params: { path: { clusterId } },
});

if (answer.ok) render(answer.value);
else showFailure(answer.error);          // a value the page draws, never a thrown error
```

Four things the client does that no caller should have to remember:

- the session cookie travels (`credentials: "include"`);
- every non-`GET` carries the CSRF header, **waiting** for start-up to produce a token rather than
  being sent without one;
- a `401` clears the token and notifies the session before the caller sees the failure;
- nothing throws. A rejected promise inside a Solid computation reaches the nearest `<Errored>`
  boundary and unmounts the page — the blank screen this product has already shipped once.

## Known gap: sections are `unknown`

Twenty-three properties across the aggregated responses — `TopicsResponse.topics`,
`GroupsResponse.groups`, `ClusterOverviewDto.clusters` and twenty more — are typed `unknown`,
because the server documents `Section[A]` with `Schema.any`. Count them with
`grep -c ': unknown;' src/schema.d.ts` and subtract the 136 index signatures, or read the list in
`TECH_DEBT.md` TD-024, which carries the measurement and the proposed server-side fix. (This
paragraph said *fifteen* and cited a `BLOCKERS.md` that is not in this repository; three files
still cite it. The figure had drifted with the contract and the citation had never resolved.)

`section.ts` is the single boundary that narrows them. Use `decodeSection` there; do not cast at a
call site. **Five of the twenty-three are the metrics reads** — `LatencyResponse.latency`,
`ThroughputResponse.throughput`, `TopProducersResponse.producers`,
`RecordSizeResponse.recordSize` and `RequestHandlersResponse.requestHandlers` — and they are the
ones this gap has actually cost something, because they are the five whose payload shape no other
generated type describes, so the browser hand-writes all of it.

## `probes.ts`

A file of functions nothing calls. It reads one representative field from each service so that the
contract guarantee is exercised *now*, before the feature packages exist to exercise it. It is not
exported from `index.ts`, so no byte of it ships. When the features read these shapes in earnest, it
can go.
