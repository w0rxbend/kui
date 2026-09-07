/**
 * The schema registry screens, rendered.
 *
 * ## Why these cases go through the mapping
 *
 * The defect this package paid for was not in `SubjectList` and not in `fetchSubjects`. It was in
 * the *join*: the endpoint started answering rows instead of names, the mapping went on handing the
 * list whatever `items` held, and the list drew it. A unit case that builds a row by hand and passes
 * it to the list asserts the arrangement in this file; the product's join is what has to be
 * asserted, so the cases below start from a recorded wire document, run the package's own mapping
 * over it, and render the package's own list with the result. Break the mapping and these go red.
 *
 * The rest are the rules that have no other gate: an inherited level said in words, a version count
 * that was not read said in words rather than as `0`, and the `Register schema` action carrying the
 * reason it will not press.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { flush } from "solid-js";
import { clearToasts, toasts } from "@kui/kernel";
import type { ApiError, KuiApiClient } from "@kui/api";
import subjectsDocument from "./recorded/subjects.json" with { type: "json" };
import { describeViolations, findViolations, mount } from "./testing.js";
import {
  forgetQueries,
  inOrder,
  refuses,
  schemasHost,
  settle,
  type StubApi,
} from "./harness.jsx";
import { SchemaWorkspace } from "./SchemaWorkspace.jsx";
import { SubjectList } from "./SubjectList.jsx";
import { fetchSubjects, type SubjectRow } from "./data.js";
import { registryVoice, rowCaption, rowLabel, versionCountSentence } from "./model.js";

const idle = { kind: "idle" } as const;

function client(document: unknown): KuiApiClient {
  const get = vi.fn(async () => ({ ok: true, value: document }));
  return { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient;
}

const listProps = {
  loading: false,
  global: undefined,
  search: "",
  onSearch: (): void => undefined,
  direction: "asc" as const,
  onDirection: (): void => undefined,
  page: 1,
  pageSize: 50,
  totalItems: undefined,
  onPage: (): void => undefined,
  hrefFor: (subject: string): string =>
    `/ui/clusters/quickstart/schemas/${encodeURIComponent(subject)}`,
  state: idle,
};

/** The product's mapping over a recorded document, rendered by the product's list. */
async function listFromWire(
  document: unknown,
  extra: { readonly selected?: string } = {},
): Promise<ReturnType<typeof mount>> {
  const answer = await fetchSubjects(client(document), "quickstart");
  if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
  const mounted = mount(() => (
    <SubjectList {...listProps} {...extra} subjects={answer.value.subjects} />
  ));
  await flush();
  return mounted;
}

describe("the subject list, from the wire the gateway sends", () => {
  it("renders the subject's name and not its object", async () => {
    /*
     * The regression, as a unit case. `GET …/schemas/subjects` answers a summary row per subject;
     * the day this mapping handed the row where it used to hand the name, the screen drew
     * `[object Object]` in a link href and the only thing that saw it was a browser test.
     */
    const { container, dispose } = await listFromWire(subjectsDocument);
    try {
      const link = container.querySelector<HTMLAnchorElement>(".kui-schemas__link");
      expect(link?.textContent).toContain("orders.avro-value");
      expect(link?.getAttribute("href")).toBe("/ui/clusters/quickstart/schemas/orders.avro-value");
      expect(container.textContent).not.toContain("[object Object]");
      expect(container.innerHTML).not.toContain("%5Bobject");
    } finally {
      dispose();
    }
  });

  it("draws the row facts the wire now carries", async () => {
    // Format, version count and level. Each is a separate call on the server's side and each may be
    // absent on its own, so each is drawn on its own rather than as one blob that is present or not.
    const { container, dispose } = await listFromWire(subjectsDocument);
    try {
      expect(container.textContent).toContain("AVRO");
      expect(container.textContent).toContain("1 version");
      expect(container.textContent).toContain("BACKWARD");
    } finally {
      dispose();
    }
  });

  it("says an inherited level is inherited, and an own level is not", async () => {
    /*
     * The distinction the screen exists to make. The two rows below differ by one boolean on the
     * wire, and a screen that draws them identically tells an operator that changing the registry's
     * global level is safe for the first subject — when it is the one subject it will move.
     */
    const { container, dispose } = await listFromWire({
      items: [
        {
          subject: "follows.global-value",
          compatibility: { level: "BACKWARD", inheritedFromGlobal: true },
        },
        {
          subject: "pinned.itself-value",
          compatibility: { level: "BACKWARD", inheritedFromGlobal: false },
        },
      ],
      page: { page: 1, pageSize: 25, totalItems: 2 },
    });
    try {
      const rows = [...container.querySelectorAll(".kui-schemas__link")];
      expect(rows[0]?.textContent).toContain("inherited");
      expect(rows[1]?.textContent).toContain("on this subject");
      expect(rows[1]?.textContent).not.toContain("inherited");
    } finally {
      dispose();
    }
  });

  it("says a version count it could not read, and never draws it as zero", async () => {
    // A subject exists because something was registered under it, so `0 versions` cannot be true —
    // it can only ever mean KUI did not ask, which is a different sentence.
    const { container, dispose } = await listFromWire({
      items: [{ subject: "unenriched.v1-value" }],
      page: { page: 1, pageSize: 25, totalItems: 1 },
    });
    try {
      expect(container.textContent).toContain("version count not read");
      expect(container.textContent).not.toContain("0 versions");
      expect(container.textContent).toContain("level not read");
    } finally {
      dispose();
    }
  });

  it("asks the registry to reorder rather than sorting the page it happens to hold", async () => {
    /*
     * A registry with four thousand subjects answers fifty at a time, so sorting in the browser
     * would order fifty rows out of four thousand and call it a sort — the first page of an
     * ascending list re-sorted descending is not the last page of the list. The control therefore
     * reports the direction and the route puts it in the request, which is also why `direction` had
     * been on `SubjectQuery` since M5 with nothing in the product setting it.
     */
    const asked: string[] = [];
    const mounted = mount(() => (
      <SubjectList {...listProps} subjects={[]} onDirection={(next) => asked.push(next)} />
    ));
    await flush();
    try {
      const combobox = mounted.container.querySelector<HTMLButtonElement>('[role="combobox"]');
      expect(combobox).not.toBeNull();
      combobox?.click();
      await flush();

      const options = [...mounted.container.querySelectorAll<HTMLElement>('[role="option"]')];
      expect(options.map((one) => one.textContent)).toEqual(["Name A→Z", "Name Z→A"]);
      // `pointerdown`, which is what the listbox listens for: a click would blur the trigger first
      // and the outside-pointer handler would close the list out from under it.
      const press = new PointerEvent("pointerdown", { bubbles: true, cancelable: true });
      options[1]?.dispatchEvent(press);
      await flush();
      expect(asked).toEqual(["desc"]);
    } finally {
      mounted.dispose();
    }
  });

  it("marks the row the address names as the current page", async () => {
    // The selection is the address, so the selected row *is* a link to where you already are. That
    // is what `aria-current="page"` says, and it is the half of the fill that reaches a reader who
    // gets no colour.
    const { container, dispose } = await listFromWire(subjectsDocument, {
      selected: "orders.avro-value",
    });
    try {
      const link = container.querySelector(".kui-schemas__link");
      expect(link?.getAttribute("aria-current")).toBe("page");
      expect(link?.classList.contains("kui-schemas__link--selected")).toBe(true);
    } finally {
      dispose();
    }
  });
});

