/**
 * The schema feature's route entry.
 *
 *   /clusters/:clusterId/schemas                          the subjects
 *   /clusters/:clusterId/schemas/:subject                 one subject, latest version
 *   /clusters/:clusterId/schemas/:subject?version=3       one version of it
 */
import { Show, createEffect, createMemo, createSignal, onCleanup } from "solid-js";
import type { JSX } from "@solidjs/web";
import { useLocation, useParams } from "@solidjs/router";
import { Actions } from "@kui/api";
import { createMutation, useKui, valueOf, writeBlockedReason, type Fetched } from "@kui/kernel";
import { SubjectList } from "./SubjectList.jsx";
import { SubjectPage } from "./SubjectPage.jsx";
import {
  checkCompatibility,
  fetchGlobalCompatibility,
  fetchSchema,
  fetchSubjectCompatibility,
  fetchSubjects,
  fetchVersions,
  setCompatibility,
  type Compatibility,
  type CompatibilityLevel,
  type ProposedSchema,
  type SchemaVersion,
  type SubjectListResult,
} from "./data.js";

export default function Schemas(): JSX.Element {
  const params = useParams<{ readonly clusterId?: string; readonly subject?: string }>();
  return (
    <Show when={params.clusterId} fallback={<NoCluster />}>
      {(clusterId) => (
        <Show when={params.subject} fallback={<SubjectsScreen clusterId={clusterId()} />}>
          {(subject) => <SubjectScreen clusterId={clusterId()} subject={subject()} />}
        </Show>
      )}
    </Show>
  );
}

function NoCluster(): JSX.Element {
  const kui = useKui();
  return (
    <section aria-label="Schema registry">
      <p role="status">
        No cluster is selected, so there is no registry to read.{" "}
        <a href={kui.paths.clusters()}>Choose a cluster</a> and try again.
      </p>
    </section>
  );
}

/** Fetch, hold, re-fetch. The fifth copy; all five want the kernel's `QueryCache`. */
function useFetch<T>(
  load: () => Promise<Fetched<T>>,
  deps: () => unknown,
): { readonly state: () => Fetched<T>; readonly reload: () => void } {
  const [state, setState] = createSignal<Fetched<T>>({ kind: "loading" });
  const [attempt, setAttempt] = createSignal(0);

  createEffect(
    () => [deps(), attempt()] as const,
    () => {
      let cancelled = false;
      setState({ kind: "loading" });
      void load().then((next) => {
        if (!cancelled) setState(() => next);
      });
      return () => {
        cancelled = true;
      };
    },
  );

  return { state, reload: () => setAttempt(attempt() + 1) };
}

/**
 * A failure in the screen's own words.
 *
 * The registry is the one upstream in this product that is routinely *absent* rather than broken —
 * plenty of clusters have none — so "not configured" is a different sentence from "not answering",
 * and neither is an empty subject list.
 */
function failureOf(state: Fetched<unknown>): { message: string; code?: string } | undefined {
  switch (state.kind) {
    case "failed":
      return { message: state.message, code: state.code };
    case "forbidden":
      return { message: "You do not have permission to read this cluster's schema registry." };
    case "not-configured":
      return {
        message: "This cluster has no schema registry configured, so there are no subjects to list.",
      };
    default:
      return undefined;
  }
}

function SubjectsScreen(props: { readonly clusterId: string }): JSX.Element {
  const kui = useKui();
  const [search, setSearch] = createSignal("");
  const [typed, setTyped] = createSignal("");
  const [page, setPage] = createSignal(1);
  const pageSize = 50;

  // The registry searches and pages; the browser only decides when to ask. Same rule as the topic
  // list, and for the same reason — a registry with four thousand subjects is not unusual.
  let timer: ReturnType<typeof setTimeout> | undefined;
  onCleanup(() => clearTimeout(timer));

  const { state } = useFetch<SubjectListResult>(
    () => fetchSubjects(kui.api, props.clusterId, { q: search(), page: page(), pageSize }),
    () => `${props.clusterId}|${search()}|${page()}`,
  );

  const globalLevel = useFetch<Compatibility>(
    () => fetchGlobalCompatibility(kui.api, props.clusterId),
    () => props.clusterId,
  );

  const setGlobal = createMutation((level: CompatibilityLevel) =>
    setCompatibility(kui.api, props.clusterId, level),
  );

  createEffect(
    () => state(),
    (current) => {
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
    },
  );

  const result = () =>
    valueOf(state(), { subjects: [], page: { page: 1, pageSize, totalItems: undefined } });

  const mayEdit = () => kui.permits(Actions.SchemaModifyGlobalCompatibility);

  return (
    <SubjectList
      subjects={result().subjects}
      loading={state().kind === "loading"}
      global={globalLevel.state().kind === "ready" ? valueOf(globalLevel.state(), undefined) : undefined}
      search={typed()}
      onSearch={(text) => {
        setTyped(text);
        clearTimeout(timer);
        timer = setTimeout(() => {
          setSearch(text.trim());
          setPage(1);
        }, 300);
      }}
      page={result().page.page}
      pageSize={result().page.pageSize}
      totalItems={result().page.totalItems}
      onPage={setPage}
      hrefFor={(subject) =>
        `${kui.paths.clusters()}/${encodeURIComponent(props.clusterId)}/schemas/${encodeURIComponent(subject)}`
      }
      onSetGlobal={
        mayEdit()
          ? (level) => {
              void setGlobal.run(level).then((outcome) => {
                if (outcome.kind === "done") globalLevel.reload();
              });
            }
          : undefined
      }
      setGlobalDisabledReason={writeBlockedReason({
        permitted: mayEdit(),
        readOnly: false,
        action: "change the registry's compatibility level",
      })}
      state={setGlobal.state()}
      failure={failureOf(state()) ?? mutationFailure(setGlobal.state())}
    />
  );
}

