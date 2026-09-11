/**
 * The Kafka Connect wire, as this browser reads it.
 *
 * ## Every shape here is transcribed from `services/connect/contract`'s DTOs, and checked against
 * them
 *
 * The first draft of this file was written from the wave plan's prose contract — *`Section`-wrapped
 * reads under `/api/v1/clusters/{clusterId}/connect/…`* — and it was **wrong in the one way that
 * matters**: it read `{ connectors: { data: { items: [...] } } }`, and the service renders
 * `{ connectors: { data: { workers: [ { connect, connectors: Section<{items, unreadable}> } ] } }
 * }`. The section key matched, so the decode would have *succeeded* and answered an empty list, and
 * the screen would have drawn "the Connect workers answered and named no connectors" over a worker
 * running three. That is wave 5's producers defect, character for character, and it was caught here
 * by `wire.golden.test.ts` the moment `services/connect/contract` committed its goldens — which is
 * the whole argument for house rule 12 and the reason that suite is red rather than skipped when
 * the files are absent.
 *
 * So: `wire.golden.test.ts` decodes `services/connect/contract/test/resources/golden/*.json` off
 * disk, through the same fetcher the screen calls. The path is the contract, and it is stated on
 * both sides.
 *
 * ## The section is **per worker**, and that is the shape of the whole response
 *
 * A Kafka cluster may configure several Connect clusters (`kui.clusters.<n>.connect[]`). One of
 * them being down costs **one row** rather than the screen — the same rule one dead alert rule
 * follows — so each worker carries its own `Section` and this module turns each into its own
 * `Fetched`. A screen that flattened them would have to choose between hiding a failure and hiding
 * the connectors the other workers did answer for.
 *
 * ## Three derived figures travel, and this file does not recompute any of them
 *
 * `runningTasks`, `taskCount` and `failed` are computed by the service from the tasks and sent
 * anyway, and `ConnectorDto`'s scaladoc says why in as many words: *the card's `3/3 tasks`, the
 * drawer's `1 failed` row and the page's voice line are three places that must show one number, and
 * three browsers deriving it are three chances to derive it differently.* `RESTARTING` is not
 * running, and that rule lives in the domain. So this module reads those three fields and
 * {@link ConnectorPanel} draws them; nothing here counts a task.
 *
 * `failed` in particular is **not** `state === "FAILED"`. A connector whose own state is `RUNNING`
 * and one of whose tasks has failed is a failed connector, and the golden document has exactly that
 * connector in it.
 *
 * ## And the figure that is not on this wire at all
 *
 * `SCREENS-V4.md` §3.14 draws `3/3 tasks · 1,204 msg/s · orders.*`. The Connect REST API publishes
 * **no throughput** — a worker's status document carries states, worker ids and traces, and the
 * per-connector record rate lives in the workers' JMX beans, which `services/connect` does not read
 * (ADR-054 §6). So no rate reaches this module, the card says "throughput not measured" rather than
 * `0 msg/s`, and §3.14's own *Absent* paragraph is the rule being kept: a literal zero on a paused
 * connector is a measured zero, and an unmeasured one must never look like it.
 */
import { ReasonCodes, decodeSection, type Section } from "@kui/api";

/** The connector states this build draws. Anything else the worker says becomes `UNKNOWN`. */
export type ConnectorRunState =
  | "RUNNING"
  | "FAILED"
  | "PAUSED"
  | "UNASSIGNED"
  | "RESTARTING"
  | "UNKNOWN";

/** The task states this build draws. Same rule, same reason. */
export type TaskRunState = ConnectorRunState;

export interface ConnectorTask {
  readonly id: number;
  /** Folded from the worker's own word. See {@link connectorState}. */
  readonly state: TaskRunState;
  /** The worker holding the task, when Connect said which. */
  readonly workerId: string | undefined;
  /**
   * The first line of {@link trace}, as the **service** sliced it.
   *
   * Sliced there rather than here so that the card, a drawer row and a future notification cannot
   * each take a different line of the same trace — `ConnectorTaskDto`'s scaladoc states it. Absent
   * when the worker recorded no trace, **which is not the same as no problem**: a failed task with
   * no reason is drawn as failed with the reason missing.
   */
  readonly reason: string | undefined;
  /** The whole trace, verbatim, for the operator who needs the caused-by chain. */
  readonly trace: string | undefined;
}

export interface Connector {
  /** The Connect cluster this connector lives on — and the subject of its permission question. */
  readonly connect: string;
  readonly name: string;
  /** `source`, `sink`, or empty where the worker said nothing. Never inferred from a class name. */
  readonly kind: string;
  readonly state: ConnectorRunState;
  readonly workerId: string | undefined;
  /** The connector's own failure line, or its first failed task's. The service picks. */
  readonly reason: string | undefined;
  /** True when the connector **or any of its tasks** failed. Not `state === "FAILED"`. */
  readonly failed: boolean;
  /** The service's count of `RUNNING` tasks. `RESTARTING` is not running. Never recomputed here. */
  readonly runningTasks: number;
  readonly taskCount: number;
  readonly tasks: readonly ConnectorTask[];
}

