# INFRA-003 — Developer loop: dev server, proxy and README

- **ID:** INFRA-003
- **Title:** Developer loop: dev server, proxy and README
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

ADR-012 ("Dev loop: `fastLinkJS` output served statically with `/api` proxied to the gateway;
no Vite step" — and because the gateway serves the assets itself, no proxy is needed at all,
which is simpler than the ADR anticipated; record this simplification),
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
