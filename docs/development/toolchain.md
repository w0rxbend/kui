# Toolchain

What you need installed to build KUI, and how the build finds it.

## Required

| Tool | Version | Why |
| --- | --- | --- |
| JDK | 21 | The runtime every service targets. Newer versions are not tested; some libraries pin their own floor, recorded in `DEPENDENCY_MATRIX.md`. |
| Mill | see `.mill-version` | The build tool. The `./mill` script in the repository root downloads the pinned version on first use, so you do not have to install it yourself. |
| Docker | any recent | Integration tests start real Kafka and friends in containers, and the development environment runs from Compose. |
| Node.js | 20 or newer | Only for the browser-facing modules: Scala.js compiles to JavaScript, and its tests run under Node. Nothing on the server side needs it. |

## Node is not on the default path

Mill runs Scala.js tests by invoking `node`, and it looks for it on the `PATH` of the process
that started the build. If you manage Node through a version manager, a plain shell will not
have it, and the failure looks like a missing binary rather than a missing setup step.

Load your version manager before building, or point the build at a specific binary:

```bash
# with nvm
. "$NVM_DIR/nvm.sh" && nvm use --lts

# or, without changing your shell
export PATH="$HOME/.nvm/versions/node/<version>/bin:$PATH"
```

Verify with `node --version` before running any task whose name contains `js`.

## The DOM test suites need jsdom

Some frontend suites render real elements and therefore need a `document`. Plain Node has none, so
those suites run under [jsdom](https://github.com/jsdom/jsdom), a `document` implemented in
JavaScript. It is an npm package, and it is not something Mill can download for you.

Install it into a `node_modules` directory at the repository root:

```bash
cd <repository root>
npm install --no-save jsdom
```

The root is where it has to be. Mill runs the test binary from a sandbox directory underneath the
repository, and Node finds a package by walking up from there looking for `node_modules`, so a
package installed at the root is found and one installed anywhere else is not. `node_modules/` is
git-ignored, so this is a per-checkout setup step and nothing to commit.

A global install (`npm install -g jsdom`) is not enough on its own, because a global package is not
on Node's lookup path. Exporting `NODE_PATH="$(npm root -g)"` does make it work, but only if the
variable is set *before Mill's background daemon starts* — the daemon passes its own environment to
the `node` process it forks, so exporting it and then running a task against an already-running
daemon has no effect. The local install avoids the whole question.

The symptom of getting this wrong is `Error: Cannot find module 'jsdom'` from inside
`codeWithJSDOMContext.js`, with the run exiting before any test starts.

Server-side work needs none of this. `./mill libs.kernel.jvm.test` and everything else on the
JVM runs without Node present.

## Checking your setup

```bash
java -version      # expect 21
./mill --version   # expect the version in .mill-version
docker info        # expect a running daemon
node --version     # only needed for browser modules
```
