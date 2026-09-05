# Toolchain

What you need installed to build KUI, and how each build finds it.

There are **two builds**, and that is the first thing to know. The backend is Scala 3 built with
Mill and it needs **nothing but a JDK** — no Node, no pnpm, no toolchain check. The interface is a
separate pnpm workspace under `frontend/`, built by Vite, and it needs **nothing but Node** — no
JDK. They are shipped as two container images and talk to each other over HTTP (ADR-048).

So the tools you install depend on which half you are working on, and neither half's setup can
break the other's.

## Required for the backend

| Tool | Version | Why |
| --- | --- | --- |
| JDK | 21 | The runtime every service targets. Newer versions are not tested; some libraries pin their own floor, recorded in `DEPENDENCY_MATRIX.md`. |
| Mill | see `.mill-version` | The build tool. The `./mill` script in the repository root downloads the pinned version on first use, so you do not have to install it yourself. |
| Docker | any recent | Integration tests start real Kafka and friends in containers, and the development environment runs from Compose. |

`./mill __.compile`, `./mill __.test`, `./mill checkArchitecture` and every other Mill task run with
those three and nothing else. If a Mill task ever asks for Node, that is a bug in the build file, not
a missing step here.

## Required for the frontend

| Tool | Version | Why |
| --- | --- | --- |
| Node.js | 22.13.0 or newer | Runs Vite, Vitest and pnpm itself. |
| pnpm | 11.25.0 exactly | The package manager the lockfile was written by. |

Both numbers are pinned in `.tool-versions`, in the format [asdf](https://asdf-vm.com) and
[mise](https://mise.jdx.dev) both read, and `frontend/package.json` repeats the pnpm one in its
`packageManager` field so pnpm fetches that exact version itself.

The floor is 22.13 rather than a rounder number for a specific reason recorded in `.tool-versions`:
pnpm 11.25 refuses to start on anything below it, with `This version of pnpm requires at least
Node.js v22.13`. That is above what Vite itself requires, so one number satisfies both.

Install pnpm with `npm install --global pnpm@11.25.0` rather than through corepack. The corepack
bundled with these Node builds carries signing keys npm no longer matches and fails with
`Cannot find matching keyid`, which is a message that sends people looking at their registry
configuration for a problem that is not there.

Then, from `frontend/`:

```bash
pnpm install          # once, and after a dependency change
pnpm build            # writes frontend/dist
pnpm test             # Vitest
pnpm typecheck        # tsc --build
```

### The DOM tests need no separate setup

Component suites render real elements and therefore need a `document`. Vitest provides one through
its `jsdom` environment, which is an ordinary dependency in the workspace and arrives with
`pnpm install`. There is nothing to install globally, nothing to place at the repository root, and
no `NODE_PATH` to export.

## Which half is a version manager's problem

If you manage Node through a version manager, a plain shell will not have it, and the failure looks
like a missing binary rather than a missing setup step:

```bash
# with nvm
. "$NVM_DIR/nvm.sh" && nvm use

# with mise, which reads .tool-versions directly
mise install && mise use
```

This only ever affects `pnpm` commands. Mill launches no Node process, so a Node that is invisible
to your shell cannot affect a Mill task, and there is no daemon environment to think about.

## Checking your setup

```bash
java -version      # expect 21          — backend
./mill --version   # expect the version in .mill-version
docker info        # expect a running daemon
node --version     # expect 22.13.0 or newer — frontend only
pnpm --version     # expect 11.25.0            — frontend only
```
