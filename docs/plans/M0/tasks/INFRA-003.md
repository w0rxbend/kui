# INFRA-003 — Developer loop: dev server and README

- **ID:** INFRA-003
- **Title:** Developer loop: dev server and README
- **Milestone / Feature:** M0 / KU-008
- **Owner role:** Infrastructure Lead
- **Size:** S
- **Dependencies / blocked by:** UI-009, INFRA-002

## Goal (user value)

A contributor clones the repository and is looking at the running UI, with frontend changes
appearing in seconds, in under ten minutes — the twelfth item of the milestone definition of
done.

## Scope

1. `./mill dev` — a task that runs the all-in-one process with
   `webAssetsDev` (fastLinkJS output served directly from the linker directory), so a
   `./mill frontend.uiShell.fastLinkJS` plus a browser refresh is the whole frontend loop.
   No Vite, no proxy needed, because the assets and the API share an origin (ADR-012).
2. `./mill devWatch` — the same with `--watch` on the frontend link.
3. The component gallery route from UI-003 is enabled only in dev mode.
4. `README.md` quick start, written for someone who has never seen the project:
   prerequisites, clone, `./mill dev`, open the URL, where to change a component, how to run
   one test, how to run the fault-isolation demo, where the docs live.
5. `CONTRIBUTING.md`: the commit convention (Conventional Commits, PLAN §51), the quality
   gates a task must pass (PLAN §50), the task-spec workflow, and the rule that scratch files
   never enter the repository.

## Non-goals

No hot module replacement (Scala.js has none; a refresh is the loop). No IDE-specific
configuration beyond documenting `./mill mill.bsp.BSP/install` for Metals.

## Design references

ADR-012 amendment 1 (the gateway serves the `fastLinkJS` output itself, so assets and API
share an origin and no proxy is needed),
`research/scala/frontend-research.md` §4 "Recommended M0 setup", PLAN §51.

## Files to create

```
build.mill                (dev, devWatch tasks)
README.md
CONTRIBUTING.md
docs/frontend/README.md   (completed with the dev loop section)
```

## Acceptance criteria

Measured literally, on a machine with only JDK 21, Docker and git:

```
$ git clone <repo> && cd kui
$ ./mill dev
[INFO] all-in-one listening on 0.0.0.0:8080 (dev assets from out/frontend/uiShell/fastLinkJS.dest)
$ open http://localhost:8080/ui/
# edit frontend/ui-shell/src/.../Header.scala
$ ./mill frontend.uiShell.fastLinkJS      # a few seconds
# refresh the browser: the change is visible
```

Time the whole sequence and record it in the Implementation Report; if it exceeds ten minutes
on a cold cache, say where the time went so M1 can improve it.

## Tests required

None (documentation and build ergonomics). The verification is the timed walkthrough above,
performed by someone who did not write the task — the QA Engineer.

## Observability

`./mill dev` sets `telemetry.logFormat = text` so the console is readable, and disables OTLP
export by default so no exporter warnings clutter a first run.

## Degraded behavior

If `fastLinkJS` output is missing, `./mill dev` runs it first rather than serving a broken
page.

## Docs to update

`README.md` and `CONTRIBUTING.md` are the deliverables.

## Deviations

### 1. `./mill devWatch` became `./mill devStart` plus Mill's own watch

The spec asks for `./mill devWatch` — "the same with `--watch` on the frontend link" — as a single
task. It cannot be one task, and the reason is a property of the build tool rather than a design
choice: **Mill serialises its invocations on the output directory.** A `./mill dev` holding the
terminal also holds that lock, so `./mill frontend.uiShell.fastLinkJS` in a second terminal prints
"Another Mill process is running, waiting for it to be done" and waits instead of linking. A task
that tried to run both would deadlock against itself.

What works, and what is documented instead:

```
./mill devStart                            # the server, detached — releases the lock
./mill -w frontend.uiShell.fastLinkJS      # Mill's own watch, in the foreground
./mill devStop
```

`./mill dev` is kept exactly as the acceptance criteria describe it — foreground, log on screen,
Ctrl-C to stop — because that is the right thing for the "clone it and look at the UI" path the task
is really about. `devStart` and `devStop` are the editing path.

### 2. The dev assets are served through the classpath, not through a new server option

The spec describes a `webAssetsDev` mode. No Scala changed: `StaticRoutes` already serves `/ui/…`
from the classpath under `/web`, so the dev tasks put two directories at the front of the classpath,
each containing a symbolic link named `web` pointing at a linker output directory
(`fastLinkJS.dest` and `css.dest`). A classloader takes the first entry that has the file, so
`main.js` and `kui.css` resolve to whatever the linker most recently wrote and `index.html` falls
through to the gateway's own resources.

This is better than a code path for it would have been. There is no development-only branch in the
server to diverge from the production one, nothing to configure, and no copy step between "the
linker wrote a file" and "the server can see it" — which is the step that makes a frontend loop feel
slow.

### 3. `index.html` now links the stylesheet

The committed template referenced `main.js` and nothing else, so the shell rendered unstyled even
when `kui.css` was being served. `<link rel="stylesheet" href="kui.css">` was added.

**A release build still needs `kui.css` and `main.js` bundled into the gateway's `web/` resources.**
Nothing does that yet; the dev tasks are the only thing that puts them where the server can see
them. That packaging step belongs to AIO-002 / UI-012, and until it exists a container image serves
the template with neither file, which the SPA fallback turns into an unstyled page rather than a
failure.

### 4. The dev-only component gallery was not gated

Scope item 3 — "the component gallery route from UI-003 is enabled only in dev mode" — is not done.
Gating it needs a `devMode` flag in the bootstrap block the gateway injects into `index.html` *and*
a change to `Navigation.scala` and `ShellRouter.scala` to read it. Those two files were being
actively rewritten in the parallel UI lane while this task ran, and a change to them from here would
have been a merge conflict at best and a silent revert of their work at worst.

**Handed to the UI lane**, which owns those files: add `devMode: Boolean` to `BootstrapConfig` and
`IndexHtml.render`, have the composition root set it, and filter `ShellPage.Gallery` out of
`Navigation.items` and `ShellRouter.routes` when it is false. The gallery is harmless in the
meantime — it is a page of components, not a privileged action.

### 5. The timed walkthrough

Measured on this machine (16 cores, warm Coursier cache), from `./mill dev` to the UI answering:

- **warm build**: 2 seconds
- **after `./mill clean` of the frontend and the all-in-one modules**: 20 seconds
- **a re-link after editing one file**: 6 seconds, and nothing restarts

Well inside the ten-minute budget. The part not measured is a genuinely cold clone, which is
dominated by dependency download rather than compilation: the Coursier cache this project fills is
about 9.7 GB across all its modules, so on a slow connection that download, not the build, is the
whole of the ten minutes. `./mill resolveAll` exists to make that step explicit and to fail fast if
a version is wrong.

The spec asks for the walkthrough to be performed by someone who did not write the task. That is
still owed: these numbers are the author's, on a warm machine, and the QA Engineer's run is the one
that counts.
