/**
 * The rendering rules this feature shipped with nothing holding them, closed by mutation.
 *
 * ## The measurement, and how to repeat it
 *
 * Each case names one edit to a file in `src/`. The edit was applied, the whole of
 * `pnpm -C frontend test packages/feature-clusters packages/feature-schemas` was run, **162 of 162
 * stayed green**, and the edit was reverted before the case below was written. Re-apply the edit a
 * case names and that case goes red.
 *
 * ## Where the survivors were, and why
 *
 * `SchemasRoute.tsx` is thoroughly gated — the registry's own total against the page's row count,
 * a stale answer's tone and its absent code, the NONE toast, a registration that named no version.
 * Every one of those was mutated here and every one went red. What was not gated is the layer
 * below: what `SubjectList`, `SubjectPage` and `CompatibilityCheck` *draw* once the route has
 * handed them a value. The route's cases drive the real screen and assert the things a route
 * decides; a level said in words rather than only in a colour is not one of them, and it is the
 * sentence this feature's first file header calls the reason the screen exists.
 */
import { describe, expect, it } from "vitest";
import { flush } from "solid-js";
import { mount } from "./testing.js";
import { SubjectList } from "./SubjectList.jsx";
import { SubjectPage } from "./SubjectPage.jsx";
import { CompatibilityCheck } from "./CompatibilityCheck.jsx";
import { RegisterSchemaDialog } from "./RegisterSchemaDialog.jsx";
import type { Compatibility, SchemaVersion, SubjectRow } from "./data.js";

const idle = { kind: "idle" } as const;

const listProps = {
  loading: false,
  failure: undefined as
    | { readonly message: string; readonly code?: string; readonly tone?: "danger" | "warning" }
    | undefined,
  global: undefined as Compatibility | undefined,
  search: "",
  onSearch: (): void => undefined,
  direction: "asc" as const,
  onDirection: (): void => undefined,
  page: 1,
  pageSize: 50,
  totalItems: undefined as number | undefined,
  onPage: (): void => undefined,
  hrefFor: (subject: string): string => `/ui/clusters/quickstart/schemas/${subject}`,
  state: idle,
};

const ROW: SubjectRow = {
  subject: "orders.avro-value",
  format: "AVRO",
  versionCount: 3,
  compatibility: { level: "BACKWARD", inherited: false },
};

function list(extra: Partial<typeof listProps> & { readonly subjects?: readonly SubjectRow[] } = {}) {
  return mount(() => <SubjectList {...listProps} subjects={[ROW]} {...extra} />);
}

describe("the registry's global compatibility level", () => {
  it("says what NONE means in words, and not only in a colour", async () => {
    /*
     * Mutation: `<Show when={level().level === "NONE"}>` → any condition that is never true. Green
     * over 162 cases, and the screen then reports `NONE` as a pill and nothing else — which is
     * exactly the state this file's header describes as how a cluster ends up unchecked after an
     * incident two years ago with nobody knowing. The words are the half of the signal that
     * survives being colour-blind, being in a screen reader, and being scanned quickly.
     */
    const { container, dispose } = list({ global: { level: "NONE", inherited: false } });
    await flush();
    expect(container.textContent).toContain(
      "Nothing is checked: the registry will accept a schema that breaks existing readers.",
    );
    dispose();
  });

  it("draws NONE as a warning and every other level as an ordinary fact", async () => {
    /*
     * Mutation: `tone={level().level === "NONE" ? "warning" : "neutral"}` → `tone="neutral"`. Green
     * over 162 cases. The sentence above and this tone are two separate statements of one rule and
     * the mutation of either leaves the other standing, so both are asserted rather than one.
     */
    const off = list({ global: { level: "NONE", inherited: false } });
    await flush();
    expect(off.container.querySelector(".kui-pill")?.className).toContain("kui-pill--warning");
    off.dispose();

    const checking = list({ global: { level: "FULL", inherited: false } });
    await flush();
    const pill = checking.container.querySelector(".kui-pill");
    expect(pill?.textContent).toBe("FULL");
    expect(pill?.className).toContain("kui-pill--neutral");
    expect(checking.container.textContent).not.toContain("Nothing is checked");
    checking.dispose();
  });

  it("refuses to draw a level it does not recognise as though it were a level", async () => {
    /*
     * Mutation: `when={level().level !== null}` → `when={true}`. Green over 162 cases. `null` is
     * what `levelOf` answers for a word this browser does not know, and the fallback exists because
     * this value decides whether tomorrow's schema is accepted. With the mutation the pill's own
     * `Show when={props.children !== ""}` swallows the empty string, so the screen draws **nothing
     * at all** where the registry's answer should be — the silent failure the sentence replaces.
     */
    const { container, dispose } = list({ global: { level: null, inherited: false } });
    await flush();
    expect(container.textContent).toContain("the registry reported a level KUI does not recognise");
    expect(container.querySelector(".kui-pill")).toBeNull();
    dispose();
  });
});

