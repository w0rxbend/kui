/**
 * The registration seam, at both of its ends.
 *
 * `registry.ts` is the single place in the whole frontend where a feature package is named, and two
 * properties of it have never been asserted by anything:
 *
 *  1. **How many features there are.** The header used to say *"seven of them, counted in this
 *     array"* and had been wrong by inheritance twice, because `app.render.test.tsx` iterates the
 *     registry rather than sizing it. A sentence in a comment that no case reads is a sentence that
 *     goes stale silently, which is house rule 11 exactly.
 *  2. **That the roster on disk and the roster in the array are the same roster.** A
 *     `frontend/packages/feature-*` directory with no registration is a screen nobody can reach —
 *     the orphan three waves of this project have each shipped one of — and a registration naming a
 *     package that is not there is a navigation row that throws when somebody clicks it. Neither is
 *     visible to the type checker: `load` is a thunk, so a missing package is a *module resolution*
 *     failure at run time rather than a compile error, and an unregistered package compiles
 *     perfectly.
 *
 * And the third property, which `frontend/scripts/bundle-shape.mjs` measures against a built
 * manifest and nothing measures before a build: **a `load` thunk's body is a bare
 * `import("@kui/feature-…")` and nothing else.** That is asserted here against the file's own text,
 * because it is the one property of this file that is invisible in its value — a thunk that pulled
 * a named export out of the module has the same type, the same behaviour on screen, and ships the
 * whole feature to every user on first paint including users whose deployment has no such service.
 * `bundle-shape.mjs` stays the authority; this is the cheap check that fires in `pnpm test` instead
 * of after `pnpm build`.
 */
import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { featureRegistry, registrationOf, FEATURE_COUNT } from "./registry.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const SOURCE = readFileSync(join(HERE, "registry.ts"), "utf8");

/** `frontend/packages`, four directories up from this file. */
const PACKAGES = join(HERE, "..", "..", "..");

/** Every feature workspace package on disk — the same roster `bundle-shape.mjs` reads. */
function featurePackages(): readonly string[] {
  return readdirSync(PACKAGES, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith("feature-"))
    .map((entry) => entry.name)
    .sort();
}

/** The packages the registry's `load` thunks name, in the order they are written. */
function importedPackages(): readonly string[] {
  return [...SOURCE.matchAll(/import\("@kui\/(feature-[a-z-]+)"\)/g)]
    .map((match) => match[1] ?? "")
    .sort();
}

describe("the feature registry", () => {
  it("is as long as it says it is", () => {
    // The count the header used to assert in prose. `toBe` rather than a range: a registry that
    // grew by one is a change somebody made, and this line is where they are asked to notice.
    expect(featureRegistry).toHaveLength(FEATURE_COUNT);
  });

  it("registers every feature package on disk, exactly once", () => {
    /*
     * Both directions, and the reason is that only one of them is a compile error even in
     * principle. A package with no registration is a screen with no way in; a registration with no
     * package is a nav row whose `load` rejects at run time with a module-resolution error that
     * reads as a bundler fault.
     */
    expect(importedPackages()).toEqual(featurePackages());
    expect(new Set(importedPackages()).size).toBe(importedPackages().length);
  });

  it("gives every registration a distinct id, and finds each one by it", () => {
    const ids = featureRegistry.map((registration) => registration.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) expect(registrationOf(id)?.id).toBe(id);
    // A bookmark naming a feature this build does not have must fail to match rather than resolve
    // to whatever is first in the array.
    expect(registrationOf("nothing-of-that-name")).toBeUndefined();
  });

  it("keeps every load thunk a bare dynamic import", () => {
    /*
     * The property `bundle-shape.mjs` measures, checked before the build rather than after it. The
     * shape is exact on purpose: `() => import("@kui/feature-x").then(featureModule)` and nothing
     * else. A type annotation naming the feature's component, or a value pulled out "for
     * convenience", makes the feature reachable from the entry chunk and nothing about the source
     * looks different when it happens.
     */
    const thunks = [...SOURCE.matchAll(/load:\s*([^\n]+)/g)].map((match) => match[1] ?? "");
    expect(thunks).toHaveLength(FEATURE_COUNT);
    for (const thunk of thunks) {
      expect(thunk).toMatch(/^\(\) => import\("@kui\/feature-[a-z-]+"\)\.then\(featureModule\),$/);
    }
  });

  it("orders the entries strictly, so nothing shuffles when a service goes down", () => {
    // Explicit rather than "the order they were registered in": an entry that jumps when its state
    // changes is one where the operator clicks the wrong thing, because they aim at the position
    // their muscle memory learnt.
    const orders = featureRegistry.map((registration) => registration.order);
    expect([...orders].sort((left, right) => left - right)).toEqual(orders);
    expect(new Set(orders).size).toBe(orders.length);
  });
});
