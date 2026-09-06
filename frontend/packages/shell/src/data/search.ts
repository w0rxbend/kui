/**
 * Cross-entity search: asking the gateway, and turning its answer into the rows the field draws.
 *
 * ## Why the decoding is a fold over `unknown` rather than a wire type
 *
 * `GET /api/v1/search` was new this wave, and this module was written while the address existed on
 * the server but not in `docs/api/openapi.browser.json` — and so not in `@kui/api`'s generated
 * `paths`. **It has since landed in both**: the merged documents were regenerated in the same wave
 * and `schema.d.ts` declares the path. Declaring an `interface SearchResponse` here to bridge the
 * gap would have been exactly the mistake this store already made once with `interface
 * SubjectPage`, which went on compiling after the subjects endpoint was widened underneath it. So
 * nothing here declares the wire: {@link decodeSearch} takes `unknown` and reads what it needs,
 * field by field, the way `decodeCapabilityFrame` reads a stream frame. That is still a defensible
 * decoding — a shape change shows up as an empty result list rather than as a type that quietly
 * lies — but the reason stated here for it has expired, so whoever next edits this module decides
 * the question on its merits and not on a generated type that no longer fails to exist.
 *
 * The shape it reads is the one stated in the wave plan, which both sides code against:
 *
 * ```
 * GET /api/v1/search?q=<1..200>&limit=<1..50, default 10>
 * 200 {"results": {"topics":   [{"cluster","name"}],
 *                  "groups":   [{"cluster","groupId"}],
 *                  "subjects": [{"cluster","subject"}]},
 *      "partial": ["schema"]}
 * ```
 *
 * ## `partial` is the whole reason this is not three fetches from the browser
 *
 * Both stacks now route all six services — the distributed one gained its `kui-schema` container
 * in wave 3, and before that `schema` was the one that differed. The registry can still refuse
 * regardless: that container is configured with no cluster and answers `not_configured`, and any
 * deployment can have a registry it cannot reach. So a search that cannot ask the registry is an
 * ordinary case, not a failure.
 * ADR-039's rule applies to a fold the same way it applies to a page: the part that could not be
 * asked says so, and the parts that answered are still shown. A search that silently returned two
 * lists out of three would tell an operator their subject does not exist.
 *
 * ## Two exports that were removed rather than left
 *
 * `NO_RESULTS` and `searchFailure` were written here for callers that never appeared: the first
 * described "the answer an empty query is given without asking anybody", which is a path the shell
 * does not have — emptying the box ends the search and builds no answer at all — and the second was
 * `userMessage` under another name, which `App.tsx` already calls directly. Both had exactly one
 * reference in the repository, their own declaration. They are named here so that the next reader
 * who wants an empty-answer constant knows one was tried and what it cost.
 */

import type { ApiResult, KuiApiClient } from "@kui/api";

import type { SearchResultGroup } from "../chrome/SearchField.jsx";

/** How many rows of each kind the gateway is asked for. Ten is its own default; see the plan. */
export const SEARCH_LIMIT = 10;

/**
 * The longest `q` the endpoint accepts, and therefore the longest the box lets anybody type.
 *
 * Enforced in the field rather than handled as a failure, because the failure is not one the field
 * can explain: a 201-character `q` is a `KUI-VALIDATION` 400, and the overlay's only failure
 * rendering says the search is not answering — which would send an operator to look at a gateway
 * that is working perfectly. A `maxlength` on the input makes the case unreachable instead, which
 * is the honest version of the same rule and the one the browser already knows how to show.
 */
export const SEARCH_MAX_LENGTH = 200;

/**
 * How long the field waits after the last keystroke before asking.
 *
 * 200ms is the usual figure and the usual figure is right here for a specific reason: the fold is
 * one request per service per cluster, so a keystroke that escaped the debounce costs three
 * upstream calls rather than one. Long enough that a typed word is one request; short enough that
 * the overlay does not feel detached from the keyboard.
 */
export const SEARCH_DEBOUNCE_MS = 200;

/** One thing the gateway found, already reduced to what a row shows. */
export interface SearchHit {
  /** Which cluster it is on. Every hit carries one: the search is across clusters. */
  readonly cluster: string;
  /** The topic name, the group id or the subject — whichever this row is. */
  readonly name: string;
}