describe("a subject row's format badge", () => {
  it("draws no badge at all for a row whose format the registry did not name", async () => {
    /*
     * Mutation: `<Show when={row.format}>` → `when={row.format ?? "AVRO"}`. Green over 162 cases,
     * and every row whose batch did not carry a format is then badged `AVRO` — the comment two
     * lines above the `Show` says exactly why that is worse than nothing: the registry holds
     * Protobuf and JSON schemas too, and a guessed language sends somebody to decode a record with
     * the wrong reader.
     */
    const named = list({ subjects: [ROW] });
    await flush();
    expect(named.container.querySelector(".kui-schemas__format")?.textContent).toBe("AVRO");
    named.dispose();

    const unnamed = list({ subjects: [{ ...ROW, format: undefined }] });
    await flush();
    expect(unnamed.container.querySelector(".kui-schemas__format")).toBeNull();
    expect(unnamed.container.textContent).not.toContain("AVRO");
    unnamed.dispose();
  });
});

describe("a subject list with no rows on it", () => {
  it("says a search matched nothing, and never that the registry is empty", async () => {
    /*
     * Mutation: collapse `kind` and `title` to the unfiltered pair. Green over 162 cases, and a
     * registry with four thousand subjects then reads "No subjects registered." the moment somebody
     * types three letters that match none of them — a confident false statement about the upstream,
     * which is the class of defect this product refuses more strongly than an em dash.
     */
    const filtered = list({ subjects: [], search: "zzz" });
    await flush();
    expect(filtered.container.textContent).toContain("No subject matches that text.");
    expect(filtered.container.querySelector(".kui-empty-state")?.className).toContain(
      "kui-empty-state--filtered",
    );
    filtered.dispose();

    const empty = list({ subjects: [], search: "" });
    await flush();
    expect(empty.container.textContent).toContain("No subjects registered.");
    expect(empty.container.querySelector(".kui-empty-state")?.className).toContain(
      "kui-empty-state--empty",
    );
    empty.dispose();
  });

  it("says nothing about emptiness while the registry is still being read, or when it refused", async () => {
    /*
     * Mutation: `when={props.loading !== true && props.failure === undefined}` → `when={true}`.
     * Green over 162 cases. Both states then draw "No subjects registered." — over a request that
     * has not come back, and *underneath the banner saying the registry is unreachable*, where it
     * is a second and contradictory answer to the same question.
     */
    const reading = list({ subjects: [], loading: true });
    await flush();
    expect(reading.container.querySelector(".kui-empty-state")).toBeNull();
    reading.dispose();

    const refused = list({
      subjects: [],
      failure: { message: "The registry is not answering.", code: "KUI-UPSTREAM-UNAVAILABLE" },
    });
    await flush();
    expect(refused.container.textContent).toContain("The registry is not answering.");
    expect(refused.container.querySelector(".kui-empty-state")).toBeNull();
    refused.dispose();
  });
});

