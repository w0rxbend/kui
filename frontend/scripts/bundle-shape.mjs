/**
 * Fail the build when a feature's code has stopped being loaded on demand.
 *
 * ## The promise this guards
 *
 * ADR-012 makes a user-visible promise: a feature's code is fetched when somebody navigates to it,
 * and not before. A deployment whose cluster has no schema registry never downloads
 * `feature-schemas` at all. That is why `vite.config.ts` names every chunk after its workspace
 * package, and it is the property E2E-001 asserts by watching the network.
 *
 * Nothing in either build has ever checked it. `TECH_DEBT.md`'s TD-016 was closed on a real
 * `dist/.vite/manifest.json` showing all five feature packages under the entry's `dynamicImports` —
 * which answered whether the promise held that afternoon, and nothing about whether it would hold
 * after the next refactor. The guard its own exit condition named was not built, was re-filed as
 * TD-022, and has been carried since. This is that guard.
 *
 * It could not grow out of `build-tests`: `BundleShape.scala` parses Scala.js linker output —
 * mangled `$c_` symbols and a `main.js` — which ADR-048 deleted, and no `checkBundleShape` task
 * survives in `build.mill`. The Vite question is a different question against a different artefact,
 * so this is a new check rather than an extra rule on the old one.
 *
 * ## What it reads, and why the manifest rather than the chunks
 *
 * `vite.config.ts` sets `build.manifest`, and the manifest states for every chunk which modules it
 * imports statically and which it imports dynamically. That distinction is the whole question, and
 * it cannot be answered by grepping the entry chunk's text: the entry legitimately *contains* a
 * feature's name, because `packages/shell/src/features/registry.ts` writes the import specifier and
 * the route pattern as string literals. A text search is wrong in both directions.
 *
 * Two rules, and both have to be here because either alone can be satisfied by a broken build.
 *
 *   1. **No feature reachable through static imports.** Starting at the entry, follow `imports`
 *      only — transitively, because `entry -> shared chunk -> feature` costs the browser exactly
 *      what `entry -> feature` costs — and fail if any chunk reached that way belongs to a
 *      `feature-*` package.
 *   2. **Every feature package present as a dynamic entry.** The first rule alone is satisfiable by
 *      a bundle with no features in it at all, and it is also satisfiable by the failure mode that
 *      is likeliest in practice: a static `import` that the bundler *inlines* into the entry chunk,
 *      after which the feature has no manifest entry of its own to catch. A feature that has
 *      stopped being a dynamic entry has stopped being lazily loaded, whatever the reason.
 *
 * The roster of features is read from the filesystem — every `frontend/packages/feature-*`
 * directory — rather than written here. A hard-coded list is a list that goes stale the first time
 * somebody adds a feature, and it goes stale silently, which is the failure this file exists to
 * end.
 *
 * ## Usage
 *
 *   pnpm build && pnpm bundle-shape
 *
 * `--manifest <path>` reads a different manifest, which is how the check is itself checked: point
 * it at a copy with a feature moved from `dynamicImports` to `imports` and it must fail.
 */
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), "..");

const flag = process.argv.indexOf("--manifest");
const manifestPath =
  flag === -1 ? join(frontend, "dist", ".vite", "manifest.json") : resolve(process.argv[flag + 1]);

/** Every `feature-*` workspace package, read from disk so that adding one is covered by itself. */
function featurePackages() {
  return readdirSync(join(frontend, "packages"), { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith("feature-"))
    .map((entry) => entry.name)
    .sort();
}

/**
 * Which feature package a manifest entry belongs to, or `undefined`.
 *
 * Both spellings are checked. A chunk built straight from a package's source carries `src`, the
 * module path — `packages/feature-topics/src/index.tsx`. A chunk the bundler merged or renamed
 * carries only `file`, and `vite.config.ts`'s `chunkFileNames` puts the package name in it —
 * `assets/feature-topics-<hash>.js`. Reading one and not the other leaves half the shapes
 * invisible.
 */
function featureOf(entry, features) {
  const source = entry.src ?? "";
  const file = entry.file ?? "";
  return features.find(
    (name) => source.includes(`packages/${name}/`) || file.includes(`/${name}-`),
  );
}

const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
const features = featurePackages();
const problems = [];

if (features.length === 0) {
  // Not a pedantic guard. If the glob that finds the packages ever stops matching, both rules below
  // become vacuously true and this script prints a tick over a bundle it has not looked at.
  problems.push("no frontend/packages/feature-* directory exists, so this check verified nothing");
}

const entries = Object.entries(manifest).filter(([, value]) => value.isEntry === true);
if (entries.length !== 1) {
  problems.push(`expected exactly one entry chunk in ${manifestPath}, found ${entries.length}`);
}

/** Rule 1: walk `imports` only, transitively, from the entry. */
const staticallyReached = new Set();
for (const [key] of entries) {
  const pending = [key];
  while (pending.length > 0) {
    const current = pending.pop();
    if (staticallyReached.has(current)) continue;
    staticallyReached.add(current);
    for (const next of manifest[current]?.imports ?? []) pending.push(next);
  }
}

for (const key of [...staticallyReached].sort()) {
  if (entries.some(([entryKey]) => entryKey === key)) continue;
  const feature = featureOf(manifest[key] ?? {}, features);
  if (feature !== undefined) {
    problems.push(
      `${feature} is reachable from the entry chunk through static imports (${key}), so every ` +
        "browser downloads it on first paint whether or not anybody navigates to that feature. " +
        "ADR-012 requires an import() — see packages/shell/src/features/registry.ts",
    );
  }
}

/** Rule 2: every feature is still a dynamic entry of its own. */
const dynamicallyReached = new Set();
for (const value of Object.values(manifest)) {
  for (const next of value.dynamicImports ?? []) dynamicallyReached.add(next);
}
const dynamicFeatures = new Set(
  [...dynamicallyReached]
    .map((key) => featureOf(manifest[key] ?? {}, features))
    .filter((name) => name !== undefined),
);
for (const feature of features) {
  if (!dynamicFeatures.has(feature)) {
    problems.push(
      `${feature} is not a dynamic import of any chunk in this bundle. Either it was folded into ` +
        "another chunk, or nothing routes to it at all; both mean its code is no longer fetched " +
        "on navigation.",
    );
  }
}

if (problems.length === 0) {
  console.log(
    `✓ ${features.length} feature packages, all dynamically imported and none reachable ` +
      `statically from the entry chunk (${manifestPath}).`,
  );
  process.exit(0);
}

for (const problem of problems) console.log(`✗ ${problem}`);
console.log(`\n✗ ${problems.length} bundle-shape violation${problems.length === 1 ? "" : "s"}.`);
process.exit(1);
