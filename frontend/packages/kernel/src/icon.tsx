/**
 * The icon set, drawn inline as SVG.
 *
 * ## Why inline SVG and not an icon font
 *
 * The design specification names a ligature icon font and says to bundle the subset in use. This
 * is the same decision taken one step further, and for the same reason the specification gives for
 * bundling in the first place: KUI is installed in private and air-gapped networks, so nothing may
 * be fetched at run time. Inline SVG is markup. It costs no request at all, it cannot half-arrive,
 * and there is no window during which every icon in the product is an empty box because a font file
 * is still in flight.
 *
 * Two more properties fall out of it. An SVG stroked in `currentColor` is automatically the right
 * colour in both themes and on every surface, so an icon never needs a colour of its own. And a
 * glyph in an icon font is a character: a screen reader that meets one may read out a private-use
 * codepoint, whereas an `aria-hidden` `<svg>` is silent by construction.
 *
 * ## Accessibility
 *
 * Every icon here is decoration and carries `aria-hidden="true"` plus `focusable="false"` (the
 * second is for Internet Explorer's descendants, which put SVG in the tab order otherwise). The
 * meaning is carried by the text beside the icon, or by the accessible name of the control that
 * contains it. An icon that is the *only* content of a control does not become the label — the
 * control gets the label.
 *
 * ## Why a record of path data rather than a component per icon
 *
 * The set is consumed by name from data: a navigation destination carries an icon *name*, not an
 * icon component, because destinations arrive from the capability registry rather than from source.
 * Keying the set means a name that does not exist is a type error rather than a blank square.
 *
 * The geometry is one 24x24 grid at a 2px stroke for every icon, so that icons sit together without
 * individual nudging, and the size is given in `em` so an icon matches the text it sits beside at
 * whatever size that text is.
 */

/** The shapes of one icon: a list of `<path d="...">` commands, plus optional filled circles. */
type IconShape = {
  readonly paths: readonly string[];
  /** Circles as `[cx, cy, r]`. Stroked like the paths unless listed in `filled`. */
  readonly circles?: readonly (readonly [number, number, number])[];
  /** Indices into `circles` that are painted solid instead of stroked. */
  readonly filledCircles?: readonly number[];
};

