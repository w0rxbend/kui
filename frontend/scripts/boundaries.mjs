/**
 * Fail the build when a package imports something its `package.json` does not declare.
 *
 * ## Why this exists
 *
 * `./mill checkArchitecture` enforces ADR-041's layering on the Scala half, edge by edge, and it
 * cannot see a line of TypeScript. This is the other half of that gate. Without it the seven
 * packages of `pnpm-workspace.yaml` are a directory convention rather than a boundary, and the way
 * a convention fails is quiet: one `import { TopicRow } from "@kui/feature-topics"` inside
 * `feature-messages` compiles, bundles, passes every test, and has already merged the two features
 * into one before anybody reads the diff that did it.
 *
 * The rule that matters above all others is therefore checked on its own, and checked even when
 * the offending package has *declared* the dependency:
 *
 *   * **No `feature-*` package may import another `feature-*` package.** Features are the unit that
 *     is loaded on demand — `vite.config.ts` names each chunk after its package precisely so that
 *     E2E-001 can assert a cluster without the topic capability never downloads the topics feature.
 *     One edge between two features puts both chunks in one download and quietly deletes that
 *     property. Shared code goes to `@kui/kernel`; that is what the kernel is for.
 *   * **Nothing may import `@kui/shell`.** The shell composes the features. A feature that reaches
 *     back into it inverts the dependency and makes the shell impossible to test without them.
 *
 * Everything else is the same rule pnpm's strict `node_modules` layout already enforces at install
 * time, brought forward to where it can be read: an import is legal when the importing package
 * declares it, or when the workspace root does — root `devDependencies` really are resolvable from
 * a package, because Node walks up out of `packages/<name>/node_modules`, which is why every
 * package uses `vitest` and `storybook-solidjs-vite` without naming them.
 *
 * A relative specifier that climbs out of its own package is checked too. It is the same violation
 * wearing a different spelling, and it is the one an editor's auto-import writes.
 *
 * ## Usage
 *
 *   pnpm lint:boundaries
 *
 * Prints file, line and specifier for every offending edge, and exits 1.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const packagesDir = join(frontend, "packages");

/**
 * Blank out comments so that prose is never read as code.
 *
 * Every character of a comment becomes a space and every newline stays a newline, so offsets into
 * the result are offsets into the original and a match can still be turned into a line number.
 * Strings and regular expressions are copied through untouched: a `//` inside either is not a
 * comment, and treating it as one truncated the rest of the line the first time this was written
 * with a regex instead of a scanner.
 */
function withoutComments(source) {
  const out = [];
  let previous = ""; // the last significant character, which decides `/` division from `/` regex
  let i = 0;

  while (i < source.length) {
    const c = source[i];
    const next = source[i + 1];

    if (c === "/" && next === "/") {
      while (i < source.length && source[i] !== "\n") out.push(" "), i++;
      continue;
    }
    if (c === "/" && next === "*") {
      const end = source.indexOf("*/", i + 2);
      const stop = end === -1 ? source.length : end + 2;
      for (; i < stop; i++) out.push(source[i] === "\n" ? "\n" : " ");
      continue;
    }
    if (c === '"' || c === "'" || c === "`") {
      out.push(c);
      i++;
      while (i < source.length) {
        out.push(source[i]);
        if (source[i] === "\\") {
          if (i + 1 < source.length) out.push(source[++i]);
        } else if (source[i] === c) {
          i++;
          break;
        }
        i++;
      }
      previous = c;
      continue;
    }
    // A `/` after a value is division; after an operator, a keyword or nothing it opens a regular
    // expression. The distinction only matters here because a regex may contain a quote.
    if (c === "/" && !/[\w$)\]]/.test(previous)) {
      out.push(c);
      i++;
      let inClass = false;
      while (i < source.length && source[i] !== "\n") {
        out.push(source[i]);
        if (source[i] === "\\") {
          if (i + 1 < source.length) out.push(source[++i]);
        } else if (source[i] === "[") inClass = true;
        else if (source[i] === "]") inClass = false;
        else if (source[i] === "/" && !inClass) {
          i++;
          break;
        }
        i++;
      }
      previous = "/";
      continue;
    }

    out.push(c);
    if (!/\s/.test(c)) previous = c;
    i++;
  }

  return out.join("");
}

