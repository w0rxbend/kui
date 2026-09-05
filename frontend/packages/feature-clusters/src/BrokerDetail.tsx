/**
 * One broker, in detail: its log directories and its configuration.
 *
 * Two tabs, answering the two questions that bring somebody to a single machine — *why is this disk
 * filling up*, and *is this broker configured like its peers*.
 *
 * ## The tabs fail independently
 *
 * They read two different endpoints, and one being unavailable must not blank the other: a broker
 * whose settings cannot be read still has disks worth looking at. So each tab carries its own state
 * — its own loading, its own failure, its own retry — and the page around them carries none.
 *
 * ## There is no metrics tab
 *
 * Not disabled. Absent. There is no metrics service for several milestones, and a permanently greyed
 * tab would be noise on every visit for all of them — a promise with a date on it.
 *
 * ## Read-only, with nothing that looks otherwise
 *
 * There is no edit control on the configuration tab, not even a disabled one. Broker configuration
 * edits arrive in a later milestone behind read-only mode and an audit trail, and a greyed-out Edit
 * button today would be a promise the product has not made.
 */

import { Show, createMemo, createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import {
  Button,
  Card,
  DataTable,
  EmptyState,
  MISSING,
  MagnitudeBar,
  PageHeader,
  StatusPill,
  Tabs,
  TextField,
  formatBytes,
  formatCount,
  share,
  type Column,
} from "@kui/kernel";
import {
  brokerMeta,
  brokerName,
  configMatches,
  configSourceLabel,
  diskPercent,
  healthLabel,
  healthTone,
  sortConfigs,
  totalLogDirBytes,
  type Broker,
  type ConfigEntry,
  type LogDir,
} from "./model.js";

/** One tab's data, and the two ways it can fail to be data. */
export type Loaded<T> =
  | { readonly kind: "ready"; readonly value: T }
  | { readonly kind: "loading" }
  | { readonly kind: "unavailable"; readonly message: string; readonly code: string; readonly onRetry: () => void }
  | { readonly kind: "forbidden"; readonly message: string; readonly code: string };

export type BrokerTabKey = "logdirs" | "configuration";

export interface BrokerDetailProps {
  readonly broker: Broker;
  readonly clusterName: string;
  readonly clustersHref: string;
  readonly brokersHref: string;
  readonly logDirs: Loaded<readonly LogDir[]>;
  readonly configuration: Loaded<readonly ConfigEntry[]>;
  /** Which tab is showing. Controlled, because the tab belongs in the URL — see `onTabChange`. */
  readonly tab: BrokerTabKey;
  /**
   * Changing the tab navigates rather than writing to local state. Two truths about which tab is
   * open — the URL's and the component's — drift apart the first time somebody presses Back.
   */
  readonly onTabChange: (tab: BrokerTabKey) => void;
}

export function BrokerDetail(props: BrokerDetailProps): JSX.Element {
  return (
    <section class="kui-brk-page" data-testid="broker-detail">
      <PageHeader
        title={brokerName(props.broker)}
        crumbs={[
          { label: "Clusters", href: props.clustersHref },
          { label: props.clusterName },
          { label: "Brokers", href: props.brokersHref },
          { label: brokerName(props.broker) },
        ]}
        chip={
          <span class="kui-brk-chips">
            <StatusPill tone={healthTone(props.broker.health)} dot>
              {healthLabel(props.broker.health)}
            </StatusPill>
            <Show when={props.broker.isController}>
              <StatusPill tone="accent" title="This broker is the cluster's active controller.">
                controller
              </StatusPill>
            </Show>
          </span>
        }
        testId="broker-detail-head"
      />

      <p class="kui-brk-meta" data-testid="broker-meta">
        {brokerMeta(props.broker)}
      </p>

      {/*
        `Tabs` builds only the selected panel's body, so opening this page fetches the log
        directories and nothing else: somebody who came to look at disk usage should neither wait
        for a `describeConfigs` call nor cause one.
      */}
      <Tabs
        label="Broker sections"
        selected={props.tab}
        onSelect={(id) => props.onTabChange(id as BrokerTabKey)}
        data-testid="broker-tabs"
        tabs={[
          { id: "logdirs", label: "Log directories", body: () => <LogDirsTab broker={props.broker} state={props.logDirs} /> },
          { id: "configuration", label: "Configuration", body: () => <ConfigurationTab state={props.configuration} /> },
        ]}
      />
    </section>
  );
}

/* ------------------------------------------------------------------------------------------ */
/* Log directories                                                                              */
/* ------------------------------------------------------------------------------------------ */

/**
 * Where this broker keeps its data, and how much of it is in each place.
 *
 * ## A directory that could not be read is a row, not a gap
 *
 * Kafka answers per directory, so one unreadable disk must not blank the disks that answered. Such a
 * row keeps its path and carries the error where its size would be — because "this directory is
 * empty" and "this directory did not answer" are the two readings an operator must not confuse, and
 * a missing row would silently subtract a disk from the total.
 *
 * ## The bar is scaled to the largest directory on this broker
 *
 * Not to the disk's capacity, which Kafka does not report per directory, and not to a fixed maximum.
 * The question the bar answers is "which of these is the big one", and it is drawn beside the figure
 * rather than instead of it.
 */
function LogDirsTab(props: { readonly broker: Broker; readonly state: Loaded<readonly LogDir[]> }): JSX.Element {
  const dirs = createMemo<readonly LogDir[]>(() => (props.state.kind === "ready" ? props.state.value : []));
  const largest = createMemo(() => dirs().reduce((max, dir) => Math.max(max, dir.sizeBytes ?? 0), 0));
  const total = createMemo(() => totalLogDirBytes(dirs()));
  const unreadable = createMemo(() => dirs().filter((dir) => dir.error !== null).length);

  const columns: readonly Column<LogDir>[] = [
    { id: "path", header: "Directory", render: (row) => <span class="kui-brk-mono">{row.path}</span> },
    {
      id: "size",
      header: "Size",
      align: "numeric",
      width: "14rem",
      render: (row) =>
        row.sizeBytes === null ? (
          <span class="kui-brk-missing" title={row.error ?? "This directory did not answer."}>
            {MISSING}
          </span>
        ) : (
          <span class="kui-brk-sizecell">
            <MagnitudeBar inline value="" fraction={share(row.sizeBytes, largest())} />
            <span class="kui-brk-mono">{formatBytes(row.sizeBytes)}</span>
          </span>
        ),
    },
    {
      id: "partitions",
      header: "Partitions",
      align: "numeric",
      width: "8rem",
      render: (row) => (row.partitions === null ? MISSING : formatCount(row.partitions)),
    },
    {
      id: "status",
      header: "Status",
      width: "16rem",
      // Kept even though it reads "ok" on a healthy broker: the point of this column is the row
      // where it does not, and a status column that only appeared during an incident would be a
      // layout change in the middle of one.
      render: (row) =>
        row.error === null ? (
          <StatusPill tone="success">ok</StatusPill>
        ) : (
          <span class="kui-brk-name">
            <StatusPill tone="danger">unreadable</StatusPill>
            <span class="kui-brk-error">{row.error}</span>
          </span>
        ),
    },
  ];

  const percent = createMemo(() => diskPercent(props.broker.diskUsedBytes, props.broker.diskTotalBytes));

  return (
    <TabPanel
      state={props.state}
      title="Log directories"
      unavailableDescription="The cluster service did not answer, so KUI cannot say what is on this broker's disks."
      forbiddenDescription="You do not have permission to read log directories on this cluster."
      caption={
        total() === null
          ? undefined
          : `${formatBytes(total() ?? 0)} across ${formatCount(dirs().length)} ${dirs().length === 1 ? "directory" : "directories"}${
              unreadable() === 0 ? "" : `, with ${formatCount(unreadable())} that could not be read — the total is lower than the truth`
            }.${percent() === undefined ? "" : ` The disk is ${Math.round(percent() ?? 0)}% full.`}`
      }
      testId="broker-logdirs"
    >
      <DataTable<LogDir>
        caption={`Log directories on ${brokerName(props.broker)}`}
        columns={columns}
        rows={dirs()}
        rowKey={(row) => row.path}
        testId="broker-logdirs-table"
        empty={
          <EmptyState
            kind="empty"
            title="No log directories."
            description="The broker reported no directories, which should not happen while it is running."
          />
        }
      />
    </TabPanel>
  );
}

/* ------------------------------------------------------------------------------------------ */
/* Configuration                                                                                */
/* ------------------------------------------------------------------------------------------ */

/**
 * What this broker is configured with, and — the part that matters — who set it.
 *
 * Rows are ordered by source: what somebody changed at runtime first, what the file said next, the
 * defaults last. That order *is* the feature. Somebody opening this tab is comparing one broker
 * against its peers, or looking for a change made at three in the morning, and either way the answer
 * is at the top.
 *
 * ## A sensitive value is not a missing one
 *
 * Kafka refuses to disclose the value of a setting it marks sensitive. That is a third thing,
 * distinct from "not set" and from "we could not read it", and the screen says which: a `hidden` pill
 * with a sentence, never a blank and never an em dash.
 *
 * ## The filter does not rebuild the field
 *
 * The search box is outside every boundary that the rows are inside, and it owns its own signal. A
 * results update re-renders the table and never the input, so the field cannot be replaced under a
 * cursor mid-word — the drawer defect, in a different costume.
 */
function ConfigurationTab(props: { readonly state: Loaded<readonly ConfigEntry[]> }): JSX.Element {
  const [term, setTerm] = createSignal("");
  const all = createMemo<readonly ConfigEntry[]>(() => (props.state.kind === "ready" ? sortConfigs(props.state.value) : []));
  const visible = createMemo(() => all().filter((entry) => configMatches(entry, term())));

  const columns: readonly Column<ConfigEntry>[] = [
    {
      id: "name",
      header: "Name",
      render: (row) => (
        <span class={row.overridden ? "kui-brk-config__name kui-brk-config__name--overridden" : "kui-brk-config__name"}>
          {row.name}
        </span>
      ),
    },
    {
      id: "value",
      header: "Value",
      render: (row) =>
        row.sensitive ? (
          <StatusPill tone="neutral" title="Kafka does not disclose the value of a setting it marks sensitive.">
            hidden
          </StatusPill>
        ) : row.value === null || row.value === "" ? (
          <span class="kui-brk-missing" title="This setting has no value.">
            {MISSING}
          </span>
        ) : (
          <span class="kui-brk-mono kui-brk-config__value">{row.value}</span>
        ),
    },
    { id: "source", header: "Source", width: "14rem", render: (row) => configSourceLabel(row.source) },
  ];

  return (
    <TabPanel
      state={props.state}
      title="Configuration"
      unavailableDescription="The cluster service did not answer, so KUI cannot read this broker's settings."
      forbiddenDescription="You do not have permission to read broker configuration on this cluster."
      caption="Read-only. Broker settings are not editable in KUI yet."
      headerEnd={
        <TextField
          label="Filter settings"
          labelHidden
          placeholder="Filter settings…"
          icon="search"
          size="sm"
          value={term()}
          onInput={(value) => setTerm(value)}
        />
      }
      testId="broker-configuration"
    >
      <DataTable<ConfigEntry>
        caption="Broker configuration"
        columns={columns}
        rows={visible()}
        rowKey={(row) => row.name}
        testId="broker-configuration-table"
        empty={
          term().trim() === "" ? (
            <EmptyState kind="empty" title="No settings." description="The broker reported no configuration at all." />
          ) : (
            <EmptyState
              kind="filtered"
              title={`Nothing matched ${term()}.`}
              description="No setting on this broker has a name or a value like that."
              action={
                <Button variant="secondary" onClick={() => setTerm("")}>
                  Clear filter
                </Button>
              }
            />
          )
        }
      />
    </TabPanel>
  );
}

/* ------------------------------------------------------------------------------------------ */
/* The shape both tabs are drawn in                                                             */
/* ------------------------------------------------------------------------------------------ */

/**
 * A tab's card, with the four not-happy renderings in one place.
 *
 * The frame never disappears (SPEC §7.1): a failing tab keeps its title, gains the sentence, the
 * stable code and a Retry, and the other tab is untouched.
 */
function TabPanel(props: {
  readonly state: Loaded<unknown>;
  readonly title: string;
  readonly unavailableDescription: string;
  readonly forbiddenDescription: string;
  readonly caption?: string | undefined;
  readonly headerEnd?: JSX.Element | undefined;
  readonly testId: string;
  readonly children: JSX.Element;
}): JSX.Element {
  const state = (): "ready" | "loading" | "unavailable" | "forbidden" => {
    if (props.state.kind === "ready") return "ready";
    return props.state.kind;
  };

  return (
    <Card
      title={props.title}
      state={state()}
      caption={props.state.kind === "ready" ? props.caption : undefined}
      headerEnd={props.state.kind === "ready" ? props.headerEnd : undefined}
      message={props.state.kind === "unavailable" || props.state.kind === "forbidden" ? props.state.message : undefined}
      description={
        props.state.kind === "forbidden"
          ? props.forbiddenDescription
          : props.state.kind === "unavailable"
            ? props.unavailableDescription
            : undefined
      }
      code={props.state.kind === "unavailable" || props.state.kind === "forbidden" ? props.state.code : undefined}
      stateAction={
        props.state.kind === "unavailable" ? (
          <Button variant="secondary" icon="refresh" onClick={props.state.onRetry}>
            Retry
          </Button>
        ) : undefined
      }
      // The table's own height, so switching tabs does not make the page jump.
      bodyMinHeight="20rem"
      testId={props.testId}
    >
      {props.children}
    </Card>
  );
}