/** What one Connect cluster is running, when it answered. */
export interface ConnectorsPage {
  readonly items: readonly Connector[];
  /**
   * The connectors the worker named and then would not describe, in the order it named them.
   *
   * On the wire rather than dropped, and drawn rather than dropped, because a connector missing
   * from a list is indistinguishable from a connector that was deleted. `GET
   * /connectors?expand=status` is answered per connector by the worker that owns it, so one wedged
   * worker in a Connect cluster loses the status of its share while the rest answer normally — this
   * is that share.
   */
  readonly unreadable: readonly string[];
}

/** One configured Connect cluster's row: its name, and what it said or why it did not. */
export interface WorkerRow {
  readonly connect: string;
  readonly page: WorkerAnswer;
}

/**
 * What one worker's section became.
 *
 * `rebalancing` is a case of its own and it is the browser half of a rule with three halves that
 * must agree: `ConnectMapping.section` maps `ErrorCode.ConnectRebalancing` to
 * `Section.Unavailable(ReasonCode.Starting, …)` keeping the worker's own sentence,
 * `ConnectHttp.errorFrom` classifies the 409 as an application error so ADR-039 §6 keeps it from
 * dimming anything, and `ConnectCapabilities.probe` leaves the capability available. A browser that
 * drew `unavailable` as a failure would contradict the other two and put a red panel and a Retry in
 * front of an operator over a state that clears itself in seconds.
 */
export type WorkerAnswer =
  | { readonly kind: "ok"; readonly page: ConnectorsPage }
  | { readonly kind: "stale"; readonly page: ConnectorsPage; readonly reason: string }
  | { readonly kind: "rebalancing"; readonly message: string }
  | { readonly kind: "failed"; readonly message: string; readonly code: string }
  | { readonly kind: "forbidden" }
  | { readonly kind: "not-configured" }
  /** The worker's section was not one this build could read. Never silently an empty page. */
  | { readonly kind: "unreadable"; readonly detail: string };

export interface ConnectorListing {
  /** In configuration order: the operator wrote the list, and reordering it hides entry two. */
  readonly workers: readonly WorkerRow[];
}

/**
 * The key the list response wraps its outer `Section` in.
 *
 * Written down once, exported, and checked against the service's own rendered document by
 * `wire.golden.test.ts`. `ALERTS_EVENT_NAME` being a hand-copied mirror of
 * `AlertChangeDto.EventName` is the standing example of what a second, uncompared copy of a wire
 * name costs.
 */
export const CONNECTORS_SECTION_KEY = "connectors";

/**
 * The reason code a rebalancing worker's section carries. See {@link WorkerAnswer}.
 *
 * `ReasonCodes` is generated from `kui.contracts.capability.ReasonCode` by
 * `./mill frontend.apiConstants`, so this is a *reference* and not a hand-copied literal — the
 * mistake the constant above names out loud. The alias exists so that the fold below reads as what
 * it is: `STARTING` is the reason code the connect service chose for a rebalance, out of eight, and
 * a reader needs the word "rebalancing" to find it.
 */
export const REBALANCING_REASON_CODE = ReasonCodes.Starting;

/** What {@link decodeConnectorListing} answers when the document is not one this build can read. */
export const Unreadable = Symbol("connector listing this build cannot read");
export type Unreadable = typeof Unreadable;

/**
 * The outer section's payload, as a listing — or {@link Unreadable}.
 *
 * The refusal is the point. `data.workers ?? []` is shorter and is the wave-5 defect verbatim: it
 * turns "this build read the wrong field name" into "this cluster has no Connect workers", and the
 * second sentence is one an operator acts on.
 */
export function decodeConnectorListing(payload: unknown): ConnectorListing | Unreadable {
  const root = asRecord(payload);
  if (root === undefined) return Unreadable;

  const workers = asArray(root["workers"]);
  if (workers === undefined) return Unreadable;

  return {
    workers: workers.map(decodeWorker).filter((row): row is WorkerRow => row !== undefined),
  };
}

/** One worker row, or `undefined` when the entry names no Connect cluster. */
function decodeWorker(entry: unknown): WorkerRow | undefined {
  const record = asRecord(entry);
  if (record === undefined) return undefined;
  const connect = asString(record["connect"]);
  if (connect === undefined) return undefined;
  return { connect, page: decodeWorkerSection(record["connectors"]) };
}

/**
 * One worker's inner `Section`, as the answer a row draws.
 *
 * `decodeSection` is `@kui/api`'s, so the five statuses and the `unreadable` case are read the same
 * way every other section in this product is read. What this function adds is the rebalance fold
 * and the refusal: a payload that carries no `items` array is `unreadable`, not an empty page.
 */
