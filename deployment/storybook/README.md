# Storybook, in a container

The component workshop: every control and every screen, in every state, in both themes, with no
Kafka cluster and no backend anywhere.

```bash
docker compose -f deployment/storybook/docker-compose.storybook.yml up --build
open http://localhost:6006
```

The first build takes a few minutes — it installs the pnpm workspace and builds the static site.
Afterwards `up` reuses the image; add `--build` when the frontend has changed.

```bash
docker compose -f deployment/storybook/docker-compose.storybook.yml down
```

`KUI_STORYBOOK_PORT=7007 docker compose … up` moves it off 6006 if something else is there.

## What it is for

This is where this frontend is reviewed, and it exists as a container so that reviewing it does not
require installing Node and pnpm at the exact pinned versions first.

The states worth looking at are the ones you cannot reach in a running product without breaking
something on purpose:

| Story | What it shows |
| --- | --- |
| `Surfaces/StatTile → The Four Absences` | `0`, a skeleton, `—` and "not measured", side by side. Four different statements that must never look alike — `—` means retrying might help, "not measured" means it never will. |
| `Chrome/EnvRail → Same First Letter` | Three environments whose names all begin with `P`, so all three tiles are identical. Hover them: the tooltip is the only thing telling them apart, which is why it is not optional. |
| `Chrome/StorageMeter → One Hot Broker` | Why the meter is one segment per broker and not one averaged bar. Compare with `Balanced`: the overall figure is nearly the same and the pictures are not. |
| `Chrome/Notifications → Empty` | The panel still opens and says there is nothing. A bell that opens nothing cannot be told from a broken bell. |
| `Shell/AppFrame → Cluster Unreachable` | The frame still draws. Every destination stays reachable; the drawer's foot swaps the storage meter for a retry. |
| `Charts/SegmentBar → Against A Progress Bar` | "2 of 6 tasks failed" drawn both ways. One says *which* two; the other cannot. |

## It talks to nothing

No backend, no Kafka, no outbound request at all. Every story renders from fixtures by design: a
story that fetched could only be looked at while the thing it fetched from was healthy, which is the
opposite of what a workshop is for.

That is also why this is a separate compose file from `deployment/quickstart/`. Different audiences
and different lifetimes — the quickstart is "show me the product working against a real cluster",
this is "show me every state the product can be in". One file would make a designer reviewing a
disabled button wait for Kafka to become healthy, and an operator trying the product build a
Storybook they will never open.

## Checking accessibility

The same stories can be swept with axe, in both themes, from a checkout:

```bash
cd frontend && pnpm storybook          # in one terminal
node scripts/a11y-stories.mjs          # in another
node scripts/a11y-stories.mjs 'chrome-' # or just some of them
```

It exits non-zero on a violation. Point it at the container instead with
`SB=http://localhost:6006 node scripts/a11y-stories.mjs`.

## Notes for whoever maintains this

Three things in here were found by building it rather than by reasoning about it, and each would
otherwise be rediscovered:

- **pnpm is installed with `npm i -g`, not corepack.** The corepack bundled with Node 22.12/22.13
  carries signing keys npm no longer matches, and every invocation dies with
  `Cannot find matching keyid`.
- **The Node pin had to move to 22.13.0.** pnpm 11.25 refuses to start on anything older, and
  `.tool-versions` said 22.12.0 — a combination that had never been tried, because every developer's
  machine happened to run something newer than the file asked for.
- **The base image is `nginx-unprivileged`.** Stock nginx starts as root and chowns its cache
  directories, which `cap_drop: ALL` forbids; the container restart-loops with a message that looks
  nothing like a permissions problem.
