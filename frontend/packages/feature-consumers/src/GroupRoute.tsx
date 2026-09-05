/**
 * One consumer group: the page, the offset-reset wizard and the delete confirmation.
 *
 * `GroupDetail` and `ResetWizard` were both fully built and neither had a route. Every link the
 * group list produced — `/clusters/:clusterId/consumer-groups/:groupId` — resolved to the list
 * again, so a wizard that knows about clamping, no-op plans and expired tokens was unreachable from
 * the product.
 */
import { Show, createEffect, createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import { useParams } from "@solidjs/router";
import { Actions } from "@kui/api";
import { ConfirmDialog, createMutation, useKui, valueOf, type Fetched } from "@kui/kernel";
import { GroupDetail as GroupDetailPage } from "./GroupDetail.jsx";
import { fetchGroup } from "./data.js";
import { applyReset, deleteGroup, planReset } from "./write.js";
import type { GroupDetail } from "./detail.js";

export function GroupRoute(): JSX.Element {
  const params = useParams<{ readonly clusterId?: string; readonly groupId?: string }>();
  return (
    <Show when={params.clusterId} fallback={<NoSubject what="cluster" />}>
      {(clusterId) => (
        <Show when={params.groupId} fallback={<NoSubject what="group" />}>
          {(groupId) => <GroupScreen clusterId={clusterId()} groupId={groupId()} />}
        </Show>
      )}
    </Show>
  );
}

function NoSubject(props: { readonly what: string }): JSX.Element {
  const kui = useKui();
  return (
    <section aria-label="Consumer group">
      <p role="status">
        This address names no {props.what}.{" "}
        <a href={kui.paths.clusters()}>Start from the cluster list</a>.
      </p>
    </section>
  );
}

function GroupScreen(props: { readonly clusterId: string; readonly groupId: string }): JSX.Element {
  const kui = useKui();
  const [state, setState] = createSignal<Fetched<GroupDetail>>({ kind: "loading" });
  const [attempt, setAttempt] = createSignal(0);
  const [confirmingDelete, setConfirmingDelete] = createSignal(false);

  createEffect(
    () => [props.clusterId, props.groupId, attempt()] as const,
    () => {
      let cancelled = false;
      setState({ kind: "loading" });
      void fetchGroup(kui.api, props.clusterId, props.groupId).then((next) => {
        // Switching group while a request is out must not land the old group's offsets on the new
        // group's page: real figures for the wrong subject is the most convincing wrong data there
        // is, and this page's figures are what a reset is composed from.
        if (!cancelled) setState(() => next);
      });
      return () => {
        cancelled = true;
      };
    },
  );

  createEffect(
    () => state(),
    (current) => {
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
    },
  );

  const remove = createMutation(() => deleteGroup(kui.api, props.clusterId, props.groupId));

  const mayReset = () => kui.permits(Actions.ConsumerGroupResetOffsets, props.groupId);
  const mayDelete = () => kui.permits(Actions.ConsumerGroupDelete, props.groupId);

  const group = () => (state().kind === "loading" ? undefined : valueOf(state(), undefined));

  return (
    <Show
      when={group()}
      fallback={<Loading state={state()} onRetry={() => setAttempt(attempt() + 1)} />}
    >
      {(detail) => (
        <>
          <GroupDetailPage
            group={detail()}
            listHref={kui.paths.consumerGroups(props.clusterId)}
            reset={{
              plan: (request) => planReset(kui.api, props.clusterId, props.groupId, request),
              apply: async (token) => {
                const outcome = await applyReset(kui.api, props.clusterId, props.groupId, token);
                // The group's committed offsets have just moved. Everything on the page behind the
                // wizard — the lag, the per-partition positions — now describes the state before the
                // reset, which is the one state it must not be showing.
                if (outcome.ok) setAttempt(attempt() + 1);
                return outcome;
              },
              permitted: mayReset(),
              refusal: mayReset()
                ? undefined
                : "You do not have permission to reset this group's offsets.",
            }}
            /* Offered only where it is permitted. `GroupDetail` handles the other refusal itself —
               a group with members cannot be deleted, and it says so before the click rather than
               after, because that is the one refusal an operator can act on directly. */
            onDelete={mayDelete() ? () => setConfirmingDelete(true) : undefined}
            deleteRefusal={
              mayDelete() ? undefined : "You do not have permission to delete this consumer group."
            }
          />

          <ConfirmDialog
            open={confirmingDelete()}
            onClose={() => setConfirmingDelete(false)}
            title={`Delete ${props.groupId}?`}
            consequence={consequenceOfDelete(detail())}
            confirmLabel="Delete group"
            confirmIcon="trash"
            /* No type-to-confirm. Unlike a topic, deleting an empty group destroys no records —
               the group's committed offsets go, and a consumer that starts up again follows its own
               auto.offset.reset. Demanding a typed name for everything is how operators learn to
               type names without reading them. */
            busy={remove.busy()}
            error={deleteError(remove.state())}
            onConfirm={() => {
              void remove.run().then((outcome) => {
                if (outcome.kind !== "done") return;
                setConfirmingDelete(false);
                window.location.assign(kui.paths.consumerGroups(props.clusterId));
              });
            }}
          />
        </>
      )}
    </Show>
  );
}

/** What deleting this group actually costs, in this group's own figures. */
export function consequenceOfDelete(group: GroupDetail): string {
  const partitions = group.offsets.length;
  const where = partitions === 1 ? "1 partition" : `${partitions} partitions`;
  return (
    `Removes the group and its committed offsets on ${where}. ` +
    // The part that decides whether this is safe, and the part an operator forgets: the data is
    // untouched, but any consumer that comes back under this group id starts from wherever its own
    // auto.offset.reset says, which for the default is the end of the log.
    "No records are deleted. A consumer that starts up again under this group id follows its own " +
    "auto.offset.reset, which by default means it begins at the end of the log and skips everything " +
    "currently in it."
  );
}

function deleteError(
  state: ReturnType<ReturnType<typeof createMutation<[], unknown>>["state"]>,
): { readonly message: string; readonly code?: string | undefined } | undefined {
  if (state.kind === "forbidden") return { message: state.message, code: "KUI-FORBIDDEN" };
  if (state.kind === "failed") return { message: state.message, code: state.code };
  return undefined;
}

function Loading(props: {
  readonly state: Fetched<GroupDetail>;
  readonly onRetry: () => void;
}): JSX.Element {
  return (
    <section aria-label="Consumer group">
      <Show when={props.state.kind === "loading"}>
        <p role="status">Asking the coordinator about this group…</p>
      </Show>
      <Show when={props.state.kind === "forbidden"}>
        <p role="status">You do not have permission to see this consumer group.</p>
      </Show>
      <Show when={props.state.kind === "failed"}>
        <p role="alert">
          {props.state.kind === "failed" ? props.state.message : ""}{" "}
          <button type="button" onClick={props.onRetry}>
            Try again
          </button>
        </p>
      </Show>
    </section>
  );
}

export default GroupRoute;