/** `import … from "x"`, `export … from "x"`, a bare `import "x"`, and a lazy `import("x")`. */
const SPECIFIER_PATTERNS = [
  /\b(?:import|export)\b[^;()]*?\bfrom\s*["']([^"']+)["']/g,
  /\bimport\s*\(\s*["']([^"']+)["']/g,
  /(^|[;{}\n])\s*import\s+["']([^"']+)["']/g,
];

function importsOf(source) {
  const code = withoutComments(source);
  const found = [];

  for (const pattern of SPECIFIER_PATTERNS) {
    pattern.lastIndex = 0;
    for (let match; (match = pattern.exec(code)) !== null; ) {
      const specifier = match[2] ?? match[1];
      // A statement can span lines, so the line is counted to the specifier rather than to the
      // start of the match: pointing at `import {` five lines above is not a location.
      const at = match.index + match[0].lastIndexOf(specifier);
      found.push({ specifier, line: code.slice(0, at).split("\n").length });
    }
  }

  return found.sort((a, b) => a.line - b.line);
}

/** `@scope/name/deep/path` is the `@scope/name` package; `name/deep` is `name`. */
function packageOf(specifier) {
  const parts = specifier.split("/");
  return specifier.startsWith("@") ? parts.slice(0, 2).join("/") : parts[0];
}

function declared(manifest) {
  return new Set([
    ...Object.keys(manifest.dependencies ?? {}),
    ...Object.keys(manifest.devDependencies ?? {}),
    ...Object.keys(manifest.peerDependencies ?? {}),
  ]);
}

function sourcesUnder(dir) {
  const files = [];
  const walk = (at) => {
    for (const entry of readdirSync(at, { withFileTypes: true })) {
      const path = join(at, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (/\.tsx?$/.test(entry.name)) files.push(path);
    }
  };
  if (statSync(dir, { throwIfNoEntry: false })?.isDirectory()) walk(dir);
  return files.sort();
}

const root = JSON.parse(readFileSync(join(frontend, "package.json"), "utf8"));
const rootDeps = declared(root);

const packages = readdirSync(packagesDir, { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => {
    const dir = join(packagesDir, entry.name);
    const manifest = JSON.parse(readFileSync(join(dir, "package.json"), "utf8"));
    return { dir, manifest, name: manifest.name, deps: declared(manifest) };
  });

const workspaceNames = new Set(packages.map((p) => p.name));
const isFeature = (name) => name.startsWith("@kui/feature-");

const violations = [];
let scanned = 0;

for (const pkg of packages) {
  for (const file of sourcesUnder(join(pkg.dir, "src"))) {
    scanned++;
    const source = readFileSync(file, "utf8");
    const where = relative(frontend, file);

    for (const { specifier, line } of importsOf(source)) {
      const report = (reason) => violations.push({ where, line, specifier, reason });

      if (specifier.startsWith(".")) {
        // Resolved against the file, not the package: `../../shell/src/App` is the same edge as
        // naming `@kui/shell`, and only this check sees it.
        const target = resolve(dirname(file), specifier);
        if (target !== pkg.dir && !target.startsWith(pkg.dir + sep)) {
          report(`reaches outside ${pkg.name}, which is a package boundary`);
        }
        continue;
      }
      if (specifier.startsWith("node:")) continue;

      const from = packageOf(specifier);
      if (from === pkg.name) continue;

      if (isFeature(pkg.name) && isFeature(from)) {
        report("a feature may not import another feature; share it through @kui/kernel instead");
        continue;
      }
      if (from === "@kui/shell") {
        report("nothing may import @kui/shell; the shell composes the features, not the reverse");
        continue;
      }
      if (!pkg.deps.has(from) && !rootDeps.has(from)) {
        const kind = workspaceNames.has(from) ? "workspace package" : "dependency";
        const manifest = relative(frontend, join(pkg.dir, "package.json"));
        report(`undeclared ${kind}: add ${from} to ${manifest}`);
      }
    }
  }
}

if (violations.length === 0) {
  console.log(`✓ ${scanned} files in ${packages.length} packages: no boundary violations.`);
  process.exit(0);
}

for (const { where, line, specifier, reason } of violations) {
  console.log(`✗ ${where}:${line}  "${specifier}"`);
  console.log(`  ${reason}`);
}
console.log(`\n✗ ${violations.length} boundary violation${violations.length === 1 ? "" : "s"}.`);
process.exit(1);
