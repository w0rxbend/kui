import { createSignal } from "solid-js";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { KsqlWorkspace } from "./KsqlWorkspace.jsx";

/**
 * ksqlDB.
 *
 * **There is no ksql service in KUI**, so this has no backend and no route; it is here so the screen
 * is settled before the service is written.
 *
 * Two things in it are decisions rather than layout. `auto.offset.reset` sits beside Run because it
 * decides whether a query reads the topic's history or only what arrives from now on — and the
 * second, chosen by accident, looks exactly like a query that has hung. And `Running` shows Run
 * replaced by **Cancel**, not by a spinner: a push query never ends by itself, so without a cancel
 * the only way out of one over a busy topic is to close the tab, and the query goes on running
 * server-side.
 */
const meta: Meta<typeof KsqlWorkspace> = {
  title: "Ksql/KsqlWorkspace",
  component: KsqlWorkspace,
  parameters: { layout: "fullscreen" },
};

export default meta;
type Story = StoryObj<typeof meta>;

const OBJECTS = [
  { name: "ORDERS_STREAM", kind: "stream" as const, topic: "orders.v1" },
  { name: "PAYMENTS_STREAM", kind: "stream" as const, topic: "orders.payments.v2" },
  { name: "ORDERS_BY_CUSTOMER", kind: "table" as const, topic: "ORDERS_BY_CUSTOMER" },
  { name: "PAGEVIEW_COUNTS", kind: "table" as const },
];

const SQL = `SELECT customer_id, COUNT(*) AS orders
FROM ORDERS_STREAM
WINDOW TUMBLING (SIZE 1 HOUR)
GROUP BY customer_id
EMIT CHANGES;`;

const base = {
  objects: OBJECTS,
  offsetReset: "earliest",
  onOffsetReset: () => undefined,
  onRun: () => undefined,
  onCancel: () => undefined,
  onClear: () => undefined,
};

/** Typing works: the editor is a real control, not a picture of one. */
export const Idle: Story = {
  render: (args) => {
    // A real signal, so the editor in this story actually types. A story that renders a picture of a
    // control is a story that cannot tell you the control works.
    const [sql, setSql] = createSignal(SQL);
    return <KsqlWorkspace {...base} {...args} sql={sql()} onSql={setSql} running={false} />;
  },
  args: { ...base, sql: SQL, onSql: () => undefined, running: false },
};

/** Run has become Cancel. See the header for why that is a requirement and not a nicety. */
export const Running: Story = {
  args: { ...base, sql: SQL, onSql: () => undefined, running: true },
};

/** Nothing typed yet: Run is disabled and says so rather than sending an empty query. */
export const Empty: Story = {
  args: { ...base, sql: "", onSql: () => undefined, running: false },
};

/** A cluster with nothing in it. Says what would make something appear. */
export const NoObjects: Story = {
  args: { ...base, objects: [], sql: SQL, onSql: () => undefined, running: false },
};

/** May look, may not run. */
export const Forbidden: Story = {
  args: {
    ...base,
    sql: SQL,
    onSql: () => undefined,
    running: false,
    runDisabledReason: "You do not have permission to run ksqlDB queries on this cluster.",
  },
};

/** Latest rather than earliest: the same query, reading only what arrives from now on. */
export const ReadingFromLatest: Story = {
  args: { ...base, offsetReset: "latest", sql: SQL, onSql: () => undefined, running: false },
};