describe("the workspace", () => {
  const list = (): ReturnType<typeof SubjectList> => (
    <SubjectList {...listProps} subjects={[] as readonly SubjectRow[]} />
  );

  it("keeps the list on screen beside the selected subject", async () => {
    // Two panes, not two pages: reading a registry is comparing, and a subject that replaces the
    // list turns every comparison into a navigation.
    const { container, dispose } = mount(() => (
      <SchemaWorkspace list={list()} detail={<p>the subject pane</p>} subjectCount={6} />
    ));
    await flush();
    try {
      expect(container.querySelector(".kui-schemas")).not.toBeNull();
      expect(container.textContent).toContain("the subject pane");
      expect(container.textContent).not.toContain("No subject selected");
    } finally {
      dispose();
    }
  });

  it("says what the empty pane is for, rather than leaving half a page blank", async () => {
    const { container, dispose } = mount(() => <SchemaWorkspace list={list()} subjectCount={6} />);
    await flush();
    try {
      expect(container.textContent).toContain("No subject selected");
      expect(container.querySelector(".kui-schemas")).not.toBeNull();
    } finally {
      dispose();
    }
  });

  it("offers Register schema as a control the operator can press", async () => {
    /*
     * For three waves this control was drawn `aria-disabled` beside a true sentence: the gateway
     * served no endpoint that wrote a schema. It serves one now, so the button is live — and the
     * case asserts it is *not* disabled, which is what goes red if the endpoint is ever taken away
     * without this screen being told.
     */
    const pressed: number[] = [];
    const { container, dispose } = mount(() => (
      <SchemaWorkspace list={list()} subjectCount={6} onRegister={() => pressed.push(1)} />
    ));
    await flush();
    try {
      const button = [...container.querySelectorAll("button")].find((one) =>
        one.textContent?.includes("Register schema"),
      );
      expect(button?.getAttribute("aria-disabled")).toBe(null);
      button?.click();
      await flush();
      expect(pressed).toEqual([1]);
    } finally {
      dispose();
    }
  });

  it("says why Register schema will not press for a principal who may not", async () => {
    /*
     * `Button` refuses a disabled control with no reason, and the reason has to be *reachable*: it
     * marks a disabled control with `aria-disabled` rather than the `disabled` attribute precisely
     * so it stays focusable — a `disabled` element is skipped by Tab and fires no pointer events,
     * so its explanation exists and nobody can read it. Focusing it is therefore the assertion.
     */
    const reason = "You do not have permission to register a schema in this cluster's registry.";
    const { container, dispose } = mount(() => (
      <SchemaWorkspace list={list()} subjectCount={6} registerDisabledReason={reason} />
    ));
    await flush();
    try {
      const button = [...container.querySelectorAll("button")].find((one) =>
        one.textContent?.includes("Register schema"),
      );
      expect(button?.getAttribute("aria-disabled")).toBe("true");

      button?.dispatchEvent(new FocusEvent("focusin", { bubbles: true }));
      await flush();
      const tip = document.querySelector('[role="tooltip"]');
      expect(tip?.textContent).toContain(reason);
      expect(button?.getAttribute("aria-describedby")).toBe(tip?.getAttribute("id"));
    } finally {
      dispose();
    }
  });

  it("says it is still reading rather than that the registry did not count", async () => {
    // The claim this header used to make about an answer that had not arrived. `subjectCount` is
    // absent for two different reasons and only one of them is the registry's silence.
    const { container, dispose } = mount(() => <SchemaWorkspace list={list()} loading />);
    await flush();
    try {
      expect(container.textContent).toContain("Reading this registry's subjects");
      expect(container.textContent).not.toContain("did not say how many subjects");
    } finally {
      dispose();
    }
  });

  it("is clean under axe with a subject selected", async () => {
    const { container, dispose } = mount(() => (
      <SchemaWorkspace
        list={<SubjectList {...listProps} subjects={[]} selected="orders.avro-value" />}
        detail={<p>the subject pane</p>}
        subjectCount={6}
        globalLevel="BACKWARD"
      />
    ));
    await flush();
    try {
      const violations = await findViolations(container);
      expect(describeViolations(violations)).toBe("");
    } finally {
      dispose();
    }
  });
});