function SubjectScreen(props: { readonly clusterId: string; readonly subject: string }): JSX.Element {
  const kui = useKui();
  const location = useLocation();

  const versions = useFetch<readonly number[]>(
    () => fetchVersions(kui.api, props.clusterId, props.subject),
    () => `${props.clusterId}/${props.subject}`,
  );

  /**
   * Which version is on screen: the one in the address, or the newest.
   *
   * `latest` rather than a number when the address names none, because the registry understands the
   * word and it stays correct while somebody is looking at the page and a new version is registered.
   */
  const version = createMemo(() => new URLSearchParams(location.search).get("version") ?? "latest");

  const schema = useFetch<SchemaVersion>(
    () => fetchSchema(kui.api, props.clusterId, props.subject, version()),
    () => `${props.clusterId}/${props.subject}/${version()}`,
  );

  const compatibility = useFetch<Compatibility>(
    () => fetchSubjectCompatibility(kui.api, props.clusterId, props.subject),
    () => `${props.clusterId}/${props.subject}`,
  );

  const setLevel = createMutation((level: CompatibilityLevel) =>
    setCompatibility(kui.api, props.clusterId, level, props.subject),
  );

  /**
   * The compatibility check.
   *
   * A `createMutation` although it changes nothing: what it needs is the running / done / failed /
   * forbidden state machine and the guard that stops a double press sending two requests. The
   * endpoint carries no mutation marker on the server, is not gated behind an edit permission, and is
   * answered on a read-only cluster like any other read — so unlike `setLevel` above it is offered to
   * every principal who can see this page.
   */
  const check = createMutation((proposed: ProposedSchema) =>
    checkCompatibility(kui.api, props.clusterId, props.subject, proposed),
  );

  createEffect(
    () => schema.state(),
    (current) => {
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
    },
  );

  const mayEdit = () => kui.permits(Actions.SchemaEdit, props.subject);

  return (
    <SubjectPage
      subject={props.subject}
      versions={valueOf(versions.state(), [])}
      current={schema.state().kind === "ready" ? valueOf(schema.state(), undefined) : undefined}
      compatibility={
        compatibility.state().kind === "ready" ? valueOf(compatibility.state(), undefined) : undefined
      }
      loading={schema.state().kind === "loading"}
      listHref={`${kui.paths.clusters()}/${encodeURIComponent(props.clusterId)}/schemas`}
      hrefForVersion={(one) =>
        `${kui.paths.clusters()}/${encodeURIComponent(props.clusterId)}/schemas/${encodeURIComponent(props.subject)}?version=${one}`
      }
      onSetCompatibility={
        mayEdit()
          ? (level) => {
              void setLevel.run(level).then((outcome) => {
                if (outcome.kind === "done") compatibility.reload();
              });
            }
          : undefined
      }
      setCompatibilityDisabledReason={writeBlockedReason({
        permitted: mayEdit(),
        readOnly: false,
        action: "change this subject's compatibility level",
      })}
      state={setLevel.state()}
      failure={failureOf(schema.state()) ?? mutationFailure(setLevel.state())}
      onCheckCompatibility={(proposed) => void check.run(proposed)}
      checkState={check.state()}
    />
  );
}

/** A failed write, in the shape the screens' banner takes. */
function mutationFailure(
  state: ReturnType<ReturnType<typeof createMutation<[CompatibilityLevel], unknown>>["state"]>,
): { message: string; code?: string } | undefined {
  if (state.kind === "forbidden") return { message: state.message, code: "KUI-FORBIDDEN" };
  if (state.kind === "failed") return { message: state.message, code: state.code };
  return undefined;
}