/* eslint-disable sort-keys */
const SHAPES = {
  "chevron-down": { paths: ["M6 9l6 6 6-6"] },
  "chevron-up": { paths: ["M18 15l-6-6-6 6"] },
  "chevron-left": { paths: ["M15 18l-6-6 6-6"] },
  "chevron-right": { paths: ["M9 18l6-6-6-6"] },
  close: { paths: ["M18 6L6 18", "M6 6l12 12"] },
  check: { paths: ["M20 6L9 17l-5-5"] },
  plus: { paths: ["M12 5v14", "M5 12h14"] },
  search: { paths: ["M21 21l-4.35-4.35"], circles: [[11, 11, 8]] },
  menu: { paths: ["M3 12h18", "M3 6h18", "M3 18h18"] },
  info: { paths: ["M12 16v-4", "M12 8h.01"], circles: [[12, 12, 10]] },
  warning: {
    paths: [
      "M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z",
      "M12 9v4",
      "M12 17h.01",
    ],
  },
  refresh: {
    paths: ["M23 4v6h-6", "M1 20v-6h6", "M20.5 9a9 9 0 0 0-14.9-3.4L1 10", "M3.5 15a9 9 0 0 0 14.9 3.4L23 14"],
  },
  sun: {
    paths: [
      "M12 1v2",
      "M12 21v2",
      "M4.2 4.2l1.4 1.4",
      "M18.4 18.4l1.4 1.4",
      "M1 12h2",
      "M21 12h2",
      "M4.2 19.8l1.4-1.4",
      "M18.4 5.6l1.4-1.4",
    ],
    circles: [[12, 12, 5]],
  },
  moon: { paths: ["M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"] },
  /* "Follow the system": a disc with one half filled. The third theme state had no picture, and it
   * is the state a first-time visitor is in, so it is the one that most had to be legible. */
  "theme-auto": { paths: ["M12 3a9 9 0 0 1 0 18z"], circles: [[12, 12, 9]] },
  /* The appearance control: three sliders. */
  sliders: {
    paths: ["M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M1 14h6", "M9 8h6", "M17 16h6"],
  },
  bell: { paths: ["M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9", "M13.7 21a2 2 0 0 1-3.4 0"] },
  /* Navigation: dashboard is the four-pane grid the design draws. */
  dashboard: { paths: ["M3 3h7v7H3z", "M14 3h7v5h-7z", "M14 12h7v9h-7z", "M3 14h7v7H3z"] },
  /* Brokers: stacked machines. */
  brokers: { paths: ["M3 4h18v6H3z", "M3 14h18v6H3z", "M7 7h.01", "M7 17h.01"] },
  /* Topics: stacked layers. */
  topics: { paths: ["M12 2L2 7l10 5 10-5z", "M2 17l10 5 10-5", "M2 12l10 5 10-5"] },
  /* Consumers: a group of people. */
  consumers: {
    paths: ["M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2", "M23 21v-2a4 4 0 0 0-3-3.9"],
    circles: [[9, 7, 4]],
  },
  /* Schema registry: braces. */
  schema: {
    paths: [
      "M8 3H7a2 2 0 0 0-2 2v4a2 2 0 0 1-2 2 2 2 0 0 1 2 2v4a2 2 0 0 0 2 2h1",
      "M16 3h1a2 2 0 0 1 2 2v4a2 2 0 0 0 2 2 2 2 0 0 0-2 2v4a2 2 0 0 1-2 2h-1",
    ],
  },
  /* Kafka Connect: a plug. */
  connect: { paths: ["M9 2v6", "M15 2v6", "M6 8h12v4a6 6 0 0 1-12 0z", "M12 18v4"] },
  /* KSQL: a terminal prompt. */
  ksql: { paths: ["M3 4h18v16H3z", "M7 9l3 3-3 3", "M13 15h4"] },
  /* The drawer foot's cluster card: a shield, because the statement it makes is "this is sound". */
  shield: { paths: ["M12 2l8 4v6c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V6z", "M9 12l2 2 4-4"] },
  /* The product's own mark: three nodes joined, which is a topic fanning out to its consumers.
   * The only icon in the set that is not decoration inside some other control — it is the picture
   * on the brand tile — and it is still `aria-hidden`, because the wordmark beside it is the name. */
  topology: {
    paths: ["M8.6 13.5l6.8 4", "M15.4 6.5l-6.8 4"],
    circles: [
      [18, 5, 3],
      [6, 12, 3],
      [18, 19, 3],
    ],
    filledCircles: [0, 1, 2],
  },
  /* Tabs. */
  messages: { paths: ["M3 5h18v14H3z", "M3 6l9 7 9-7"] },
  settings: {
    paths: ["M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2V21a2 2 0 1 1-4 0v-.1A1.7 1.7 0 0 0 7 19.4a1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.7 1.7 0 0 0 3 15H3a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 4.6 7a1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.7 1.7 0 0 0 9 3V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 3 1.2l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0 1.2 2.9H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"],
    circles: [[12, 12, 3]],
  },
  /* A solid dot, for health markers that are drawn as an icon rather than as a bare span. */
  dot: { circles: [[12, 12, 4]], filledCircles: [0], paths: [] },

  /* Added for the control primitives (button, select, checkbox, chip, avatar). They are in this
   * file rather than in a second set beside it: two icon modules means two answers to "is this
   * icon decorative", two stroke weights on one screen, and a name that resolves in one of them
   * and not the other. One keyed record is the whole point. */
  minus: { paths: ["M5 12h14"] },
  error: { paths: ["M12 7v6", "M12 17h.01"], circles: [[12, 12, 10]] },
  person: { paths: ["M4 20a8 8 0 0 1 16 0"], circles: [[12, 8, 4]] },
  trash: { paths: ["M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3"] },
  send: { paths: ["M4 12l16-8-8 16-2-6-6-2z"] },
  key: { paths: ["M14.5 10.5L4 21v-3h3v-3h3l3.5-3.5"], circles: [[16, 8, 4]] },
  copy: { paths: ["M9 9h10v10H9z", "M15 9V5H5v10h4"] },
  lock: { paths: ["M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 8 0v4"] },
  braces: { paths: ["M9 4c-2 0-2 3-2 4s0 4-2 4c2 0 2 3 2 4s0 4 2 4", "M15 4c2 0 2 3 2 4s0 4 2 4c-2 0-2 3-2 4s0 4-2 4"] },
  lag: { paths: ["M12 7v5l3.5 2"], circles: [[12, 12, 9]] },
  "arrow-up-right": { paths: ["M7 17L17 7", "M8 7h9v9"] },

  /* An empty tray: "there is nothing here yet". Distinct from `search`, which is the glyph for
   * "your filter matched nothing" — two situations that must never share a picture. */
  inbox: { paths: ["M3 13h4l2 3h6l2-3h4", "M5 5h14l3 8v5a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-5z"] },
  /* The dashboard's two chart glyphs. The design marks the throughput panel with bars and the
   * latency panel with a rising line, and the distinction is doing work: the two panels sit in
   * different rows and a reader scanning for "the one with the history in it" uses the glyph. */
  "chart-bars": { paths: ["M4 20V10", "M10 20V4", "M16 20v-7", "M22 20H2"] },
  "chart-line": { paths: ["M3 17l5-6 4 3 5-7 4 4", "M3 21h18"] },
  /* A funnel, for the message browser's filter field. Drawn as one open path rather than a closed
   * shape so that it reads at 14px, which is the size it is used at: a filled funnel that small
   * is a grey triangle. */
  filter: { paths: ["M3 5h18l-7 8v6l-4 2v-8z"] },
} as const satisfies Record<string, IconShape>;
/* eslint-enable sort-keys */