describe("the voice line", () => {
  it("uses the registry's level and the registry's count", () => {
    expect(registryVoice({ subjectCount: 6, globalLevel: "BACKWARD" })).toBe(
      "6 subjects. Backward compatible, unlike your last migration.",
    );
  });

  it("drops the aside when nothing is being checked", () => {
    // A wry line above a registry that will accept a schema breaking every reader reads as approval.
    const spoken = registryVoice({ subjectCount: 6, globalLevel: "NONE" });
    expect(spoken).not.toContain("migration");
    expect(spoken).toContain("Nothing is checked");
  });

  it("says the count was not read rather than saying zero", () => {
    expect(registryVoice({ subjectCount: undefined, globalLevel: "BACKWARD" })).toBe(
      "The registry did not say how many subjects it holds.",
    );
  });

  it("counts one subject in the singular", () => {
    expect(registryVoice({ subjectCount: 1, globalLevel: null })).toBe("1 subject.");
  });
});

describe("a row's caption", () => {
  it("names the level and whose it is", () => {
    expect(
      rowCaption({
        subject: "s",
        format: "AVRO",
        versionCount: 3,
        compatibility: { level: "BACKWARD", inherited: true },
      }),
    ).toBe("3 versions · BACKWARD, inherited");
  });

  it("keeps a level the registry named and KUI does not know apart from one it did not send", () => {
    expect(
      rowCaption({ subject: "s", format: undefined, versionCount: 1, compatibility: undefined }),
    ).toBe("1 version · level not read");
    expect(
      rowCaption({
        subject: "s",
        format: undefined,
        versionCount: 1,
        compatibility: { level: null, inherited: false },
      }),
    ).toBe("1 version · level KUI does not recognise");
  });

  it("pluralises the count it has and refuses the count it has not", () => {
    expect(versionCountSentence(1)).toBe("1 version");
    expect(versionCountSentence(12)).toBe("12 versions");
    expect(versionCountSentence(undefined)).toBe("version count not read");
    // A zero the *server* stated is a measured zero and is passed through. The rule this feature
    // keeps is that an absence never becomes one, not that the digit is banned — and no registry
    // can produce a subject with no versions, so this branch is documentation of the boundary.
    expect(versionCountSentence(0)).toBe("0 versions");
  });
});

/**
 * The route, mounted.
 *
 * ## Why this block exists, and what was true without it
 *
 * `SchemasRoute.tsx` is where every non-pure thing this package does happens — the queries, the
 * writes, the toasts, the props the two panes are actually given — and no test in this package ever
 * mounted it. Three rules survived every mutation because of that, and each is now a case below:
 * the sort control's direction could be hard-coded so the listbox was wired to nothing; both
 * `notifyLevelSet` calls could be deleted, which is the whole of M6's toast bullet here; and
 * `subjectCount` could be changed from the registry's total to the page's row count, which is
 * character for character the defect the consumer groups screen was rewritten to remove.
 *
 * Every case here asserts at the seam: what the *route* sent to the gateway, or what the *route*
 * put on screen from what the gateway sent back. None of them composes a component by hand, which
 * is the arrangement that let all three ship green.
 */