/** What one search answered: three lists, and the services that could not be asked. */
export interface SearchAnswer {
  readonly topics: readonly SearchHit[];
  readonly groups: readonly SearchHit[];
  readonly subjects: readonly SearchHit[];
  /**
   * The ids of the services that did not contribute, e.g. `["schema"]`.
   *
   * Service **ids**, not a boolean, because the field says which one is missing in words. A
   * boolean would put "some results may be missing" under a search for a subject name, which is
   * the sentence that makes an operator retry rather than go and look at their registry.
   */
  readonly partial: readonly string[];
}

/** How many rows an answer holds, across the three kinds. */
export function searchHitCount(answer: SearchAnswer): number {
  return answer.topics.length + answer.groups.length + answer.subjects.length;
}

/**
 * The gateway's answer, read defensively.
 *
 * Every list is optional and every row is checked, because "absent" is a real answer here: a
 * service in `partial` contributes no list at all, and a gateway that routes no schema service
 * omits `subjects` entirely. A missing list is an empty list plus, usually, an entry in `partial` —
 * and the two are reported separately so the field can tell "nothing matched" from "nobody asked".
 */
export function decodeSearch(body: unknown): SearchAnswer {
  const root = objectOf(body);
  const results = objectOf(root?.["results"]);
  return {
    topics: hitsOf(results?.["topics"], "name"),
    groups: hitsOf(results?.["groups"], "groupId"),
    subjects: hitsOf(results?.["subjects"], "subject"),
    partial: stringsOf(root?.["partial"]),
  };
}

/**
 * One request, as a result that cannot throw.
 *
 * ## The cast, and when it goes away
 *
 * `KuiApiClient.get` is typed from the generated `paths`, which did not contain this address when
 * this was written. **It does now** — the browser document was regenerated in the same wave — so
 * the cast below bridges nothing any more and can go. It is described rather than removed here
 * because removing it changes the call and not a comment, and that belongs to whoever next edits
 * this function. Until then the call is still made through a single narrowed view of the client,
 * in one place, with the query built by `openapi-fetch` exactly as it is for every other call: the
 * encoding of `q` is not this module's business, and a hand-built query string is how a search for
 * `orders/payments` becomes a request for a path.
 *
 * The answer is `unknown` on purpose and stays `unknown` until {@link decodeSearch} reads it, so
 * removing the cast later changes this function and nothing below it.
 */
export async function fetchSearch(
  api: KuiApiClient,
  query: string,
  limit: number = SEARCH_LIMIT,
): Promise<ApiResult<SearchAnswer>> {
  const call = api.get as unknown as (
    path: string,
    init: { readonly params: { readonly query: { readonly q: string; readonly limit: number } } },
  ) => Promise<ApiResult<unknown>>;

  const answer = await call("/api/v1/search", { params: { query: { q: query, limit } } });
  return answer.ok ? { ok: true, value: decodeSearch(answer.value) } : answer;
}

/**
 * What the search field is currently showing, as one value rather than four flags.
 *
 * The same argument `NoticeFeed` makes next door: three booleans describe eight states, five of
 * which are nonsense, and the nonsense is what gets rendered when a request fails halfway.
 */
export type SearchState =
  | { readonly kind: "idle" }
  | { readonly kind: "searching" }
  | { readonly kind: "ready"; readonly answer: SearchAnswer }
  | { readonly kind: "failed"; readonly reason: string };

/** The `status` the field takes, from the state above. */
export type SearchFieldStatus = "idle" | "searching" | "ready" | "empty" | "failed";

export function searchStatus(state: SearchState): SearchFieldStatus {
  switch (state.kind) {
    case "idle":
      return "idle";
    case "searching":
      return "searching";
    case "failed":
      return "failed";
    case "ready":
      /* An answer that found nothing is "empty" only when everybody was asked. When a service is in
         `partial` the honest rendering is the result list with its "could not be asked" line in it,
         because "Nothing matches" over a search that never reached the registry is a false
         negative — and a false negative in a search box is indistinguishable from an absence. */
      return searchHitCount(state.answer) === 0 && state.answer.partial.length === 0
        ? "empty"
        : "ready";
  }
}

