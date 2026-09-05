/**
 * Creating a topic.
 *
 * ## The two fields that are not numbers
 *
 * Partitions and replication factor look like plain numeric inputs and are not. Both are
 * *decisions*, both are effectively permanent, and the form's job is to say so before the operator
 * commits rather than after:
 *
 * - **Partitions can be added later and never removed.** Adding them also changes which partition a
 *   given key hashes to, so any consumer relying on per-key ordering sees that ordering break at the
 *   moment of the change. Starting too low is recoverable at a cost; starting too high is not
 *   recoverable at all.
 * - **Replication factor cannot be changed here at all.** Kafka changes it through a partition
 *   reassignment, which this product does not do. A topic created with one replica has no redundancy
 *   for the rest of its life, and a broker restart takes it offline.
 *
 * Neither field is required. Leaving one empty means "the broker's own default", which is a better
 * answer than a number this form invented — and is why the placeholder says so rather than
 * pre-filling `1`, which would look like a considered choice made on the operator's behalf.
 *
 * ## Validating the name here as well as on the server
 *
 * The server validates it and its rejection is authoritative. This form checks the same rule anyway,
 * because a round trip that comes back "must not be '.' or '..'" after the operator has moved on is
 * a worse way to learn it than being told while typing. The two must agree; the rule is stated once
 * here, quoting the contract's own wording.
 */
import { Show, createMemo, createSignal, createUniqueId } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Banner, Button, Dialog, TextField, type Mutation } from "@kui/kernel";
import type { CreatedTopic, NewTopic } from "./write.js";

export interface CreateTopicDialogProps {
  readonly open: boolean;
  readonly onClose: () => void;
  /** Runs the create. The dialog stays open until `state` says otherwise. */
  readonly onCreate: (topic: NewTopic) => void;
  /** The mutation's state, owned by the screen so a success can navigate to the new topic. */
  readonly state: Mutation<CreatedTopic>;
  /** Names already on the cluster. A local clash is caught before a round trip. */
  readonly existingNames: readonly string[];
}

/**
 * Kafka's own rule, quoted from the contract: 1-249 characters from `[a-zA-Z0-9._-]`, and not `.`
 * or `..`. The server enforces it; this is the same rule stated where the operator is typing.
 */
const NAME_PATTERN = /^[a-zA-Z0-9._-]{1,249}$/;

/** Why this name cannot be used, or `undefined`. */
export function nameProblem(name: string, existing: readonly string[]): string | undefined {
  if (name === "") return undefined; // Not yet typed is not yet wrong.
  if (name === "." || name === "..") {
    return "Kafka reserves “.” and “..” and will refuse them.";
  }
  if (!NAME_PATTERN.test(name)) {
    return "A topic name is 1 to 249 characters, and only letters, digits, dot, underscore and hyphen.";
  }
  if (existing.includes(name)) {
    return "This cluster already has a topic with that name.";
  }
  /*
   * A warning rather than a refusal, and deliberately last so a genuine error wins.
   *
   * Kafka's metrics collapse `.` and `_` to the same character, so `a.b` and `a_b` produce colliding
   * metric names and one topic's figures silently land on the other's graph. Kafka itself only warns,
   * so refusing here would be this product inventing a rule the cluster does not have.
   */
  if (name.includes(".") && name.includes("_")) {
    return "Kafka's metrics treat “.” and “_” as the same character, so this name can collide with another topic's metrics.";
  }
  return undefined;
}

/** The text of a numeric field, as a number — or `undefined` for empty, which means the default. */
function count(text: string): number | undefined {
  if (text.trim() === "") return undefined;
  const value = Number(text);
  // `Number("")` is 0 and `Number("2x")` is NaN; neither is a count. An unparseable value becomes
  // `undefined`, which sends the broker's default rather than a number nobody chose.
  return Number.isInteger(value) && value > 0 ? value : undefined;
}