describe("the schemas route, over a stub gateway", () => {
  afterEach(() => {
    forgetQueries();
    clearToasts();
  });

  const SUBJECTS_PATH = "GET /api/v1/clusters/{clusterId}/schemas/subjects";
  const GLOBAL_PATH = "GET /api/v1/clusters/{clusterId}/schemas/compatibility";
  const SET_GLOBAL_PATH = "PUT /api/v1/clusters/{clusterId}/schemas/compatibility";
  const REGISTER_PATH = "POST /api/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions";

  /** Two rows out of a registry that says it holds six. The gap is the point of one case below. */
  const page = {
    items: [
      { subject: "orders.avro-value", format: "AVRO", versionCount: 1,
        compatibility: { level: "BACKWARD", inheritedFromGlobal: true } },
      { subject: "payments.avro-value", format: "AVRO", versionCount: 2,
        compatibility: { level: "FULL", inheritedFromGlobal: false } },
    ],
    page: { page: 1, pageSize: 50, totalItems: 6 },
  };

  const registry = {
    [SUBJECTS_PATH]: page,
    [GLOBAL_PATH]: { level: "BACKWARD", inheritedFromGlobal: false },
  };

  /** What the route asked the registry for, in the order it asked. */
  function subjectQueries(stub: StubApi): Record<string, unknown>[] {
    return stub.calls
      .filter((call) => call.method === "GET" && call.path.endsWith("/schemas/subjects"))
      .map((call) => {
        const params = (call.init?.["params"] ?? {}) as {
          readonly query?: Record<string, unknown>;
        };
        return params.query ?? {};
      });
  }

  async function open(options: Parameters<typeof schemasHost>[0]) {
    const host = schemasHost(options);
    const mounted = mount(host.view);
    await settle();
    return { ...mounted, stub: host.stub };
  }

  it("puts the sort control's direction into the request it sends the registry", async () => {
    /*
     * **The rule this packet owns.**
     *
     * A registry with four thousand subjects answers fifty at a time, so ordering in the browser
     * would order fifty rows out of four thousand and call it a sort. The registry orders; the
     * control only says which way — and until this case existed, the control could be disconnected
     * from the request entirely and every test in this package stayed green, because the only case
     * about direction handed `onDirection` to a component it had composed itself and asserted that
     * the component called it.
     *
     * So this asserts the *request*. Hard-code `direction()` in `SchemasRoute` and the second query
     * below still says `asc`, which is what makes this a gate.
     */
    const { container, dispose, stub } = await open({
      at: "/clusters/quickstart/schemas",
      answers: registry,
    });
    try {
      expect(subjectQueries(stub).at(-1)?.["direction"]).toBe("asc");

      const combobox = [...container.querySelectorAll<HTMLElement>('[role="combobox"]')].find(
        (one) => one.textContent?.includes("Name A"),
      );
      expect(combobox).not.toBeUndefined();
      (combobox as HTMLButtonElement).click();
      await flush();

      const descending = [...container.querySelectorAll<HTMLElement>('[role="option"]')].find(
        (one) => one.textContent === "Name Z→A",
      );
      expect(descending).not.toBeUndefined();
      // `pointerdown`, which is what the listbox listens for: a click blurs the trigger first and
      // the outside-pointer handler closes the list out from under it.
      descending?.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true, cancelable: true }));
      await settle();

      expect(subjectQueries(stub).at(-1)?.["direction"]).toBe("desc");
      // And the page went back to one. Page 3 of an ascending list is a different set of subjects
      // from page 3 of a descending one, so keeping the number would silently change what is shown.
      expect(subjectQueries(stub).at(-1)?.["page"]).toBe(1);
    } finally {
      dispose();
    }
  });

  it("reads the header's count off the registry's total and not off the rows on screen", async () => {
    /*
     * Two rows, six subjects. The registry pages, so the row count is this page's size dressed up
     * as an inventory — right only on a registry with one page, which is every registry anybody
     * develops against and no registry anybody operates. Change `subjectCount` to
     * `result().subjects.length` and this reads "2 subjects".
     */
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: registry,
    });
    try {
      const voice = container.querySelector(".kui-schema-workspace__voice")?.textContent ?? "";
      expect(voice).toContain("6 subjects");
      expect(voice).not.toContain("2 subjects");
    } finally {
      dispose();
    }
  });

  it("raises a toast when the registry's global level is changed", async () => {
    /*
     * M6's toast bullet, for this package. Both `notifyLevelSet` calls could be deleted with every
     * test in this package green, because nothing mounted the file that calls them.
     */
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [SET_GLOBAL_PATH]: {} },
    });
    try {
      const change = [...container.querySelectorAll("button")].find(
        (one) => one.textContent?.trim() === "Change",
      );
      expect(change).not.toBeUndefined();
      change?.click();
      await flush();

      const save = [...container.querySelectorAll("button")].find(
        (one) => one.textContent?.trim() === "Save",
      );
      save?.click();
      await settle();

      expect(toasts().map((toast) => toast.title)).toEqual([
        "Compatibility for every inheriting subject set to BACKWARD",
      ]);
    } finally {
      dispose();
    }
  });

  /* -------------------------------------------------------------------------------------------- */
  /* Registering                                                                                    */
  /* -------------------------------------------------------------------------------------------- */

  const AVRO = '{"type":"record","name":"Ping","fields":[]}';

  /** Opens the dialog and fills it in, through the controls a person uses. */
  async function fillRegistration(container: HTMLElement, subject: string): Promise<void> {
    const open = [...container.querySelectorAll("button")].find((one) =>
      one.textContent?.includes("Register schema"),
    );
    open?.click();
    await flush();

    const dialog = document.querySelector('[data-testid="register-schema-dialog"]');
    expect(dialog).not.toBeNull();

    const name = dialog?.querySelector<HTMLInputElement>("input");
    name!.value = subject;
    name!.dispatchEvent(new Event("input", { bubbles: true }));

    const editor = dialog?.querySelector<HTMLTextAreaElement>("textarea");
    editor!.value = AVRO;
    editor!.dispatchEvent(new Event("input", { bubbles: true }));
    await flush();
  }

  function pressRegister(): void {
    const dialog = document.querySelector('[data-testid="register-schema-dialog"]');
    const button = [...(dialog?.querySelectorAll("button") ?? [])].find(
      (one) => one.textContent?.trim() === "Register",
    );
    button?.click();
  }

  it("says what NONE means when NONE is what was just set", async () => {
    /*
     * The other half of the toast rule, and a different event. Every level but `NONE` narrows what
     * the registry will accept; `NONE` turns the checking off, and a green "done" for that is the
     * product agreeing with a decision it should be reporting. Deleting the special case leaves a
     * cheerful success toast over a registry that will now accept a schema breaking every reader.
     */
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [SET_GLOBAL_PATH]: {} },
    });
    try {
      const change = [...container.querySelectorAll("button")].find(
        (one) => one.textContent?.trim() === "Change",
      );
      change?.click();
      await flush();

      const level = [...container.querySelectorAll<HTMLElement>('[role="combobox"]')].find((one) =>
        one.textContent?.includes("BACKWARD"),
      );
      level?.click();
      await flush();
      const none = [...container.querySelectorAll<HTMLElement>('[role="option"]')].find(
        (one) => one.textContent === "NONE",
      );
      none?.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true, cancelable: true }));
      await flush();

      [...container.querySelectorAll("button")]
        .find((one) => one.textContent?.trim() === "Save")
        ?.click();
      await settle();

      const raised = toasts();
      expect(raised.map((toast) => toast.title)).toEqual([
        "Compatibility for every inheriting subject set to NONE",
      ]);
      expect(raised[0]?.tone).toBe("warning");
      expect(raised[0]?.message).toContain("breaks every");
    } finally {
      dispose();
    }
  });

  it("warns about a stale answer rather than reporting it as a failure with an invented code", async () => {
    /*
     * A stale query is a real answer with a badge on it: the rows below the banner are the ones the
     * registry gave, they are simply older than the last attempt. This screen drew it in `danger` —
     * the tone this product reserves for something being wrong — beside `KUI-STALE`, a code that
     * exists in no generated constant, in no `docs/api/error-codes.md` and in no service, under a
     * `code` prop documented as "the stable code, for whoever the operator escalates to".
     *
     * The state is produced rather than composed: the registry answers, a registration succeeds and
     * makes the route re-ask, and the second ask fails. `useQuery` keeps the last good value and
     * marks it stale with the failure's message, which is exactly what an operator should still be
     * reading figures from.
     */
    const gone: ApiError = { kind: "unreachable", cause: "the registry stopped answering" };
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: {
        ...registry,
        [SUBJECTS_PATH]: inOrder(page, refuses(gone)),
        [REGISTER_PATH]: { subject: "ping-value", version: 1, id: 41 },
      },
    });
    try {
      await fillRegistration(container, "ping-value");
      pressRegister();
      await settle();

      const banner = container.querySelector(".kui-banner");
      expect(banner).not.toBeNull();
      // A warning, which waits for a pause, rather than an alert, which interrupts.
      expect(banner?.getAttribute("role")).toBe("status");
      expect(banner?.classList.contains("kui-banner--warning")).toBe(true);
      expect(banner?.classList.contains("kui-banner--danger")).toBe(false);
      expect(banner?.querySelector(".kui-banner__code")).toBeNull();
      expect(container.textContent).not.toContain("KUI-STALE");
      // And the rows the registry did give are still on screen, which is the whole reason a stale
      // answer is kept rather than blanked.
      expect(container.textContent).toContain("orders.avro-value");
    } finally {
      dispose();
    }
  });

  it("registers a schema, refreshes the list and confirms what the registry made of it", async () => {
    /*
     * M6's last bullet, end to end at the seam: the dialog's fields become the request body, the
     * answer becomes the toast, and the list is asked again — because the registry pages and
     * searches, so a new subject is on screen only if this is the page the registry puts it on.
     */
    const { container, dispose, stub } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [REGISTER_PATH]: { subject: "ping-value", version: 1, id: 41 } },
    });
    try {
      const before = subjectQueries(stub).length;
      await fillRegistration(container, "ping-value");
      pressRegister();
      await settle();

      const write = stub.calls.find((call) => call.method === "POST");
      expect(write?.path).toBe("/api/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions");
      expect(write?.init?.["params"]).toEqual({
        path: { clusterId: "quickstart", subject: "ping-value" },
      });
      expect(write?.init?.["body"]).toEqual({ schemaType: "AVRO", definition: AVRO });

      // The list was asked again, so a subject the registry now holds can appear.
      expect(subjectQueries(stub).length).toBeGreaterThan(before);

      expect(toasts().map((toast) => toast.title)).toEqual([
        "Registered a schema under ping-value",
      ]);
      // The version and the id are different numbers and only one of them is in a record's header,
      // so the confirmation labels both rather than quoting one as "registered as".
      expect(toasts()[0]?.message).toContain("version 1");
      expect(toasts()[0]?.message).toContain("schema id 41");
      // And the dialog is gone: the write succeeded, so there is nothing left to correct.
      expect(document.querySelector('[data-testid="register-schema-dialog"]')).toBeNull();
    } finally {
      dispose();
    }
  });

  it("reopens on an empty form after a schema has been registered", async () => {
    /*
     * The dialog's fields are its own signals, so they outlive the surface `Dialog` unmounts. Left
     * mounted, the dialog reopens holding the schema that was just registered — one press away
     * from registering it a second time, which for a registry means a second id and a version
     * number nobody chose.
     */
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [REGISTER_PATH]: { subject: "ping-value", version: 1, id: 41 } },
    });
    try {
      await fillRegistration(container, "ping-value");
      pressRegister();
      await settle();

      const reopen = [...container.querySelectorAll("button")].find((one) =>
        one.textContent?.includes("Register schema"),
      );
      reopen?.click();
      await flush();

      const dialog = document.querySelector('[data-testid="register-schema-dialog"]');
      expect(dialog?.querySelector("textarea")?.value).toBe("");
      expect(dialog?.querySelector("input")?.value).toBe("");
      // And no leftover banner from the attempt that succeeded.
      expect(dialog?.querySelector(".kui-banner")).toBeNull();
    } finally {
      dispose();
    }
  });

  it("shows the registry's own words when the registry rejects the schema", async () => {
    /*
     * A rejection is a 400 `KUI-VALIDATION` whose envelope message says KUI's half and whose
     * `details` carry the registry's. The second half names the field path and the two types, which
     * is the only part anybody can act on, and `createMutation` keeps only the first — so the
     * screen would have shown "The registry rejected the schema" and nothing else.
     */
    const refusal: ApiError = {
      kind: "envelope",
      code: "KUI-VALIDATION",
      message: "The registry rejected this schema.",
      details: [
        {
          field: "definition",
          restrictions: [
            "{errorType:'READER_FIELD_MISSING_DEFAULT_VALUE', description:'The field 'channel' at path '/fields/3' in the new schema has no default value and is missing in the old schema'}",
          ],
        },
      ],
      correlationId: "c-1",
      retryable: false,
    };
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [REGISTER_PATH]: refuses(refusal) },
    });
    try {
      await fillRegistration(container, "orders.avro-value");
      pressRegister();
      await settle();

      const dialog = document.querySelector('[data-testid="register-schema-dialog"]');
      // The dialog stays, with the schema still in it: editing one field and pressing again is the
      // operator's next act, and a dialog that closed would make them paste it all back.
      expect(dialog).not.toBeNull();
      expect(dialog?.textContent).toContain("READER_FIELD_MISSING_DEFAULT_VALUE");
      expect(dialog?.textContent).toContain("/fields/3");
      expect(dialog?.querySelector("textarea")?.value).toBe(AVRO);
      // Nothing is confirmed. A toast here would be the product agreeing with a refusal.
      expect(toasts()).toEqual([]);
    } finally {
      dispose();
    }
  });

  it("refuses the control, with a sentence, for a principal without SCHEMA:CREATE", async () => {
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: registry,
      permits: false,
    });
    try {
      const button = [...container.querySelectorAll("button")].find((one) =>
        one.textContent?.includes("Register schema"),
      );
      expect(button?.getAttribute("aria-disabled")).toBe("true");
      button?.dispatchEvent(new FocusEvent("focusin", { bubbles: true }));
      await flush();
      expect(document.querySelector('[role="tooltip"]')?.textContent).toContain(
        "You do not have permission to register a schema",
      );
    } finally {
      dispose();
    }
  });

  it("is clean under axe with rows on screen and the register dialog open", async () => {
    /*
     * Over the *route*, with real rows: the labelled links, the pagination, the two listboxes and
     * the portaled dialog at once. The workspace's other axe case composes an empty list, so every
     * rule about a row — and every rule about the dialog — was outside it.
     */
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: registry,
    });
    try {
      expect(describeViolations(await findViolations(container))).toBe("");

      const control = [...container.querySelectorAll("button")].find((one) =>
        one.textContent?.includes("Register schema"),
      );
      control?.click();
      await flush();
      // The dialog is portaled to `document.body`, so it is swept from there rather than from the
      // route's own container — which is also how the published sweep sees it.
      expect(describeViolations(await findViolations(document.body))).toBe("");
    } finally {
      dispose();
    }
  });

  it("names each row for a screen reader without running the badge into the subject", async () => {
    // Four grid items inside one link and no text between them, so the computed name was
    // `AVROorders.avro-value` — and the subject is the one string on this screen somebody reads
    // out over a call.
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: registry,
    });
    try {
      const link = container.querySelector(".kui-schemas__link");
      expect(link?.getAttribute("aria-label")).toBe(
        "AVRO orders.avro-value. 1 version · BACKWARD, inherited",
      );
      expect(link?.getAttribute("aria-label")).toBe(
        rowLabel({
          subject: "orders.avro-value",
          format: "AVRO",
          versionCount: 1,
          compatibility: { level: "BACKWARD", inherited: true },
        }),
      );
    } finally {
      dispose();
    }
  });

  /* ---------------------------------------------------------------------------------------------
   * Rules this package shipped with no gate, closed by mutation.
   *
   * Each case below was written after deleting the rule it names from the source and watching all
   * 147 existing cases stay green. The mutation is named in the comment, because a case whose rule
   * is already gated somewhere else is a case nobody will maintain — and because the next reader
   * should be able to re-run the measurement rather than trust it.
   * ------------------------------------------------------------------------------------------ */

  /** Opens the dialog and fills it with whatever document the case wants to submit. */
  async function fillWith(
    container: HTMLElement,
    subject: string,
    definition: string,
  ): Promise<void> {
    const opener = [...container.querySelectorAll("button")].find((one) =>
      one.textContent?.includes("Register schema"),
    );
    opener?.click();
    await flush();

    const dialog = document.querySelector('[data-testid="register-schema-dialog"]');
    expect(dialog).not.toBeNull();
    const name = dialog?.querySelector<HTMLInputElement>("input");
    name!.value = subject;
    name!.dispatchEvent(new Event("input", { bubbles: true }));
    const editor = dialog?.querySelector<HTMLTextAreaElement>("textarea");
    editor!.value = definition;
    editor!.dispatchEvent(new Event("input", { bubbles: true }));
    await flush();
  }

  it("refuses a schema document that is not valid JSON before it reaches the registry", async () => {
    /*
     * **The rule this packet owns**, and it was ungated at the seam that matters.
     *
     * `proposedSchemaProblem` itself has four cases in `recorded.test.ts`. What nothing asserted is
     * that `RegisterSchemaDialog` is *wired* to it: replacing its `problem` memo with one that
     * answers `undefined`, or dropping the `problem() === undefined` clause from `canRegister`,
     * leaves all 147 cases in this package green — and with either applied the Register button
     * submits `{ not json` to the registry, the `role="alert"` paragraph never appears, and
     * `blockedReason`'s fallback is dead code.
     *
     * So the assertion is on the product's own dialog, over the route, and it is about three
     * things at once: the parser's position is on screen, the control says why it will not press,
     * and **no POST leaves the browser**. The last one is the half a component-level case cannot
     * make.
     */
    const { container, dispose, stub } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [REGISTER_PATH]: { subject: "ping-value", version: 1, id: 41 } },
    });
    try {
      await fillWith(container, "ping-value", "{ not json");

      const dialog = document.querySelector('[data-testid="register-schema-dialog"]');
      // The parser's own message, which names the position. "Invalid JSON" names nothing.
      const alert = dialog?.querySelector('[role="alert"]');
      expect(alert).not.toBeNull();
      expect(alert?.textContent).toContain("This is not valid JSON");
      expect(alert?.textContent).toContain("AVRO");

      const button = [...(dialog?.querySelectorAll("button") ?? [])].find(
        (one) => one.textContent?.trim() === "Register",
      );
      expect(button?.getAttribute("aria-disabled")).toBe("true");
      // Reachable, not merely present: `Button` marks a refused control `aria-disabled` rather than
      // `disabled` precisely so its reason can still be read.
      button?.dispatchEvent(new FocusEvent("focusin", { bubbles: true }));
      await flush();
      expect(document.querySelector('[role="tooltip"]')?.textContent).toContain(
        "This is not valid JSON",
      );

      pressRegister();
      await settle();

      // The registry was never asked, so there is nothing for it to have accepted or refused.
      expect(stub.calls.filter((call) => call.method === "POST")).toEqual([]);
      expect(toasts()).toEqual([]);
      expect(document.querySelector('[data-testid="register-schema-dialog"]')).not.toBeNull();
    } finally {
      dispose();
    }
  });

  it("lets a Protobuf definition through, because a .proto file is not JSON", async () => {
    /*
     * The other half of the same wire, and the reason the check is `proposedSchemaProblem` rather
     * than `JSON.parse`: a dialog that refused every correct Protobuf schema would be worse than
     * one that refused none. Asserted through the dialog for the same reason as above — the
     * function's own case cannot see whether the dialog consults it, and this one cannot pass if
     * the dialog starts refusing what the function allows.
     */
    const { container, dispose, stub } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [REGISTER_PATH]: { subject: "ping-value", version: 1, id: 41 } },
    });
    try {
      await fillWith(container, "ping-value", 'syntax = "proto3"; message Ping { string id = 1; }');
      const dialog = document.querySelector('[data-testid="register-schema-dialog"]');
      const type = [...(dialog?.querySelectorAll<HTMLElement>('[role="combobox"]') ?? [])].find(
        (one) => one.textContent?.includes("AVRO"),
      );
      type?.click();
      await flush();
      [...document.querySelectorAll<HTMLElement>('[role="option"]')]
        .find((one) => one.textContent === "PROTOBUF")
        ?.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true, cancelable: true }));
      await flush();

      expect(dialog?.querySelector('[role="alert"]')).toBeNull();
      pressRegister();
      await settle();
      const write = stub.calls.find((call) => call.method === "POST");
      expect((write?.init?.["body"] as { schemaType?: string })?.schemaType).toBe("PROTOBUF");
    } finally {
      dispose();
    }
  });

  it("says a registration the registry accepted without naming a version, and never says version 0", async () => {
    /*
     * The milestone's bare-zero rule on the one wire wave 4 added, and it was ungated at *both*
     * layers: `registerSchema` could read `Number(answer.value.version ?? 0)` and `notifyRegistered`
     * could push `version ${registered.version ?? 0}`, each with 147 cases green, and either puts
     * "The registry accepted it as version 0" in the success toast.
     *
     * The branch is real rather than hypothetical, which is why `RegisteredVersionDto` makes the
     * field optional: the registry's own registration answer is `{"id": N}` and the version is a
     * second call that can fail after the write has already succeeded.
     */
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [REGISTER_PATH]: { id: 41 } },
    });
    try {
      await fillRegistration(container, "ping-value");
      pressRegister();
      await settle();

      const raised = toasts();
      expect(raised.map((toast) => toast.title)).toEqual(["Registered a schema under ping-value"]);
      // What the registry did say, labelled.
      expect(raised[0]?.message).toContain("schema id 41");
      // And not a word about a version, because the registry named none. `version 0` is the
      // failure this asserts against; so is `version undefined`.
      expect(raised[0]?.message).not.toMatch(/version/i);
    } finally {
      dispose();
    }
  });

  it("says the registry named neither figure, rather than printing two zeroes", async () => {
    // The same rule one step further: an acceptance that carried no id either. The sentence is the
    // whole answer, and it is a sentence rather than `version 0, schema id 0`.
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [REGISTER_PATH]: {} },
    });
    try {
      await fillRegistration(container, "ping-value");
      pressRegister();
      await settle();

      expect(toasts()[0]?.message).toBe(
        "The registry accepted it and did not say which version or id it became.",
      );
      expect(toasts()[0]?.message).not.toContain("0");
    } finally {
      dispose();
    }
  });

  it("keeps a typed schema when the veil is clicked", async () => {
    /*
     * `closeOnScrimClick={false}` was deletable with every case green. A stray click beside a
     * dialog holding forty lines somebody pasted is not a decision to discard them, and the two
     * deliberate ways out — Cancel and Escape — are both still there.
     */
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [REGISTER_PATH]: { subject: "ping-value", version: 1, id: 41 } },
    });
    try {
      await fillRegistration(container, "ping-value");
      const scrim = document.querySelector(".kui-modal-scrim");
      expect(scrim).not.toBeNull();
      // Dispatched natively: `scrimClickHandler` compares `event.target` with the veil itself.
      scrim?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      await flush();

      const dialog = document.querySelector('[data-testid="register-schema-dialog"]');
      expect(dialog).not.toBeNull();
      expect(dialog?.querySelector("textarea")?.value).toBe(AVRO);
    } finally {
      dispose();
    }
  });

  it("does not carry a refusal into the next opening of the dialog", async () => {
    /*
     * `register.reset()` in the dialog's `onClose` was deletable. The existing case about a fresh
     * form goes through a *successful* registration, which resets the mutation on its own path —
     * so the branch that matters, closing after the registry has refused, was asserted by nothing,
     * and the next opening would draw the previous attempt's refusal above a box nobody has typed
     * in yet.
     */
    const refusal: ApiError = {
      kind: "envelope",
      code: "KUI-VALIDATION",
      message: "The registry rejected this schema.",
      details: [],
      correlationId: "c-2",
      retryable: false,
    };
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [REGISTER_PATH]: refuses(refusal) },
    });
    try {
      await fillRegistration(container, "orders.avro-value");
      pressRegister();
      await settle();
      expect(
        document.querySelector('[data-testid="register-schema-dialog"] .kui-banner'),
      ).not.toBeNull();

      const dialog = document.querySelector('[data-testid="register-schema-dialog"]');
      [...(dialog?.querySelectorAll("button") ?? [])]
        .find((one) => one.textContent?.trim() === "Cancel")
        ?.click();
      await flush();

      const reopen = [...container.querySelectorAll("button")].find((one) =>
        one.textContent?.includes("Register schema"),
      );
      reopen?.click();
      await flush();

      const reopened = document.querySelector('[data-testid="register-schema-dialog"]');
      expect(reopened).not.toBeNull();
      expect(reopened?.querySelector(".kui-banner")).toBeNull();
      expect(reopened?.textContent).not.toContain("The registry rejected this schema.");
    } finally {
      dispose();
    }
  });

  it("says whether the subject typed is a new version or a new subject", async () => {
    /*
     * The Subject field's three-branch `help` was collapsible to its first string with every case
     * green, which also made `knownSubjects` dead. It is the one thing the page's own list can
     * honestly say about a name: the same three fields submitted under a familiar subject are a
     * *new version*, judged by that subject's compatibility level, and under an unfamiliar one they
     * may be a new subject — "may", because the page holds fifty rows of a registry that can have
     * four thousand.
     */
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: registry,
    });
    try {
      await fillWith(container, "", "");
      const dialog = document.querySelector('[data-testid="register-schema-dialog"]');
      expect(dialog?.textContent).toContain("The registry's own name for this schema");

      const name = dialog?.querySelector<HTMLInputElement>("input");
      const type = (text: string): void => {
        name!.value = text;
        name!.dispatchEvent(new Event("input", { bubbles: true }));
      };

      type("orders.avro-value");
      await flush();
      expect(dialog?.textContent).toContain("already in the registry");
      expect(dialog?.textContent).toContain("registers a new version");
      expect(dialog?.textContent).not.toContain("No subject with this name is on screen");

      type("nothing.like.this-value");
      await flush();
      expect(dialog?.textContent).toContain("No subject with this name is on screen");
      expect(dialog?.textContent).not.toContain("already in the registry");
    } finally {
      dispose();
    }
  });

  it("offers no next page over a registry that did not count its subjects", async () => {
    /*
     * `hasNext` was replaceable with `true`, green. It only decides anything when the registry
     * answered no total — exactly the case it exists for — and with it always true the control
     * offers page 2 of a registry whose two subjects are all of them, which lands the operator on
     * an empty page and a search they did not change.
     */
    const uncounted = {
      items: page.items,
      page: { page: 1, pageSize: 50, totalItems: null },
    };
    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas",
      answers: { ...registry, [SUBJECTS_PATH]: uncounted },
    });
    try {
      // No total, so no page count and no numbered buttons: the only thing that can say whether
      // there is more is whether this page came back full.
      expect(container.querySelector(".kui-schemas__count")?.textContent).toBe("2 shown");
      const next = [...container.querySelectorAll("button")].find(
        (one) => one.getAttribute("aria-label") === "Next page",
      );
      expect(next).not.toBeUndefined();
      expect((next as HTMLButtonElement).disabled).toBe(true);
    } finally {
      dispose();
    }
  });

  it("says a refusal with no reason is a refusal, and not an acceptance", async () => {
    /*
     * `CompatibilityCheck`'s header argues this at length and nothing asserted it: the branch that
     * draws "The registry refused it and gave no reason" could be replaced by the branch that
     * draws "The registry raised nothing against it", green, and a refusal would then read on
     * screen as an approval with a red pill beside it.
     *
     * The state is the quickstart's own. Apicurio's Confluent-compatible API words its explanation
     * under a key KUI's registry client does not read, so a genuine refusal arrives as
     * `{"compatible": false, "messages": []}` — which `recorded.test.ts` maps correctly and no case
     * had ever drawn.
     */
    const VERSIONS = "GET /api/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions";
    const ONE_VERSION =
      "GET /api/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions/{version}";
    const SUBJECT_COMPAT =
      "GET /api/v1/clusters/{clusterId}/schemas/subjects/{subject}/compatibility";
    const CHECK =
      "POST /api/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions/{version}/compatibility";

    const { container, dispose } = await open({
      at: "/clusters/quickstart/schemas/orders.avro-value",
      answers: {
        ...registry,
        [VERSIONS]: { versions: [1] },
        [ONE_VERSION]: {
          subject: "orders.avro-value",
          version: 1,
          id: 1042,
          schemaType: "AVRO",
          definition: AVRO,
          references: [],
        },
        // Not NONE: under NONE the control is refused, which is a different rule with its own case.
        [SUBJECT_COMPAT]: { level: "BACKWARD", inheritedFromGlobal: false },
        [CHECK]: { compatible: false, messages: [] },
      },
    });
    try {
      const editor = container.querySelector<HTMLTextAreaElement>(".kui-schema-check__editor");
      expect(editor).not.toBeNull();
      editor!.value = AVRO;
      editor!.dispatchEvent(new Event("input", { bubbles: true }));
      await flush();

      [...container.querySelectorAll("button")]
        .find((one) => one.textContent?.trim() === "Check compatibility")
        ?.click();
      await settle();

      const answer = container.querySelector('[data-testid="compatibility-verdict"]');
      expect(answer).not.toBeNull();
      expect(answer?.textContent).toContain("Would be refused");
      expect(answer?.textContent).toContain("gave no reason");
      // The sentence for an *acceptance*, which must never appear beside a refusal.
      expect(answer?.textContent).not.toContain("raised nothing against it");
    } finally {
      dispose();
    }
  });
});
