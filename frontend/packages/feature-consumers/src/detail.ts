/**
 * The shapes the group-detail screen and the offset-reset wizard draw, and the arithmetic that is
 * about one value rather than one layout.
 *
 * Same rule as `model.ts`: plain data and plain functions, so the parts that are wrong in ways a
 * screenshot cannot show are the parts a test can reach without a DOM.
 */

import type { GroupState } from "./model.js";

/** One consumer in the group, with what it holds. */
export interface Member {
  readonly memberId: string;
  readonly clientId: string;
  readonly host: string;
  /** A static member's id, when the group uses static membership. `null` otherwise, and that is
   *  a fact about the group rather than a value KUI failed to read. */
  readonly groupInstanceId: string | null;
  /** `topic-3` and so on, as the coordinator reports them. */
  readonly partitions: readonly string[];
  readonly rebalancing: boolean;
}

/** One partition's position, as the group has committed it. */
export interface PartitionOffset {
  readonly topic: string;
  readonly partition: number;
  /** `null` where the group has never committed on this partition — not a committed offset of 0. */
  readonly committed: number | null;
  /** `null` where the end offset could not be read; the lag is then unknowable, not zero. */
  readonly endOffset: number | null;
  /** The member holding this partition, or `null` when nobody does. */
  readonly memberId: string | null;
}

/**
 * The lag of one partition, or `null` when it cannot be computed.
 *
 * Both inputs have to be present. An end offset with no committed offset is a partition the group
 * has never read, which is not a lag of the whole partition and not a lag of nothing — it is a
 * question KUI cannot answer, and the screen prints an em dash for it.
 *
 * The result is floored at zero. A committed offset ahead of the end offset happens: the end offset
 * is read a moment after the commit, and on an idle partition the two can cross. A negative lag on
 * screen reads as a bug in KUI rather than as a race in Kafka.
 */
export function partitionLag(row: PartitionOffset): number | null {
  if (row.committed === null || row.endOffset === null) return null;
  return Math.max(0, row.endOffset - row.committed);
}

export interface GroupDetail {
  readonly groupId: string;
  readonly state: GroupState | null;
  readonly coordinator: string | null;
  /** Which assignor the group negotiated: `range`, `cooperative-sticky`. */
  readonly partitionAssignor: string;
  /** `CLASSIC`, `CONSUMER` or `UNKNOWN` — which group protocol the members speak. */
  readonly protocol: string;
  readonly isSimple: boolean;
  readonly totalLag: number | null;
  /** Records per second the group is committing. Negative means offsets moved backwards. */
  readonly pace: number | null;
  readonly members: readonly Member[];
  readonly offsets: readonly PartitionOffset[];
  readonly excludedPartitions: number;
  /** When the coordinator was asked. Drives the freshness line, not a spinner. */
  readonly observedAt: Date;
}

/** The topics this group holds offsets on, and their partitions — what the wizard may reset. */
export function subscriptions(detail: GroupDetail): readonly { readonly topic: string; readonly partitions: readonly number[] }[] {
  const byTopic = new Map<string, number[]>();
  for (const offset of detail.offsets) {
    const existing = byTopic.get(offset.topic);
    if (existing === undefined) byTopic.set(offset.topic, [offset.partition]);
    else existing.push(offset.partition);
  }
  return [...byTopic.entries()]
    .map(([topic, partitions]) => ({ topic, partitions: [...partitions].sort((a, b) => a - b) }))
    .sort((a, b) => a.topic.localeCompare(b.topic));
}

/* ------------------------------------------------------------------------------------------ */
/* The offset-reset plan                                                                        */
/* ------------------------------------------------------------------------------------------ */

/**
 * Where a reset moves to. The labels are sentences a person reads, not the protocol's words:
 * `EARLIEST` is a fact about Kafka, and "the beginning of each partition" is what the operator is
 * choosing.
 */
export type ResetTarget = "EARLIEST" | "LATEST" | "OFFSET" | "TIMESTAMP" | "SHIFT_BY" | "DURATION";

export interface ResetTargetOption {
  readonly value: ResetTarget;
  readonly label: string;
  /** Which extra field this target needs, if any. Nothing else decides that. */
  readonly parameter: "offset" | "timestamp" | "shiftBy" | "durationMinutes" | null;
  /** Shown under the field. Says what the number means, not how to type it. */
  readonly hint?: string;
}

export const RESET_TARGETS: readonly ResetTargetOption[] = [
  { value: "EARLIEST", label: "The beginning of each partition", parameter: null },
  { value: "LATEST", label: "The end of each partition", parameter: null },
  { value: "OFFSET", label: "A specific offset", parameter: "offset", hint: "The same offset on every partition. Clamped to each partition's range." },
  {
    value: "TIMESTAMP",
    label: "The first record at or after a time",
    parameter: "timestamp",
    // KIP-122's rule, spelled out, because it is the surprise that makes a preview necessary.
    hint: "A partition with no record at or after that time moves to its end, not to its beginning.",
  },
  { value: "SHIFT_BY", label: "Forwards or backwards by a number of records", parameter: "shiftBy", hint: "Negative rewinds. Positive skips." },
  { value: "DURATION", label: "Back by a length of time", parameter: "durationMinutes", hint: "In minutes, counted back from now." },
];

