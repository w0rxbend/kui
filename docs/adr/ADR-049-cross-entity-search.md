# ADR-049 — Cross-entity search is a fold at the gateway, and `partial` is a list of service ids

- Status: Accepted
- Date: 2026-09-06

## Context

Every screen carries the same field in the top bar. Typing `orders` into it has to answer one
question — "where is `orders`?" — over three kinds of thing that belong to three different
services: topics (`kui-topic`), consumer groups (`kui-consumer`) and Schema Registry subjects
(`kui-schema`). Before this decision `docs/api/openapi.json` had **no path containing `search`**
at all, so the field had nothing to call.

Two facts about the deployments shape everything below.

- **Not every deployment routes every service.** When this was written
  `deployment/compose/docker-compose.yml` ran five backend services and the gateway;
  `/api/v1/capabilities` on that stack answered `cluster consumer message metrics topic`, because
  the schema service had a contract, a Mill module and a `deployment.docker.schema` image and no
  address, while the all-in-one process routed six. So a search that could only work when all three
  services were present would be a permanently broken field on the stack most people run first.

  **Corrected the same day:** that stack gained its `kui-schema` container, so both shapes now route
  six and the capability list names the schema service. The premise survives the correction and is
  the reason this section is left standing rather than rewritten — the schema service in that stack
  is configured with no cluster and answers `not_configured`, any deployment can have a registry it
  cannot reach, and an operator may still route five. The decision below holds for a service that is
  absent *or* refuses, which is why it did not have to be revisited when the container appeared.
- **The list endpoints already exist and already match.** `GET …/clusters/{id}/topics/names` answers
  every topic name on a cluster, unpaged, in one call — it was built in wave 2 for exactly this. The
  consumer service's list and the schema service's subject list both take a `q` and narrow on their
  own side, over snapshots they already hold.

## Decision

### 1. The search is a fold at the gateway, not a seventh service

`GET /api/v1/search?q=<1..200>&limit=<1..50, default 10>` is served by the gateway itself, beside
the dashboard and the topic-page aggregations it already answers, over the published contracts of
the three services. **No service module is edited and no service is registered**:
`ServiceContracts.byService` still holds six services.

A seventh service was rejected on cost and on correctness. On cost, a service in the ADR-041 six-layer
shape is about three thousand production lines with its own config section, RBAC resource,
`ServiceContracts` entry, Mill module, container image and regenerated documents — the schema service,
the smallest complete one in the repository, is the measured figure. On correctness, a search service
would own no data: it would hold three clients and call the same three endpoints this fold calls,
one process further from the browser, adding a hop and a failure mode to every keystroke. The
gateway is already the process whose subject matter is other services' contracts, and it is already
where the two other cross-service aggregations live.

An index was rejected for the same reason ADR-038 deferred Lucene: the data is names, the services
already hold them in `NameIndex`-backed snapshots, and an index at the gateway would be a second copy
of a set of strings that goes stale the moment a topic is created.

### 2. `partial` is a list of service ids, not a boolean

The answer is

```json
{"results": {"topics": [{"cluster", "name"}],
             "groups": [{"cluster", "groupId"}],
             "subjects": [{"cluster", "subject"}]},
 "partial": ["schema"]}
```

All three result lists are always present, empty included, and `partial` names every service the
gateway could not ask.

A boolean would have been enough to draw a grey "some results may be missing" line, and that is
exactly what makes it wrong. The remedy differs per service and only the id carries it: a deployment
that routes no schema service will *never* produce subject results and should say so plainly, while a
topic service that timed out is an outage somebody can go and look at. The two look identical through
a boolean, and identical to a browser that was simply handed fewer results — which is the failure
this product exists not to have. It is the same argument ADR-032 makes for `not_configured` versus
`unavailable`, one level up: an empty list and "we could not ask" must not be the same document.

A service reaches `partial` in four ways, and all four are the same fact to whoever is looking:

- this deployment routes no such service (the distributed stack, and `schema`);
- the call failed — unreachable, timed out, the breaker is open;
- the caller is not permitted to list that kind of thing, so the service refused;
- the service answered with a freshness section carrying no rows at all, such as a cluster whose
  topics have never been scraped, or one with no Schema Registry configured.

`partial` is sorted and de-duplicated, so two identical requests produce identical bytes. Losing the
**cluster list** is the one compound case: nothing then knows which clusters exist, so the answer
names `cluster` and all three searched services rather than reporting an empty product.

### 3. The fan-out is bounded by clusters × services

One request per service per cluster, issued in parallel, and no request per result. The three list
endpoints each narrow on their own side — `topics/names` is unpaged by design, and the other two take
the caller's `q` with `pageSize = limit` — so nothing here asks a follow-up question about a hit it
has just been told about. `SearchSuite.theFoldIssuesOneRequestPerServicePerClusterAndNotOnePerResult`
pins it: ten matches on each of two clusters is seven upstream calls.

`limit` caps each kind separately rather than sharing one budget across the three, because the result
panel draws three groups and a query matching forty topics must not push every group off the end.

### 4. A bad `q` is `KUI-VALIDATION`, declared on the endpoint

The bounds — 1 to 200 characters, and a limit of 1 to 50 — are Tapir validators on the contract, so
they appear in the published document and are enforced by the same decode path every other endpoint's
malformed input takes. `details[0].field` is `"q"`. No error code is added: `KUI-VALIDATION` is the
code for a request that is malformed, and this one is.

The lower bound is one character rather than zero because a blank query matches *every* name — that
is the substring rule, not an accident — and a browser sends one on every backspace. `limit` is
refused rather than clamped, unlike a list endpoint's `pageSize`: a page that clamps is still a page
of a list somebody is walking through, while a search that quietly returned fifty of two hundred
would look like a cluster holding fewer topics than it has.

### 5. Permission is the services', not a second check here

Each upstream call carries the caller's signed principal, so the topic, consumer and schema services
apply their own RBAC to it and a caller sees only what they may see. A refusal makes that service
`partial` for this query. There is deliberately no separate search permission: a second rule about
who may see a topic name is a second answer to "may I", and the interface would be wrong in whichever
direction the two had drifted.

## Consequences

- The three sources live in `services/gateway/api/search/` and the fold in
  `services/gateway/application/search/`. The split is a module edge and not a preference: the
  gateway's `application` module sees the cluster, topic and consumer contracts, and the schema
  service's contract reaches only as far as `api`. Putting two sources in one layer and one in
  another to preserve a symmetry nobody can see would be worse than the note that explains it.
- `SearchUseCase.Services` is the single list of what a search covers. The composition root builds
  its sources by walking it, so "what is searched" and "what `partial` can name" cannot drift apart.
- Adding a fourth searchable kind — Connect connectors, in M9 — is one `SearchSource`, one entry in
  that list, one case in `GatewayWiring.searchSourceOf` and one field on the results document.
- The merged OpenAPI documents move by one path. They are regenerated once, by the packet that owns
  `docs/api/**`, and `./mill services.gateway.api.openApiCheck` is red until that happens.

## Alternatives rejected

- **A seventh service.** Cost and an extra hop, for a component that would own no data. See §1.
- **A `partial: boolean`.** Cannot distinguish a deployment choice from an outage. See §2.
- **Omitting a category's key when it could not be asked.** Indistinguishable, in a browser, from a
  category that matched nothing.
- **Searching one cluster at a time, from the browser.** The field is at the top of every screen and
  belongs to the product, not to the cluster whose page happens to be open; and it would turn one
  keystroke into one request per cluster from the browser instead of one from the gateway.
- **An index at the gateway.** A second copy of names the services already index, stale on every
  topic creation. ADR-038's reasoning, unchanged.

## Reversibility

High. The endpoint is the gateway's own; nothing in any service knows it exists. Replacing the fold
with a service later means keeping the same public path and answer shape and changing one composition
root.
