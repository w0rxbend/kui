/**
 * Writing a record into a topic.
 *
 * ## The distinction this file exists to keep
 *
 * `ProduceRequestDto` says it plainly: *an absent value is a tombstone, not an empty one.* On a
 * compacted topic a record with a null value is an instruction to delete the key, permanently, and a
 * record with an empty-string value is an ordinary record that happens to carry no characters. The
 * browser has one text box for both, so somewhere the difference has to be made explicit — it is
 * made here, by a caller passing `null` rather than `""`, and by the drawer having a separate
 * control for it rather than inferring it from an empty field. Inferring it would mean an operator
 * who cleared the box to start again could delete a key by pressing Backspace.
 *
 * The same applies per header: a header may have a null value, which is not an empty one.
 */
import type { ApiResult, KuiApiClient } from "@kui/api";

/** One header to write. `value: null` is a header explicitly present with no value. */
export interface HeaderDraft {
  readonly name: string;
  readonly value: string | null;
}

export interface RecordDraft {
  /** `null` lets the partitioner choose, which is what the key is for. */
  readonly partition: number | null;
  /** `null` is a record with no key at all. */
  readonly key: string | null;
  /** `null` is a **tombstone**. `""` is an empty value. They are different records. */
  readonly value: string | null;
  readonly headers: readonly HeaderDraft[];
  readonly keySerde: string | null;
  readonly valueSerde: string | null;
  /**
   * How many copies to write.
   *
   * One, almost always. More exists for filling a topic while testing a consumer, and the drawer
   * says so — a field that quietly defaults to something other than 1 on a screen that writes to a
   * production cluster would be indefensible.
   */
  readonly count: number;
}

export const EMPTY_RECORD_DRAFT: RecordDraft = {
  partition: null,
  key: null,
  value: "",
  headers: [],
  keySerde: null,
  valueSerde: null,
  count: 1,
};

/** Where one written record landed. The broker's acknowledgement, not the request echoed back. */
export interface ProducedRecord {
  readonly partition: number;
  readonly offset: number;
  readonly timestamp: string;
}

export async function produce(
  api: KuiApiClient,
  clusterId: string,
  topicName: string,
  draft: RecordDraft,
): Promise<ApiResult<readonly ProducedRecord[]>> {
  const answer = await api.post("/api/v1/clusters/{clusterId}/topics/{topicName}/messages", {
    params: { path: { clusterId, topicName } },
    body: {
      count: draft.count,
      /*
       * Every optional field is *omitted* when it is null rather than sent as null.
       *
       * That is the encoding the contract asks for — an absent `value` is the tombstone — and it is
       * also why these cannot be written as `value: draft.value ?? undefined`: with
       * `exactOptionalPropertyTypes` that is a different type, and more importantly it reads as
       * though absent and null were interchangeable here, which is the one thing they are not.
       */
      ...(draft.partition === null ? {} : { partition: draft.partition }),
      ...(draft.key === null ? {} : { key: draft.key }),
      ...(draft.value === null ? {} : { value: draft.value }),
      ...(draft.keySerde === null ? {} : { keySerde: draft.keySerde }),
      ...(draft.valueSerde === null ? {} : { valueSerde: draft.valueSerde }),
      ...(draft.headers.length === 0
        ? {}
        : {
            headers: draft.headers.map((header) =>
              header.value === null
                ? { name: header.name }
                : { name: header.name, value: header.value },
            ),
          }),
    },
  });
  if (!answer.ok) return answer;
  return { ok: true, value: [...(answer.value.records ?? [])] };
}

/** Why this draft cannot be sent, or `undefined`. */
export function draftProblem(draft: RecordDraft): string | undefined {
  if (!Number.isInteger(draft.count) || draft.count < 1) {
    return "Write at least one record.";
  }
  if (draft.partition !== null && (!Number.isInteger(draft.partition) || draft.partition < 0)) {
    return "A partition number is a whole number from 0, or leave it empty to let Kafka choose.";
  }
  // A header with no name is not a header. An empty *value* is fine, and so is a null one.
  if (draft.headers.some((header) => header.name.trim() === "")) {
    return "Every header needs a name.";
  }
  return undefined;
}