export function targetOption(target: ResetTarget): ResetTargetOption {
  const found = RESET_TARGETS.find((option) => option.value === target);
  /* Exhaustive by construction — every member of the union is in the array above — but the array
   * is data and a future edit could drop one, so the failure is loud rather than `undefined`
   * reaching the screen as a blank form. */
  if (found === undefined) throw new Error(`No reset target option for ${target}`);
  return found;
}

/** What the wizard is about to send. Built in one place so the form and the request cannot differ. */
export interface ResetRequest {
  readonly topic: string;
  readonly target: ResetTarget;
  readonly partitions: readonly number[];
  readonly offset?: number;
  readonly timestamp?: string;
  readonly shiftBy?: number;
  readonly durationMinutes?: number;
}

export interface ResetForm {
  readonly topic: string;
  readonly target: ResetTarget;
  readonly offset: string;
  readonly timestamp: string;
  readonly shiftBy: string;
  readonly durationMinutes: string;
}

export const EMPTY_RESET_FORM: ResetForm = {
  topic: "",
  target: "EARLIEST",
  offset: "",
  timestamp: "",
  shiftBy: "",
  durationMinutes: "",
};

/**
 * Turns the form into a request, or into the one sentence that says what is missing.
 *
 * `Either`-shaped rather than throwing, and validated here rather than in the component, because
 * "the preview button did nothing" is the defect this whole file exists to prevent: a click that
 * neither advances nor explains is indistinguishable from a broken button, and the only way to be
 * sure every refusal produces a sentence is to make the refusal a value.
 */
export function resetRequestOf(form: ResetForm, partitions: readonly number[]): { readonly ok: true; readonly request: ResetRequest } | { readonly ok: false; readonly problem: string } {
  if (form.topic.trim() === "") return { ok: false, problem: "Choose a topic to reset." };
  if (partitions.length === 0) return { ok: false, problem: "That topic has no partitions this group holds offsets on." };

  const base = { topic: form.topic, target: form.target, partitions } as const;
  const parameter = targetOption(form.target).parameter;

  if (parameter === "offset") {
    const offset = wholeNumber(form.offset);
    if (offset === null || offset < 0) return { ok: false, problem: "Enter an offset: a whole number, zero or greater." };
    return { ok: true, request: { ...base, offset } };
  }
  if (parameter === "shiftBy") {
    const shiftBy = wholeNumber(form.shiftBy);
    if (shiftBy === null) return { ok: false, problem: "Enter how many records to move by. Negative rewinds, positive skips." };
    if (shiftBy === 0) return { ok: false, problem: "A shift of zero moves nothing. Choose a different number, or a different target." };
    return { ok: true, request: { ...base, shiftBy } };
  }
  if (parameter === "durationMinutes") {
    const minutes = wholeNumber(form.durationMinutes);
    if (minutes === null || minutes <= 0) return { ok: false, problem: "Enter how many minutes to go back. A whole number, greater than zero." };
    return { ok: true, request: { ...base, durationMinutes: minutes } };
  }
  if (parameter === "timestamp") {
    if (form.timestamp.trim() === "") return { ok: false, problem: "Choose the date and time to move to." };
    const parsed = Date.parse(form.timestamp);
    if (Number.isNaN(parsed)) return { ok: false, problem: "That is not a date and time KUI can read." };
    return { ok: true, request: { ...base, timestamp: new Date(parsed).toISOString() } };
  }
  return { ok: true, request: base };
}

function wholeNumber(raw: string): number | null {
  const trimmed = raw.trim();
  if (trimmed === "") return null;
  if (!/^[+-]?\d+$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isSafeInteger(value) ? value : null;
}

/** One partition of a plan: where it is now, where it would go, and by how much. */
export interface PlannedPartition {
  readonly partition: number;
  /** `null` where the group has never committed here. Never rendered as 0. */
  readonly current: number | null;
  readonly proposed: number;
  /** `null` when there is no `current` to subtract from. */
  readonly delta: number | null;
}

export interface PlanWarning {
  readonly kind: string;
  readonly message: string;
}

/**
 * What the broker says would happen. The plan is a document the operator reads, and the token is
 * the only thing the apply call sends.
 */
export interface ResetPlan {
  readonly token: string;
  readonly topic: string;
  readonly partitions: readonly PlannedPartition[];
  readonly warnings: readonly PlanWarning[];
  /** Every partition already where the reset would put it. There is then nothing to confirm. */
  readonly noOp: boolean;
  readonly expiresAt: Date;
}

/** How many records the plan moves, in total, ignoring direction. For the plan's one-line summary. */
export function recordsMoved(plan: ResetPlan): number {
  return plan.partitions.reduce((sum, one) => sum + Math.abs(one.delta ?? 0), 0);
}