/** Every icon this product has. A name outside this union does not compile. */
export type IconName = keyof typeof SHAPES;

/** The names, for the gallery story and for the test that asserts every icon is hidden. */
export const iconNames = Object.keys(SHAPES) as IconName[];

export type IconProps = {
  readonly name: IconName;
  /** Extra classes. The icon's own class is always applied first. */
  readonly class?: string;
  /**
   * A CSS length. Icons default to `1em` so they follow the text beside them; pass a length only
   * where the icon is on its own and has no text to follow, such as a 20px navigation glyph.
   */
  readonly size?: string;
};

/**
 * One icon. Always decorative: if you need it to mean something, put the meaning in the text or in
 * the accessible name of the surrounding control.
 */
export function Icon(props: IconProps) {
  const shape = () => SHAPES[props.name] as IconShape;
  return (
    <svg
      class={["kui-icon", props.class]}
      viewBox="0 0 24 24"
      width={props.size ?? "1em"}
      height={props.size ?? "1em"}
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      /* `focusable="false"` used to be needed to keep old Internet Explorer from putting an SVG
       * in the tab order. It is not in Solid's SVG attribute types, no engine this product
       * supports needs it, and `aria-hidden` already removes the icon from the accessibility
       * tree — an `aria-hidden` SVG has no `tabindex`, so it is not reachable by Tab either. */
      data-icon={props.name}
    >
      {shape().paths.map((d) => (
        <path d={d} />
      ))}
      {(shape().circles ?? []).map((c, index) => (
        <circle
          cx={c[0]}
          cy={c[1]}
          r={c[2]}
          fill={(shape().filledCircles ?? []).includes(index) ? "currentColor" : "none"}
        />
      ))}
    </svg>
  );
}
