# `@kui/api` — the contract seam

This package is the only place in the browser that knows what the server's shapes are. Everything
in it is either generated from a committed contract or is the small amount of runtime needed to talk
to one. **Nothing here is a hand-written mirror of a server type, and nothing outside here may be.**

That rule is what replaces the guarantee ADR-011 got for free. Under Scala.js the browser and the
server compiled against the same Tapir endpoint values, so renaming a field broke both halves at
once. ADR-048 gives that up and buys it back with generation plus three build gates.

## The chain, end to end

```
a Tapir endpoint changes shape
  └─ ./mill __.openApiCheck                fails until docs/api/openapi.json and
     │                                     docs/api/openapi.browser.json are regenerated
     └─ ./mill frontend.apiTypes --check   fails until src/schema.d.ts is regenerated
        └─ ./mill frontend.typecheck       fails at every call site that used the old shape
```

Three build failures, and a browser never enters into it. Measured, not asserted: renaming
`GroupSummaryDto.totalLag` in the document and regenerating produced
`probes.ts(61,23): error TS2339: Property 'totalLag' does not exist`, `tsc exit=1`.

## Why the browser has its own document

`docs/api/openapi.json` describes the contract KUI's *services* speak, and that contract requires
`X-Kui-Principal` on 42 of its 46 paths. The gateway mints that header and strips every inbound
`X-Kui-*` from browsers at the edge (ADR-020, ADR-040) — so generating a browser client from it
produces types that oblige every call site to send an internal trust header the browser must never
send. The type system would enforce the exact inverse of the security boundary.

`docs/api/openapi.browser.json` is the edge view: the same document with those headers removed, by
the same rule `EdgeHeaders.isForbidden` applies at runtime, in the same module, from the same list.
`X-Csrf-Token` (19 operations) and `If-Match` (2) stay, because the browser really does send them
and the types should force it to. It is computed, never maintained: `BrowserProjection` in
`services/gateway/api` produces it and `openApiCheck` keeps it honest.

## Regenerating

```
./mill services.gateway.api.openApi     # both documents, from the Tapir endpoints
./mill frontend.apiTypes                # src/schema.d.ts, from the browser document
./mill frontend.apiConstants            # src/constants.generated.ts, from the Scala constants
```

Each has a `--check` mode that CI runs. Two files in `src/` are generated and must not be edited:
`schema.d.ts` and `constants.generated.ts`.

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

Fifteen properties across the aggregated responses — `TopicsResponse.topics`,
`GroupsResponse.groups`, `ClusterOverviewDto.clusters` and twelve more — are typed `unknown`,
because the server documents `Section[A]` with `Schema.any`. `section.ts` is the single boundary
that narrows them, and `BLOCKERS.md` B-005 has the measurement and the proposed server-side fix.
Use `decodeSection` there; do not cast at a call site.

## `probes.ts`

A file of functions nothing calls. It reads one representative field from each service so that the
contract guarantee is exercised *now*, before the feature packages exist to exercise it. It is not
exported from `index.ts`, so no byte of it ships. When the features read these shapes in earnest, it
can go.
