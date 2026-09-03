# Spike — pinning JVM Playwright and its browser build

- **Task:** BUILD-006, spike 3 (risk R-10)
- **Role:** QA Engineer
- **Date:** 2026-09-03

## Question

ADR-018 chose **JVM Playwright** (`com.microsoft.playwright`) for end-to-end tests: a Java library
that drives a real browser. It does not use whatever browser happens to be installed on the
machine; it downloads its own build of Chromium, and it only speaks to the revision it shipped with.
So there are two version numbers, not one, and CI has to install the browser explicitly before the
e2e job runs.

Which library version, which browser revision, and what is the exact install command?

## Method

1. Read the published versions from Maven Central's metadata for
   `com.microsoft.playwright:playwright`.
2. Resolve the newest one with coursier.
3. Run the browser installer: `java -cp <classpath> com.microsoft.playwright.CLI install chromium`,
   and record what it says it downloaded.
4. Run a smoke test in Scala 3.9.0: launch headless Chromium, set page content, read an element's
   text back, print the browser's own reported version, close the browser.

Environment: JDK 21 (Temurin 21.0.9), Linux x86-64.

## Findings

**Current release: 1.62.0** (the newest on Maven Central; the metadata's `<release>`).

**It resolves cleanly.** The artifact pulls `driver`, `driver-bundle`, `gson` and `opentest4j` — no
Scala dependency of any kind, which is why it can be used from Scala 3.9 without a cross-version
question.

**Browser install output:**

```
Chrome for Testing 151.0.7922.34 (playwright chromium v1234)
  downloaded to ~/.cache/ms-playwright/chromium-1234
Chrome Headless Shell 151.0.7922.34 (playwright chromium-headless-shell v1234)
  downloaded to ~/.cache/ms-playwright/chromium_headless_shell-1234
```

So the pair is **Playwright 1.62.0 ↔ Chromium build 1234 (Chrome for Testing 151.0.7922.34)**.
Download size: 184 MB plus 115 MB for the headless shell.

**Smoke test passes:**

```
browser=151.0.7922.34
title-text=KUI smoke
```

The browser reports the same version the installer named, so the library and the downloaded build
are the matched pair.

**One environment caveat, which is a development-machine issue and not a CI one.** On a
non-Debian-family Linux, Playwright prints:

```
BEWARE: your OS is not officially supported by Playwright; downloading fallback build for ubuntu24.04-x64.
Playwright Host validation warning:
  Host system is missing dependencies to run browsers.
```

It is a warning: the fallback build launched and the smoke test passed anyway. On the
`ubuntu-latest` GitHub runner the message does not appear, because that is the officially supported
platform the fallback build is built for.

## Decision taken

Pin, per BUILD-006's decision rule ("take the newest stable release that resolves and runs the smoke
navigation; pin it and its browser revision"):

```scala
val playwright        = "1.62.0"
val playwrightBrowser = "chromium 151.0.7922.34 (playwright build 1234)"
```

Both are in `build.mill`'s `Versions` object, and both are readable from outside the build as
`./mill show versions.playwright` and `./mill show versions.playwrightBrowser`.

No alternatives were evaluated: ADR-018 already rejected TypeScript Playwright with reasons, and
only the version number was open.

## The exact CI install command for E2E-001

The e2e job must install the browser before running any test, because the runner image has no
browser Playwright will talk to. From a Mill build the classpath comes from the e2e module:

```yaml
- name: Install the Playwright browser build
  run: |
    ./mill show e2e.runClasspath \
      | python3 -c 'import json,sys; print(":".join(p["path"] for p in json.load(sys.stdin)))' \
      > /tmp/e2e-cp
    java -cp "$(cat /tmp/e2e-cp)" com.microsoft.playwright.CLI install --with-deps chromium
```

`--with-deps` is the part that matters on a runner: it installs the shared libraries the browser
needs, which a fresh container image does not have. It needs root, which the GitHub-hosted runner
grants through passwordless `sudo`; the command above runs as the runner user and Playwright
escalates internally.

Cache `~/.cache/ms-playwright` keyed on the Playwright version, or the job pays the 300 MB download
on every run.

E2E-001 owns adding that step; this spike only supplies the command and the numbers.

## Consequence

- `DEPENDENCY_MATRIX.md`: the `com.microsoft.playwright` row gets `1.62.0`, and its open question
  row is closed.
- `Versions.playwright` and `Versions.playwrightBrowser` exist in `build.mill`, with a comment
  saying the two numbers move together.
- No `TECH_DEBT.md` entry.

## Confidence

**High** for the pin: resolved, installed and smoke-tested on the pinned JDK and Scala version.

**Medium** for the CI command, which is written from the install behaviour observed here and from
the Mill task shape, but has not been executed on a GitHub runner — E2E-001 will be the first run
that proves it, and it is the task that owns the e2e job.

## Deviation from the task specification

BUILD-006's acceptance criteria list `./mill show Versions.playwright`. That command cannot work:
`Versions` is a plain Scala object evaluated while the build definition compiles, and `show` can
only print a Mill *task*. The two numbers are therefore re-exported as tasks in a small `versions`
module, and the working command is `./mill show versions.playwright` (lowercase). The pinned values
themselves are unchanged and still live in one place.
