# KERN-008 — Generated `docs/api/error-codes.md`

- **ID:** KERN-008
- **Title:** Generated `docs/api/error-codes.md`
- **Milestone / Feature:** M0 / OT-005
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/kernel`, docs
- **Size:** S
- **Dependencies / blocked by:** KERN-004

## Goal (user value)

An operator who sees `KUI-UPSTREAM-UNAVAILABLE` in a log line can look it up in one page that
is guaranteed to be complete, because it is generated from the enum rather than maintained by
hand.

## Scope

A Mill task `./mill docs.errorCodes` that renders every `ErrorCode` case to a Markdown table
(code, HTTP status, retryable, area, meaning) and writes `docs/api/error-codes.md`; plus a
`--check` mode used by CI that fails when the committed file differs from the generated one.

The "meaning" column comes from a Scaladoc comment on each enum case, extracted at build time
is over-engineering — instead, keep an explicit `description: String` field on `ErrorCode`
added in this task, so the table has one source and the compiler enforces completeness.

## Non-goals

No OpenAPI generation (GW-007). No i18n of messages (ADR-024: strings are centralized per
feature, English only).

## Design references

ADR-034 ("The full table lives in `docs/api/error-codes.md` and is generated from the
`ErrorCode` enum"), PLAN §35 (documentation requirements).

## Files to create or change

```
build.mill                                          (docs.errorCodes task)
libs/kernel/src/kui/kernel/error/ErrorCode.scala    (add `description`)
tools/error-codes/src/kui/tools/ErrorCodeDoc.scala  (the renderer, a plain function)
tools/error-codes/test/src/kui/tools/ErrorCodeDocSuite.scala
docs/api/error-codes.md                             (generated, committed)
```

## Public Scala signatures to implement

```scala
package kui.tools

object ErrorCodeDoc:
  /** Pure: enum values in, Markdown out. The Mill task only writes the file. */
  def render(codes: List[ErrorCode]): String
```

## Acceptance criteria

```
$ ./mill docs.errorCodes                 # writes docs/api/error-codes.md
$ ./mill docs.errorCodes --check         # exits 0 when the file is current
$ git diff --exit-code docs/api/error-codes.md
```

Adding a new `ErrorCode` case without regenerating must fail `--check` in CI. Demonstrate once
and record it in the Implementation Report.

## Tests required

- `ErrorCodeDocSuite` (unit): rendering a two-case list produces the exact expected Markdown
  (golden string); the table is sorted by code so diffs stay minimal; every case appears
  exactly once.

## Observability / Degraded behavior

Not applicable.

## Docs to update

`docs/api/error-codes.md` is the output. Add a line to its header saying it is generated and
must not be edited by hand.
