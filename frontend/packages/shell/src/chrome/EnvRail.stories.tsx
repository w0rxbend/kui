import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import { EnvRail, type RailDestination } from "./EnvRail.jsx";
import { CLUSTERS } from "./fixtures.js";
import type { ClusterSummary } from "./types.js";

/**
 * The environment rail.
 *
 * Two stories are the ones that matter, and neither is the happy path.
 *
 * `SameFirstLetter` is the design's unresolved problem (`SCREENS.md` §6, open finding 1) made
 * visible: three environments beginning with `P` are three identical tiles, and the only thing
 * standing between an operator and purging the wrong cluster is the tooltip. Hover them.
 *
 * `EveryHealth` is there because the fourth dot colour is the one that gets dropped. "We have not
 * asked yet" must not be readable as "it is fine", and the only way to check that is to see all
 * four next to each other.
 */
const meta: Meta<typeof EnvRail> = {
  title: "Chrome/EnvRail",
  component: EnvRail,
  parameters: { layout: "fullscreen" },
};

export default meta;
type Story = StoryObj<typeof EnvRail>;

const DESTINATIONS: readonly RailDestination[] = [
  { id: "notifications", label: "Notifications", icon: "bell", href: "#notifications" },
  { id: "ksql", label: "ksqlDB", icon: "ksql", href: "#ksql" },
  { id: "security", label: "Security", icon: "shield", href: "#security" },
  { id: "settings", label: "Settings", icon: "settings", href: "#settings", atFoot: true },
];

/** The rail against the page ground it is actually drawn on. It has no fill of its own. */
const OnThePage = (props: { readonly children: unknown }) => (
  <div style={{ display: "flex", height: "560px", background: "var(--kui-color-surface)" }}>
    {props.children as never}
    {/* A stand-in for the drawer, so the seam between the two can be judged: the rail is on the
        page ground and the drawer is raised, and if that step disappears they merge into one slab. */}
    <div style={{ width: "182px", background: "var(--kui-color-surface-raised)" }} />
  </div>
);

/** The four clusters the fixtures carry, with the first selected. */
export const Default: Story = {
  render: () => {
    const [current, setCurrent] = createSignal(CLUSTERS[0]?.id);
    return (
      <OnThePage>
        <EnvRail
          environments={CLUSTERS}
          currentId={current()}
          onSelect={setCurrent}
          destinations={DESTINATIONS}
          currentDestinationId="ksql"
          onAdd={() => undefined}
          accountName="Olena Petrenko"
        />
      </OnThePage>
    );
  },
};

/**
 * Every health, in one column.
 *
 * Top to bottom: healthy, degraded, not answering, and not yet known. The fourth is deliberately
 * none of the health colours — a grey dot cannot be mistaken for a green one, and "we have not
 * asked" being read as "it is fine" is the most expensive misreading this rail can produce.
 */
export const EveryHealth: Story = {
  render: () => {
    const environments: readonly ClusterSummary[] = [
      { id: "a", name: "healthy-01", health: "healthy" },
      { id: "b", name: "degraded-01", health: "degraded" },
      { id: "c", name: "unreachable-01", health: "unreachable" },
      { id: "d", name: "starting-01", health: "unknown" },
    ];
    return (
      <OnThePage>
        <EnvRail environments={environments} currentId="a" accountName="Olena Petrenko" />
      </OnThePage>
    );
  },
};

/**
 * Three environments called `P`.
 *
 * This is the design's open problem, not a contrived case: `prod-kyiv-01`, `prod-eu-02` and
 * `payments-prod` all reduce to one letter. Hover each tile — the tooltip is the only thing that
 * tells them apart, which is why the component's documentation says it is not optional.
 */
export const SameFirstLetter: Story = {
  render: () => {
    const environments: readonly ClusterSummary[] = [
      { id: "1", name: "prod-kyiv-01", health: "healthy" },
      { id: "2", name: "prod-eu-02", health: "healthy" },
      { id: "3", name: "payments-prod", health: "degraded" },
    ];
    return (
      <OnThePage>
        <EnvRail environments={environments} currentId="1" accountName="Olena Petrenko" />
      </OnThePage>
    );
  },
};

/**
 * Nothing has loaded yet.
 *
 * The rail keeps its width and draws the mark and the account. It must not collapse: the frame's
 * geometry does not depend on how many clusters exist, and a rail that appears once the first
 * response lands would shift the whole page sideways at an arbitrary moment.
 */
export const NoEnvironmentsYet: Story = {
  render: () => (
    <OnThePage>
      <EnvRail environments={[]} destinations={DESTINATIONS} accountName="Olena Petrenko" />
    </OnThePage>
  ),
};

/**
 * The identity service is unavailable.
 *
 * The avatar is a neutral person glyph, not guessed initials. Inventing somebody's initials is
 * worse than admitting we do not know them — especially here, where the avatar is how you check
 * whose credentials are about to purge a topic.
 */
export const NoIdentity: Story = {
  render: () => (
    <OnThePage>
      <EnvRail environments={CLUSTERS} currentId="prod-kyiv-01" destinations={DESTINATIONS} />
    </OnThePage>
  ),
};

/**
 * More environments than the rail is tall.
 *
 * The environment list scrolls and the account stays pinned at the foot. An estate with twenty
 * clusters is ordinary, and losing the account menu to it is not.
 */
export const TheExtremes: Story = {
  render: () => {
    const environments: readonly ClusterSummary[] = Array.from({ length: 20 }, (_, index) => ({
      id: `env-${index}`,
      name: `${["prod", "staging", "dev", "qa"][index % 4]}-${index}`,
      health: (["healthy", "degraded", "unreachable", "unknown"] as const)[index % 4] ?? "healthy",
    }));
    return (
      <OnThePage>
        <EnvRail
          environments={environments}
          currentId="env-3"
          destinations={DESTINATIONS}
          onAdd={() => undefined}
          accountName="Olena Petrenko"
        />
      </OnThePage>
    );
  },
};

/** A name that is one emoji, which configuration files really do contain. It must not be halved. */
export const AnAwkwardName: Story = {
  render: () => (
    <OnThePage>
      <EnvRail
        environments={[
          { id: "1", name: "🚀 launch-cluster", health: "healthy" },
          { id: "2", name: "   ", health: "unknown" },
          { id: "3", name: "Ωmega-prod", health: "healthy" },
        ]}
        currentId="1"
        accountName="Olena Petrenko"
      />
    </OnThePage>
  ),
};