describe("one subject's pane", () => {
  const SCHEMA: SchemaVersion = {
    subject: "orders.avro-value",
    version: 2,
    id: 41,
    schemaType: "AVRO",
    definition: '{"type":"record","name":"Order","fields":[]}',
    references: [{ name: "com.kui.Money", subject: "money.avro-value", version: 7 }],
  };

  function pane(current: SchemaVersion | undefined = SCHEMA) {
    return mount(() => (
      <SubjectPage
        subject="orders.avro-value"
        versions={[1, 2, 3]}
        current={current}
        compatibility={{ level: "BACKWARD", inherited: false }}
        listHref="/ui/clusters/quickstart/schemas"
        hrefForVersion={(one) => `/ui/clusters/quickstart/schemas/orders.avro-value?version=${one}`}
        state={idle}
      />
    ));
  }

  it("names every schema this one references, with the version it points at", async () => {
    /*
     * Mutation: `<Show when={schema().references.length > 0}>` → `when={false}`. Green over 162
     * cases. A reference is what makes a schema decodable — an Avro record that names a type
     * defined in another subject cannot be read without it — and a pane that silently omits them
     * shows a definition that looks complete and is not.
     */
    const { container, dispose } = pane();
    await flush();
    const references = container.querySelector(".kui-subject__references");
    expect(references).not.toBeNull();
    expect(references?.textContent).toContain("com.kui.Money");
    expect(references?.textContent).toContain("money.avro-value");
    expect(references?.textContent).toContain("v7");
    dispose();
  });

  it("says whether the level is the subject's own or the registry's", async () => {
    /*
     * Mutation: empty the `kui-subject__compat-source` span. Green over 162 cases, although
     * `levelSourceSentence` itself is gated in `model.ts` — the pure function tested where it is
     * computed and not where it is applied, once again. The file's own comment calls this "the
     * distinction the whole feature turns on": an operator who cannot see which group a subject is
     * in cannot know what changing the global level is about to move.
     */
    const own = mount(() => (
      <SubjectPage
        subject="orders.avro-value"
        versions={[1]}
        current={SCHEMA}
        compatibility={{ level: "BACKWARD", inherited: false }}
        listHref="/ui/clusters/quickstart/schemas"
        hrefForVersion={(one) => `?version=${one}`}
        state={idle}
      />
    ));
    await flush();
    expect(own.container.querySelector(".kui-subject__compat-source")?.textContent).toBe(
      "set on this subject",
    );
    own.dispose();

    const inherited = mount(() => (
      <SubjectPage
        subject="orders.avro-value"
        versions={[1]}
        current={SCHEMA}
        compatibility={{ level: "BACKWARD", inherited: true }}
        listHref="/ui/clusters/quickstart/schemas"
        hrefForVersion={(one) => `?version=${one}`}
        state={idle}
      />
    ));
    await flush();
    expect(inherited.container.querySelector(".kui-subject__compat-source")?.textContent).toBe(
      "inherited from the registry's global level",
    );
    inherited.dispose();
  });

  it("marks the version on screen as the current page, and no other", async () => {
    /*
     * Mutation: `aria-current={props.current?.version === version ? "page" : undefined}` →
     * `aria-current={undefined}`. Green over 162 cases. The version list is a row of near-identical
     * links; without this a screen-reader user is told which subject they are on and never which
     * *version*, which is the only thing that differs between three of the four links on the pane.
     * The subject list's own row has the same rule and it is gated there — this one was not.
     */
    const { container, dispose } = pane();
    await flush();
    const links = [...container.querySelectorAll<HTMLAnchorElement>(".kui-subject__version-list a")];
    expect(links.map((one) => one.textContent)).toEqual(["v1", "v2", "v3"]);
    expect(links.map((one) => one.getAttribute("aria-current"))).toEqual([null, "page", null]);
    dispose();
  });
});

