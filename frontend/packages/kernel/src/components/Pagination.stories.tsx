import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { For, createSignal } from "solid-js";
import { Pagination } from "./Pagination.jsx";

/**
 * The row under a table.
 *
 * Three of these stories are the ones worth keeping. `Interactive` is the only way to see the
 * numbered window slide as you walk through 50 pages. `NoTotal` is the case a server that streams
 * produces, and the one that is normally discovered in production. `TheEdges` is every off-by-one
 * a paginator can have, on one screen.
 */
const meta: Meta<typeof Pagination> = {
  title: "Lists/Pagination",
  component: Pagination,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof Pagination>;

const Framed = (props: { readonly children: unknown }) => (
  <div
    style={{
      border: "var(--kui-card-border)",
      "border-radius": "var(--kui-radius-lg)",
      background: "var(--kui-color-surface-elevated)",
    }}
  >
    {props.children as never}
  </div>
);

/** The topic list's row, exactly as the design draws it: 24 topics, 8 to a page. */
export const TheTopicListRow: Story = {
  render: () => (
    <Framed>
      <Pagination page={1} pageSize={8} total={24} shown={8} onPage={() => undefined} onPageSize={() => undefined} />
    </Framed>
  ),
};

/** Live, over enough pages that the numbered window has to move. Walk it to the end and back. */
export const Interactive: Story = {
  render: () => {
    const [page, setPage] = createSignal(1);
    const [size, setSize] = createSignal(8);
    const total = 397;
    const shown = () => Math.min(size(), Math.max(0, total - (page() - 1) * size()));
    return (
      <Framed>
        <Pagination
          page={page()}
          pageSize={size()}
          total={total}
          shown={shown()}
          onPage={setPage}
          onPageSize={(next) => {
            setSize(next);
            // Changing the page size can leave the user past the end. The component clamps what it
            // is given, but the caller owns the page, so the caller has to reset it — this is the
            // realistic handler, not a simplified one.
            setPage(1);
          }}
        />
      </Framed>
    );
  },
};

/**
 * The server did not say how many there are.
 *
 * The range says what it knows and stops. The numbered buttons are gone entirely, and `last` is
 * disabled — a computed last page would be a guess, and a guess sends the operator somewhere that
 * does not exist.
 */
export const NoTotal: Story = {
  render: () => (
    <Framed>
      <Pagination page={3} pageSize={8} total={undefined} shown={8} hasNext onPage={() => undefined} />
    </Framed>
  ),
};

/** No total, and no next page either: the end of a stream. Every step is disabled but `previous`. */
export const NoTotalAtTheEnd: Story = {
  render: () => (
    <Framed>
      <Pagination page={4} pageSize={8} total={undefined} shown={3} hasNext={false} onPage={() => undefined} />
    </Framed>
  ),
};

/**
 * Every edge, stacked.
 *
 * From the top: nothing at all; one row; exactly one full page; the first page of two; the last
 * page, which is short; and a page the user should not be on because the list shrank underneath
 * them. None of these is reachable from a screenshot and all of them happen.
 */
export const TheEdges: Story = {
  render: () => {
    // Each gets its own name. Six landmarks called "Pagination" in one document are six
    // indistinguishable entries in a screen reader's landmark list — which is how the `label` prop
    // came to exist, and this story is where axe found it.
    const cases: readonly (readonly [string, number, number, number])[] = [
      ["Nothing at all", 1, 0, 0],
      ["One row", 1, 1, 1],
      ["Exactly one full page", 1, 8, 8],
      ["The first page of two", 1, 9, 8],
      ["The last page, short", 3, 17, 1],
      ["Past the end, after the list shrank", 9, 17, 0],
    ];
    return (
      <div style={{ display: "flex", "flex-direction": "column", gap: "12px" }}>
        <For each={cases}>
          {([label, page, total, shown]) => (
            <Framed>
              <Pagination
                label={label}
                page={page}
                pageSize={8}
                total={total}
                shown={shown}
                onPage={() => undefined}
              />
            </Framed>
          )}
        </For>
      </div>
    );
  },
};

/** Without the page-size control, for a table whose size is fixed by the screen rather than chosen. */
export const NoPageSizeControl: Story = {
  render: () => (
    <Framed>
      <Pagination page={2} pageSize={20} total={140} shown={20} onPage={() => undefined} />
    </Framed>
  ),
};

/** The extreme: a total nobody will paginate through, and the window still has to behave. */
export const TheExtremes: Story = {
  render: () => (
    <Framed>
      <Pagination
        page={62_500}
        pageSize={16}
        total={2_000_000}
        shown={16}
        onPage={() => undefined}
        onPageSize={() => undefined}
      />
    </Framed>
  ),
};

/** The smallest window. It wraps onto as many lines as it needs; it never scrolls sideways. */
export const NarrowWindow: Story = {
  render: () => (
    <div style={{ width: "320px" }}>
      <Framed>
        <Pagination page={2} pageSize={8} total={97} shown={8} onPage={() => undefined} onPageSize={() => undefined} />
      </Framed>
    </div>
  ),
};
