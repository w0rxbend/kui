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
import { describe, expect, it, vi } from "vitest";
import { flush } from "solid-js";
import type { KuiApiClient } from "@kui/api";
import subjectsDocument from "./recorded/subjects.json" with { type: "json" };
import { describeViolations, findViolations, mount } from "./testing.js";
import { SchemaWorkspace } from "./SchemaWorkspace.jsx";
import { SubjectList } from "./SubjectList.jsx";
import { fetchSubjects, type SubjectRow } from "./data.js";
import {
  REGISTER_UNAVAILABLE_REASON,
  registryVoice,
  rowCaption,
  versionCountSentence,
} from "./model.js";

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

  it("offers Register schema and says why it will not press", async () => {
    /*
     * The design draws `+ Register schema` and the gateway serves no endpoint that writes one. A
     * hidden control would say KUI has no opinion about registering schemas; a live one would be a
     * button that does nothing. `Button` refuses a disabled control with no reason, and this is the
     * case that keeps the reason a sentence rather than a shrug.
     */
    const { container, dispose } = mount(() => <SchemaWorkspace list={list()} subjectCount={6} />);
    await flush();
    try {
      const button = [...container.querySelectorAll("button")].find((one) =>
        one.textContent?.includes("Register schema"),
      );
      expect(button?.getAttribute("aria-disabled")).toBe("true");

      /*
       * And the reason is reachable, which is the half that is easy to lose. `Button` marks a
       * disabled control with `aria-disabled` rather than the `disabled` attribute precisely so it
       * stays focusable — a `disabled` element is skipped by Tab and fires no pointer events, so
       * its explanation exists and nobody can read it. Focusing it is therefore the assertion.
       */
      button?.dispatchEvent(new FocusEvent("focusin", { bubbles: true }));
      await flush();
      const tip = document.querySelector('[role="tooltip"]');
      expect(tip?.textContent).toContain(REGISTER_UNAVAILABLE_REASON);
      expect(button?.getAttribute("aria-describedby")).toBe(tip?.getAttribute("id"));
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