export function CreateTopicDialog(props: CreateTopicDialogProps): JSX.Element {
  const [name, setName] = createSignal("");
  const [partitions, setPartitions] = createSignal("");
  const [replication, setReplication] = createSignal("");

  const problem = createMemo(() => nameProblem(name(), props.existingNames));
  /** The metric-collision note is a warning, not a refusal — it must not disable the button. */
  const blocking = () => problem() !== undefined && !problem()!.startsWith("Kafka's metrics");
  const busy = () => props.state.kind === "running";
  const canCreate = () => name() !== "" && !blocking() && !busy();

  const hintId = createUniqueId();

  /** Why the create button is unavailable. Never empty: `Button`'s type refuses that. */
  const blockedReason = (): string => {
    if (busy()) return "The topic is being created.";
    if (name() === "") return "Give the topic a name first.";
    return problem() ?? "This name cannot be used.";
  };

  const submit = (): void => {
    if (!canCreate()) return;
    props.onCreate({
      name: name(),
      partitions: count(partitions()),
      replicationFactor: count(replication()),
      // No configuration keys from this form. A topic's retention and cleanup policy are edited on
      // the topic page, where the current values are visible; asking for them at create time means
      // typing settings blind.
      config: {},
    });
  };

  return (
    <Dialog
      open={props.open}
      onClose={props.onClose}
      title="Create a topic"
      description="Partitions can be added later but never removed, and replication factor cannot be changed here at all."
      size="md"
      /* The operator has typed a name and two numbers. A stray click on the veil must not discard
         them — the only way out is Cancel or Escape, both of which are deliberate. */
      closeOnScrimClick={false}
      testId="create-topic-dialog"
      actions={
        <>
          <Show
            when={busy()}
            fallback={
              <Button variant="ghost" onClick={props.onClose}>
                Cancel
              </Button>
            }
          >
            <Button variant="ghost" disabled disabledReason="The topic is being created.">
              Cancel
            </Button>
          </Show>
          {/* Two branches rather than `disabled={!canCreate()}`, because `Button` makes
              `disabledReason` mandatory exactly when `disabled` is true and enforces it in the
              type. That is the right rule — a control the operator cannot use owes them a sentence
              saying why — and satisfying it costs one `Show`. */}
          <Show
            when={canCreate()}
            fallback={
              <Button
                variant="primary"
                icon="plus"
                busy={busy()}
                disabled
                disabledReason={blockedReason()}
              >
                Create topic
              </Button>
            }
          >
            <Button variant="primary" icon="plus" onClick={submit}>
              Create topic
            </Button>
          </Show>
        </>
      }
    >
      <div class="kui-create-topic">
        <TextField
          label="Name"
          value={name()}
          onInput={setName}
          placeholder="orders.payments.v2"
          required
          mono
          error={blocking() ? problem() : undefined}
          help={blocking() ? undefined : problem()}
          hintKey={hintId}
        />

        <div class="kui-create-topic__numbers">
          <TextField
            label="Partitions"
            type="number"
            value={partitions()}
            onInput={setPartitions}
            /* Not pre-filled. An empty box that says what empty means is honest; a box containing
               "1" looks like a recommendation this form is in no position to make. */
            placeholder="broker default"
            help="More partitions means more parallel consumers. They can be added later, but adding them changes which partition a key lands in, which breaks per-key ordering for anything relying on it."
          />
          <TextField
            label="Replication factor"
            type="number"
            value={replication()}
            onInput={setReplication}
            placeholder="broker default"
            help="How many brokers hold a copy. This cannot be changed afterwards from KUI: a topic created with one replica stays without redundancy."
          />
        </div>

        <Show when={props.state.kind === "failed" || props.state.kind === "forbidden"}>
          {/* The form keeps everything the operator typed. A dialog that closes on failure loses
              the work and leaves them nothing to correct. */}
          <Banner
            tone="danger"
            message={
              props.state.kind === "failed" || props.state.kind === "forbidden"
                ? props.state.message
                : ""
            }
            code={props.state.kind === "failed" ? props.state.code : undefined}
          />
        </Show>
      </div>
    </Dialog>
  );
}
