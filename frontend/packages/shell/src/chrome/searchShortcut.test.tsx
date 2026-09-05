/**
 * The keyboard shortcut the search field advertises.
 *
 * Before this existed, the `⌘K` drawn in the field's trailing corner was decoration: nothing in the
 * application listened for that key. These tests are what stop it going back to being decoration —
 * a control that advertises a shortcut it does not implement teaches the reader that keyboard
 * shortcuts in this product do not work, and they stop trying the ones that do.
 */

import { describe, expect, it, vi } from "vitest";
import { flush, onSettled } from "solid-js";

import { SearchField } from "./SearchField.jsx";
import { installSearchShortcut, isTypingTarget, matchesSearchShortcut } from "./searchShortcut.js";
import { findViolations, mount } from "./testing.js";

const stroke = (over: Partial<Parameters<typeof matchesSearchShortcut>[0]> = {}) => ({
  key: "k",
  metaKey: false,
  ctrlKey: false,
  altKey: false,
  ...over,
});

describe("the matching rule", () => {
  it("accepts both Command-K and Control-K on every platform", () => {
    expect(matchesSearchShortcut(stroke({ metaKey: true }))).toBe(true);
    expect(matchesSearchShortcut(stroke({ ctrlKey: true }))).toBe(true);
  });

  it("accepts the shifted key, because event.key is 'K' when Shift is down", () => {
    expect(matchesSearchShortcut(stroke({ metaKey: true, key: "K" }))).toBe(true);
  });

  it("ignores a bare K, which is somebody typing", () => {
    expect(matchesSearchShortcut(stroke())).toBe(false);
  });

  it("does not swallow chords that belong to other software", () => {
    expect(matchesSearchShortcut(stroke({ metaKey: true, altKey: true }))).toBe(false);
  });

  it("ignores other keys held with the modifier", () => {
    expect(matchesSearchShortcut(stroke({ metaKey: true, key: "j" }))).toBe(false);
  });
});

describe("isTypingTarget", () => {
  it("recognises the places a plain keystroke belongs to the user", () => {
    for (const tag of ["input", "textarea", "select"]) {
      expect(isTypingTarget(document.createElement(tag))).toBe(true);
    }
    const editable = document.createElement("div");
    editable.setAttribute("contenteditable", "true");
    expect(isTypingTarget(editable)).toBe(true);
    expect(isTypingTarget(document.createElement("div"))).toBe(false);
    expect(isTypingTarget(null)).toBe(false);
  });
});

describe("installSearchShortcut", () => {
  it("calls back on the chord and stops when removed", () => {
    const focus = vi.fn();
    const remove = installSearchShortcut(focus, document);

    document.dispatchEvent(new KeyboardEvent("keydown", { key: "k", metaKey: true, cancelable: true }));
    expect(focus).toHaveBeenCalledTimes(1);

    remove();
    document.dispatchEvent(new KeyboardEvent("keydown", { key: "k", metaKey: true, cancelable: true }));
    // Still one. A listener that outlives its frame keeps stealing the key from whatever replaced
    // it, and the symptom is a shortcut that focuses an input nobody can see.
    expect(focus).toHaveBeenCalledTimes(1);
  });

  it("takes the key from the browser, which also binds this chord", () => {
    const remove = installSearchShortcut(() => {}, document);
    const event = new KeyboardEvent("keydown", { key: "k", metaKey: true, cancelable: true });
    document.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    remove();
  });

  it("fires while focus is in a text field, because that is still a request to search", () => {
    const focus = vi.fn();
    const remove = installSearchShortcut(focus, document);
    const input = document.createElement("input");
    document.body.appendChild(input);
    input.focus();
    input.dispatchEvent(new KeyboardEvent("keydown", { key: "k", metaKey: true, bubbles: true, cancelable: true }));
    expect(focus).toHaveBeenCalledTimes(1);
    remove();
    input.remove();
  });
});

/**
 * The field and the shortcut together, wired the way `App` wires them.
 *
 * The unit tests above prove the matcher and the listener; this proves the seam between them — the
 * `inputRef` prop — which is the part that can silently stop working when the field's markup
 * changes and which no unit test would notice.
 */
function mountWiredField(initial: string) {
  return mount(() => {
    let input: HTMLInputElement | undefined;
    onSettled(() =>
      installSearchShortcut(() => {
        input?.focus();
        input?.select();
      }),
    );
    return (
      <SearchField
        value={initial}
        onInput={() => {}}
        inputRef={(el) => {
          input = el;
        }}
      />
    );
  });
}

describe("the field and the shortcut together", () => {
  it("keeps the promise the hint makes", () => {
    const { container, dispose } = mountWiredField("");
    const input = container.querySelector<HTMLInputElement>('[data-testid="search-input"]');
    expect(document.activeElement).not.toBe(input);

    document.dispatchEvent(new KeyboardEvent("keydown", { key: "k", metaKey: true, cancelable: true }));
    flush();

    expect(document.activeElement).toBe(input);
    dispose();
  });

  it("selects the existing query, so the next keystroke replaces it", () => {
    const { container, dispose } = mountWiredField("orders");
    const input = container.querySelector<HTMLInputElement>('[data-testid="search-input"]');

    document.dispatchEvent(new KeyboardEvent("keydown", { key: "k", metaKey: true, cancelable: true }));
    flush();

    expect(input?.selectionStart).toBe(0);
    expect(input?.selectionEnd).toBe("orders".length);
    dispose();
  });

  it("unbinds when the field goes away", () => {
    const { container, dispose } = mountWiredField("");
    const input = container.querySelector<HTMLInputElement>('[data-testid="search-input"]');
    dispose();

    expect(() =>
      document.dispatchEvent(new KeyboardEvent("keydown", { key: "k", metaKey: true, cancelable: true })),
    ).not.toThrow();
    expect(document.activeElement).not.toBe(input);
  });

  it("has no accessibility violations", async () => {
    const { container, dispose } = mountWiredField("");
    expect((await findViolations(container)).map((v) => v.id)).toEqual([]);
    dispose();
  });
});