/**
 * Where each kind of hit goes.
 *
 * Handed in for the reason `NavigationInput.landingFor` is handed in: this module concatenates no
 * URLs. The shell builds all three through the router's typed path proxy, so a renamed segment is a
 * compile error at the one place that spells addresses rather than a result list that quietly 404s
 * — and `KuiPaths` gains no member for the subject row, which is the one destination the shared
 * interface does not carry.
 */
export interface SearchLinks {
  readonly topic: (cluster: string, name: string) => string;
  readonly group: (cluster: string, groupId: string) => string;
  /** The registry for a cluster. There is no per-subject address, and inventing one would 404. */
  readonly subjects: (cluster: string) => string;
}

/**
 * The three result groups, in the order the overlay draws them.
 *
 * A group with no rows is left out rather than drawn as an empty heading: the field's own empty and
 * failed states are what say "there is nothing", and a heading over nothing says it a second time
 * in a way that looks like a list that failed to load.
 */
export function searchGroups(
  answer: SearchAnswer,
  links: SearchLinks,
): readonly SearchResultGroup[] {
  const groups: SearchResultGroup[] = [];
  const add = (heading: string, hits: readonly SearchHit[], href: (hit: SearchHit) => string) => {
    if (hits.length === 0) return;
    groups.push({
      heading,
      items: hits.map((hit) => ({
        id: `${heading}:${hit.cluster}:${hit.name}`,
        label: hit.name,
        href: href(hit),
        /* The cluster, on every row. The search is across clusters, so two rows can carry the same
           topic name and differ only in which cluster they are on — and a reader who cannot see
           which is which will open the wrong one. */
        detail: hit.cluster,
      })),
    });
  };

  add("TOPICS", answer.topics, (hit) => links.topic(hit.cluster, hit.name));
  add("CONSUMER GROUPS", answer.groups, (hit) => links.group(hit.cluster, hit.name));
  add("SUBJECTS", answer.subjects, (hit) => links.subjects(hit.cluster));
  return groups;
}

/**
 * What each service is called in the sentence naming it as unavailable.
 *
 * Service ids are what the gateway reports and they are not words a person uses: "schema" beneath a
 * search box reads as a noun the operator was looking for rather than as the name of a service that
 * was not asked. Anything this build has not heard of keeps its id, which is worse than a name and
 * far better than being dropped.
 */
const SERVICE_LABELS: Readonly<Record<string, string>> = {
  cluster: "Clusters",
  consumer: "Consumer groups",
  message: "Messages",
  metrics: "Metrics",
  schema: "Schema Registry",
  topic: "Topics",
};

/** The services that could not be asked, in words, for the line under the results. */
export function unavailableServices(answer: SearchAnswer): readonly string[] {
  return answer.partial.map((service) => SERVICE_LABELS[service] ?? service);
}

/* --- Reading the answer ----------------------------------------------------------------------- */

function objectOf(raw: unknown): Record<string, unknown> | undefined {
  return typeof raw === "object" && raw !== null ? (raw as Record<string, unknown>) : undefined;
}

/**
 * One list of hits, from whichever field carries the row's name.
 *
 * The three lists differ only in what the name is called — `name`, `groupId`, `subject` — because
 * each is the identifier its own service uses, and flattening them at the gateway would have made
 * the wire say less than the services do. A row missing either half is dropped rather than drawn
 * with a blank: a result row with no name is a click target that says nothing.
 */
function hitsOf(raw: unknown, nameField: string): readonly SearchHit[] {
  if (!Array.isArray(raw)) return [];
  const hits: SearchHit[] = [];
  for (const item of raw) {
    const row = objectOf(item);
    const cluster = row?.["cluster"];
    const name = row?.[nameField];
    if (typeof cluster !== "string" || typeof name !== "string" || name.length === 0) continue;
    hits.push({ cluster, name });
  }
  return hits;
}

function stringsOf(raw: unknown): readonly string[] {
  return Array.isArray(raw) ? raw.filter((item): item is string => typeof item === "string") : [];
}