describe("the check-a-schema panel", () => {
  function panel(level: Parameters<typeof CompatibilityCheck>[0]["level"]) {
    return mount(() => (
      <CompatibilityCheck
        subject="orders.avro-value"
        level={level}
        onCheck={() => undefined}
        state={idle}
      />
    ));
  }

  it("draws a refusal in the tone this product keeps for a refusal", async () => {
    /*
     * Mutation: `tone={answer().compatible ? "success" : "danger"}` → `tone="success"`. Green over
     * 162 cases: the existing case asserts the pill's *words* and nothing asserted its tone, so a
     * refused schema was drawn with the green pill this product reserves for a healthy answer. The
     * words and the colour are two statements of one fact and each has to be held on its own —
     * which is the same split as the `NONE` sentence and the `NONE` pill above.
     */
    const refused = mount(() => (
      <CompatibilityCheck
        subject="orders.avro-value"
        level="BACKWARD"
        onCheck={() => undefined}
        state={{ kind: "done", value: { compatible: false, messages: ["reader/writer mismatch"] } }}
      />
    ));
    await flush();
    const bad = refused.container.querySelector(".kui-schema-check__verdict .kui-pill");
    expect(bad?.textContent).toContain("Would be refused");
    expect(bad?.className).toContain("kui-pill--danger");
    refused.dispose();

    const accepted = mount(() => (
      <CompatibilityCheck
        subject="orders.avro-value"
        level="BACKWARD"
        onCheck={() => undefined}
        state={{ kind: "done", value: { compatible: true, messages: [] } }}
      />
    ));
    await flush();
    const good = accepted.container.querySelector(".kui-schema-check__verdict .kui-pill");
    expect(good?.textContent).toContain("Would be accepted");
    expect(good?.className).toContain("kui-pill--success");
    accepted.dispose();
  });

  it("says before the box that a level of NONE makes the answer meaningless", async () => {
    /*
     * Mutation: `const meaningful = () => props.level === undefined || checkIsMeaningful(...)` →
     * `() => true`. Green over 162 cases: `checkIsMeaningful` and `checkBlockedReason` are both
     * gated as functions in `data.ts`, and the panel could stop calling one of them without any
     * case noticing — the pure-data rule tested where it is computed and not where it is applied,
     * which is the whole shape of this sweep.
     *
     * The banner is above the textarea deliberately: reading it afterwards means having typed a
     * schema for nothing.
     */
    const none = panel("NONE");
    await flush();
    const banner = none.container.querySelector(".kui-banner");
    expect(banner?.className).toContain("kui-banner--warning");
    expect(banner?.textContent).toContain("The registry checks nothing");
    // Above the box, not below it: the DOM order is the reading order.
    const editor = none.container.querySelector(".kui-schema-check__editor");
    expect(
      (banner as Element).compareDocumentPosition(editor as Node) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    none.dispose();

    const checking = panel("BACKWARD");
    await flush();
    expect(checking.container.querySelector(".kui-banner")).toBeNull();
    checking.dispose();

    // Not yet read is not yet NONE, which is the third state the prop's own docblock names.
    const unread = panel(undefined);
    await flush();
    expect(unread.container.querySelector(".kui-banner")).toBeNull();
    unread.dispose();
  });
});

describe("the register-a-schema dialog", () => {
  function dialog(state: { readonly kind: string }) {
    return mount(() => (
      <RegisterSchemaDialog
        open
        onClose={() => undefined}
        onRegister={() => undefined}
        state={state as never}
        knownSubjects={[]}
      />
    ));
  }

  const cancel = (): HTMLButtonElement | undefined =>
    [...document.querySelectorAll<HTMLButtonElement>("button")].find(
      (one) => (one.textContent ?? "").trim() === "Cancel",
    );

  it("will not let Cancel be pressed while the registry is being asked", async () => {
    /*
     * Mutation: collapse the `<Show when={busy()}>` to its fallback, so Cancel is always live.
     * Green over 162 cases. A registration is in flight at that moment: closing the dialog
     * unmounts the component that is waiting for the answer, and the operator is left with a
     * registry that may or may not have accepted their schema and a screen that says neither. The
     * dialog is portalled, so this looks at the document rather than at the mount's container.
     */
    const busy = dialog({ kind: "running" });
    await flush();
    expect(cancel()?.getAttribute("aria-disabled")).toBe("true");
    busy.dispose();

    const waiting = dialog(idle);
    await flush();
    expect(cancel()?.getAttribute("aria-disabled")).toBeNull();
    waiting.dispose();
  });
});