export function decodeWorkerSection(raw: unknown): WorkerAnswer {
  const section: Section<unknown> = decodeSection<unknown>(raw);

  switch (section.status) {
    case "ok":
    case "stale": {
      const page = decodePage(section.data);
      if (page === Unreadable) {
        return {
          kind: "unreadable",
          detail:
            "the worker's section carried no connector list this build recognises, so KUI cannot " +
            "say what it is running",
        };
      }
      return section.status === "ok"
        ? { kind: "ok", page }
        : {
            kind: "stale",
            page,
            reason:
              section.reason.message ??
              "This is the last answer KUI received from this worker.",
          };
    }
    case "unavailable":
      /* The transient case. See `WorkerAnswer`: three halves of one rule; this is the browser's. */
      return section.reason.code === REBALANCING_REASON_CODE
        ? {
            kind: "rebalancing",
            message:
              section.reason.message ??
              "This Connect cluster is rebalancing and cannot list its connectors yet.",
          }
        : {
            kind: "failed",
            message: section.reason.message ?? "This Connect cluster did not answer.",
            code: section.reason.code,
          };
    case "forbidden":
      return { kind: "forbidden" };
    case "not_configured":
      return { kind: "not-configured" };
    case "unreadable":
      return {
        kind: "unreadable",
        detail: section.reason.message ?? "the section could not be read",
      };
  }
}

/** One worker's page of connectors, or {@link Unreadable}. Never an empty page by default. */
export function decodePage(payload: unknown): ConnectorsPage | Unreadable {
  const root = asRecord(payload);
  if (root === undefined) return Unreadable;

  const items = asArray(root["items"]);
  if (items === undefined) return Unreadable;

  return {
    items: items.map(decodeConnector).filter((one): one is Connector => one !== undefined),
    /* A missing `unreadable` is an empty one and not a refusal: the service always renders it,
       and a document from an older build that omitted it is still a readable list. */
    unreadable: (asArray(root["unreadable"]) ?? [])
      .map((name) => asString(name))
      .filter((name): name is string => name !== undefined),
  };
}

/**
 * One connector, or `undefined` when the entry has no name.
 *
 * A nameless connector is dropped rather than drawn as "(unnamed)": the name is what makes a row
 * actionable — every control on it names the connector to the server — and a row nobody can act on
 * is furniture.
 */
function decodeConnector(entry: unknown): Connector | undefined {
  const record = asRecord(entry);
  if (record === undefined) return undefined;
  const name = asString(record["name"]);
  if (name === undefined) return undefined;

  const tasks = (asArray(record["tasks"]) ?? []).map(decodeTask);
  return {
    connect: asString(record["connect"]) ?? "",
    name,
    kind: asString(record["kind"]) ?? "",
    state: connectorState(record["state"]),
    workerId: nonBlank(asString(record["workerId"])),
    reason: nonBlank(asString(record["reason"])),
    /* Read, never derived. A browser that recomputed `failed` from `tasks` would be the third
       opinion `ConnectorDto`'s scaladoc exists to prevent — and it would disagree with the service
       about the connector whose own state is RUNNING and whose task 1 has failed. */
    failed: record["failed"] === true,
    runningTasks: asNumber(record["runningTasks"]) ?? 0,
    taskCount: asNumber(record["taskCount"]) ?? tasks.length,
    tasks,
  };
}

function decodeTask(entry: unknown): ConnectorTask {
  const record = asRecord(entry) ?? {};
  return {
    id: asNumber(record["id"]) ?? -1,
    state: connectorState(record["state"]),
    workerId: nonBlank(asString(record["workerId"])),
    // Blank is not a reason. A worker that sends `""` has told us nothing, and an empty reason
    // block under a failed connector reads as "KUI knows and will not say".
    reason: nonBlank(asString(record["reason"])),
    trace: nonBlank(asString(record["trace"])),
  };
}

/**
 * A state word, folded to one this build draws.
 *
 * Anything else becomes `UNKNOWN` rather than `RUNNING`. Connect has added states across releases —
 * `STOPPED` arrived in Kafka 3.5, which is why `ConnectorTaskDto.state` is a string and not an enum
 * — and guessing `RUNNING` for a word this build has never seen would be KUI telling somebody their
 * pipeline is fine on no evidence.
 */
export function connectorState(value: unknown): ConnectorRunState {
  switch (asString(value)?.toUpperCase()) {
    case "RUNNING":
      return "RUNNING";
    case "FAILED":
      return "FAILED";
    case "PAUSED":
      return "PAUSED";
    case "UNASSIGNED":
      return "UNASSIGNED";
    case "RESTARTING":
      return "RESTARTING";
    default:
      return "UNKNOWN";
  }
}

/* --- The four readers everything above is built from ------------------------------------------ */

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function asArray(value: unknown): readonly unknown[] | undefined {
  return Array.isArray(value) ? (value as readonly unknown[]) : undefined;
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

/** A number, and never `NaN` or an infinity — both of which format as words on a screen. */
function asNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function nonBlank(value: string | undefined): string | undefined {
  return value === undefined || value.trim() === "" ? undefined : value;
}
