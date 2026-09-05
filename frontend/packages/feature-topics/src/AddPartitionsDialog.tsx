/**
 * Choosing how many partitions to grow a topic to.
 *
 * ## Why this is a step in front of `PlannedActionDialog` rather than part of it
 *
 * Every other planned action in this feature is planned the moment its dialog opens, because there
 * is nothing to ask: purging a topic and deleting a topic each have exactly one shape. A partition
 * increase has a parameter, and the server cannot compute a plan without it — so somebody has to
 * name a number first, and only then is there something to plan.
 *
 * Splitting it that way keeps `PlannedActionDialog`'s central property intact: the plan is fetched
 * on open, the token fixes the agreement, and the confirmation the operator reads is the one the
 * token applies. A dialog that re-planned as the operator typed would show a warning about growing
 * to eight while holding a token for twelve.
 *
 * ## The number is not pre-filled with anything clever
 *
 * It starts at one more than the current count, which is the smallest legal answer and therefore
 * the least opinionated. Doubling is the folklore, and this form is in no position to recommend it:
 * the right number depends on the consumer group's size and the throughput, and neither is on this
 * screen.
 *
 * ## The consequence appears here, before the plan
 *
 * The key-routing warning is on the confirmation too, in the server's own words, and it is repeated
 * here in shorter form for a reason: an operator who learns that per-key ordering breaks *after*
 * they have picked a number has already decided. The point of saying it at the point of choice is
 * that it can change the choice, which is what "add partitions" needs and "purge" does not.
 */
import { Show, createEffect, createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Banner, Button, Dialog, TextField } from "@kui/kernel";

export interface AddPartitionsDialogProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly topicName: string;
  /**
   * What the topic has now, as the page last read it.
   *
   * `undefined` when the page has not been able to describe the topic. The form still works — the
   * server refuses an illegal target with the count it actually holds — but it cannot check the
   * number locally or say what the increase would be, and it says so instead of guessing.
   */
  readonly current: number | undefined;
  /** Hands the chosen target on to the plan-and-confirm step. */
  readonly onContinue: (target: number) => void;
}

/**
 * Why this target cannot be used, or `undefined` when it can.
 *
 * Local and advisory. The server checks the same thing against a freshly read count and its answer
 * is the authority — this only spares the operator a round trip for a number that is obviously
 * wrong, and it deliberately declines to judge at all when the current count is not known.
 */
export function targetProblem(typed: string, current: number | undefined): string | undefined {
  const trimmed = typed.trim();
  if (trimmed === "") return "Enter the number of partitions the topic should end up with.";
  if (!/^\d+$/.test(trimmed)) return "Partitions are a whole number.";
  const target = Number(trimmed);
  if (target < 1) return "A topic has at least one partition.";
  if (current === undefined) return undefined;
  if (target <= current) {
    /* Kafka's own limitation, stated as such rather than as a rule of KUI's. An operator who reads
       "KUI does not allow this" goes looking for a command line that does; there isn't one. */
    return `This topic already has ${current} partitions, and Kafka cannot remove one — a partition count can only be raised.`;
  }
  return undefined;
}

export function AddPartitionsDialog(props: AddPartitionsDialogProps): JSX.Element {
  const [typed, setTyped] = createSignal("");

  /*
   * Reset on open, not on close. A dialog that cleared itself as it closed would visibly blank the
   * field during the closing animation, and reopening is the only moment the starting value can be
   * recomputed from a count that may have changed since last time.
   */
  createEffect(
    () => props.open,
    (open) => {
      if (open) setTyped(props.current === undefined ? "" : String(props.current + 1));
    },
  );

  const problem = (): string | undefined => targetProblem(typed(), props.current);
  const target = (): number => Number(typed().trim());
  const added = (): number | undefined =>
    props.current === undefined || problem() !== undefined ? undefined : target() - props.current;

  return (
    <Dialog
      open={props.open}
      onClose={props.onClose}
      title={`Add partitions to ${props.topicName}`}
      size="sm"
      testId="add-partitions"
      /* The field holds something the operator typed, so a stray click on the veil must not throw
         it away. Cancel and Escape both still close it. */
      closeOnScrimClick={false}
      actions={
        <>
          <Button variant="ghost" onClick={props.onClose}>
            Cancel
          </Button>
          {/* Two branches rather than `disabled={problem() !== undefined}`: `Button`'s type demands
              a reason with every disabled state, and the reason here is the validation message. */}
          <Show
            when={problem()}
            fallback={
              <Button variant="primary" icon="plus" onClick={() => props.onContinue(target())}>
                Continue
              </Button>
            }
          >
            {(reason) => (
              <Button variant="primary" icon="plus" disabled disabledReason={reason()}>
                Continue
              </Button>
            )}
          </Show>
        </>
      }
    >
      <div class="kui-add-partitions">
        <Show
          when={props.current !== undefined}
          fallback={
            <Banner
              tone="warning"
              message="KUI could not read this topic's current partition count, so the number below cannot be checked here. The cluster will refuse a target that is not greater than what the topic actually has."
            />
          }
        >
          <p class="kui-add-partitions__current">This topic has {props.current} partitions.</p>
        </Show>

        <TextField
          label="Partitions after the change"
          type="number"
          value={typed()}
          onInput={setTyped}
          /* The message appears as an error only once something has been typed. An empty field on a
             dialog that has just opened is not a mistake anybody has made yet, and marking it red
             before the operator has touched it is the form telling them off for arriving. */
          {...(problem() === undefined || typed().trim() === "" ? {} : { error: problem() })}
          help={
            added() === undefined
              ? "The total the topic should end up with, not the number to add."
              : `The total the topic should end up with. That adds ${added()} ${added() === 1 ? "partition" : "partitions"}.`
          }
        />

        <Banner
          tone="warning"
          message="Kafka routes a keyed record by hash(key) % partitions, so raising the count sends most keys to a different partition from the records already stored under them. Anything relying on per-key ordering breaks across the change, and it cannot be undone — Kafka has no way to remove a partition."
        />
      </div>
    </Dialog>
  );
}
