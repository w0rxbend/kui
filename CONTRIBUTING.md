# Contributing to KUI

Thank you for considering a contribution. This guide assumes no prior knowledge of the project.

## Getting it running first

```
(cd frontend && pnpm install && pnpm build)
./mill dev
```

The backend and the interface are two builds — Mill needs only a JDK, the interface is a pnpm
workspace under `frontend/` and needs only Node — so the first line builds the interface and the
second builds the backend and starts the whole product on <http://localhost:8080/ui/>. Skipping the
first line is not an error: `/ui/` then answers 503 and everything else works.

See the quick start in [README.md](README.md) for the edit-and-refresh loop and for the
fault-isolation demonstration, which is the fastest way to understand what this project is actually
for.

## Before you write code

KUI is planned in the open. Two documents decide most questions before they reach the code:

- [ARCHITECTURE.md](ARCHITECTURE.md) describes the services, what each one owns, and the rules
  about which module may depend on which.
- [docs/adr/](docs/adr/) records each significant decision. If you disagree with one, that is a
  legitimate discussion, but it belongs in a new decision record that supersedes the old one, not
  in a pull request that quietly does something else.

Work is tracked in [docs/FEATURE_MATRIX.md](docs/FEATURE_MATRIX.md) and scheduled in
[docs/ROADMAP.md](docs/ROADMAP.md).

### Before you start on something

Read these four, in this order. Together they are about an hour, and they answer most of the
questions a first change runs into:

1. [ARCHITECTURE.md](ARCHITECTURE.md) — what the services are, which module may depend on which,
   and why the dependency direction is the way it is.
2. [docs/FEATURE_MATRIX.md](docs/FEATURE_MATRIX.md) — whether the thing you want to build already
   exists, is half built, or was deliberately rejected. Rows are set by reading the code and
   driving the running application, so a row that says `RESEARCHING` really does mean no code.
3. [docs/adr/](docs/adr/) — the decision behind whatever you are about to touch. Search it for the
   library or the mechanism by name before proposing a different one.
4. [docs/testing.md](docs/testing.md) — which kind of test your change needs, and how to run it.

If the change is larger than a bug fix, open an issue describing the behaviour you want before
writing the code. A design that disagrees with an accepted ADR is a legitimate proposal, but it
belongs in a new decision record that supersedes the old one — not in a pull request that quietly
does something else.

### Scratch files never enter the repository

Notes to yourself, exploratory scripts, sample output, a `TODO.md` you wrote while working — none of
it is committed. Stage explicitly (`git add <paths>`, never `git add -A`) and read
`git diff --cached --stat` before you commit. Use a directory outside the repository for working
files. The repository is read by people who were not there; anything in it should be something they
were meant to find.

## The shape of the code

Each service is split into six parts, and the split is enforced:

- `domain` holds the business types and rules. It depends on almost nothing, so it stays testable
  and stays honest. No HTTP, no Kafka client, no serialization library may appear here.
- `application` holds the use cases that combine domain rules to do something useful.
- `infrastructure` talks to the outside world: Kafka, HTTP APIs, directories. It translates their
  failures into the project's own error types at the boundary.
- `contract` defines the HTTP endpoints. It compiles for both the server and the browser, which is
  how the two stay in agreement.
- `api` connects the contract to the use cases and maps errors to responses.
- `app` is the only place that knows how all the pieces are wired together.

If a change feels like it belongs in two of these at once, that usually means a boundary is in the
wrong place. Say so in the pull request rather than working around it.

## Errors, resources, and streams

Three habits matter more than any style rule:

- Business code returns errors as values rather than throwing. Exceptions are caught where they
  enter the system and turned into typed errors immediately.
- Anything with a lifetime, a Kafka client, a server, a background worker, is acquired as a
  resource so it is always released, including on cancellation.
- Anything that could be large is streamed. If you find yourself collecting records into a list to
  return them, stop and stream instead.

## Tests

Every change carries the tests its task specification asks for. In practice:

- Rules and invariants get unit tests, and property-based tests where a rule should hold for all
  inputs rather than a chosen few.
- Adapters get integration tests against real infrastructure in containers, not mocks of it.
- Anything that can be unavailable gets a test that makes it unavailable and checks the rest still
  works. This is the project's central promise, so it is tested, not assumed.

Fakes are written by hand against the port they stand in for. The project does not use a mocking
framework.

## Commits and pull requests

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary in the imperative, lowercase>

<body: what changed, why, and what it means for anyone using this>
```

Always write a body, except for genuinely trivial changes. The diff already shows what changed;
the body explains why, for someone who was not there.

Keep commits small and self-contained. Each one should compile and pass its tests on its own. A
single task normally becomes several commits rather than one large one.

## Before you open a pull request

```
./mill __.compile        # must pass with warnings treated as errors
./mill __.test
./mill __.checkFormat
./mill __.fix --check
./mill checkArchitecture # no module dependency may break the layering rules of ADR-041
```

All five, every time. `checkArchitecture` is the one people forget, and it is the one that catches
the mistake nobody spots in review: a reviewer reads a diff, not a dependency graph, so an edge
added to a `build.mill` line is invisible until the structure it broke starts costing something.

Describe the change for a reviewer who has never seen this codebase: what it does, why, how it
works, and how to run it. Note anything surprising and anything you deliberately left out.

## Writing for other people

Documentation is part of the work, not a follow-up. When you explain something, assume the reader
is competent but new: spell out acronyms the first time, say what the code did before and what it
does now, and prefer a short concrete example to an abstract description. Avoid "simply",
"obviously" and "just": if it were obvious, the sentence would not be needed.
