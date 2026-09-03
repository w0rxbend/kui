# KERN-003 — `libs/kernel`: paging and sorting primitives

- **ID:** KERN-003
- **Title:** `libs/kernel`: paging and sorting primitives
- **Milestone / Feature:** M0 / foundation for ADR-026 (M2, M3)
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/kernel`
- **Size:** S
- **Dependencies / blocked by:** KERN-001

## Goal (user value)

Every list endpoint in KUI pages and sorts the same way, and the off-by-one bug the reference
implementation has (page count computed before the filter, ADR-026 context) is impossible
because the page arithmetic lives in one tested place.

## Scope

`PageSize`, `PageRequest`, `Page[A]`, `PageToken`, `SortOrder`, `Sort[Field]`, and the pure
paging function that turns a fully filtered list into a `Page`.

Defaults from ADR-026: `pageSize` default 25, maximum 500, `page` is 1-based.

## Non-goals

**No cursor implementation** — signed cursors are ADR-026's other half and belong to M3
(`services/message`). `PageToken` is an opaque `String` wrapper here, nothing more. No sorting
comparators for domain types. No `NameIndex` / search (M2, ADR-038).

## Design references

ADR-026 ("Sorted lists" half only), `ARCHITECTURE.md` §4.1 and §8,
`docs/domain/context-map.md` §"Shared-kernel type list".

## Files to create

```
libs/kernel/src/kui/kernel/paging.scala
libs/kernel/test/src/kui/kernel/PagingSuite.scala
```

## Public Scala signatures to implement

```scala
package kui.kernel

enum SortOrder { case Asc, Desc }

final case class Sort[Field](field: Field, order: SortOrder)

opaque type PageSize = Int
object PageSize:
  val Default: PageSize
  val Max: PageSize                                  // 500
  def from(n: Int): Either[ValidationError, PageSize]
  extension (p: PageSize) def value: Int

final case class PageRequest(page: PositiveInt, pageSize: PageSize)
object PageRequest:
  val Default: PageRequest                            // page 1, size 25
  def from(page: Int, pageSize: Int): Either[ValidationError, PageRequest]

opaque type PageToken = String
object PageToken:
  def from(raw: String): Either[ValidationError, PageToken]   // non-empty, <= 32 KiB
  extension (t: PageToken) def value: String

final case class Page[A](
    items: List[A],
    page: Int,
    pageSize: Int,
    totalItems: Option[Long],
    nextPageToken: Option[PageToken]
):
  def map[B](f: A => B): Page[B]

object Page:
  /** Cuts a page out of an already-filtered, already-sorted list. `totalItems` is the size of
    * that list, which is why filtering must happen before this call — the reference bug is
    * counting before filtering. Requesting a page past the end yields an empty page, not an
    * error. */
  def of[A](all: List[A], request: PageRequest): Page[A]

  def empty[A](request: PageRequest): Page[A]
```

## Library coordinates

None new.

## Acceptance criteria

```
$ ./mill libs.kernel.jvm.test
$ ./mill libs.kernel.js.test
```

## Tests required

- `PagingSuite` (unit + property, cross-compiled):
  - `pageOfPartitionsWithoutLoss` — property: concatenating every page of a list reproduces
    the list exactly, for any list and any valid page size.
  - `totalItemsCountsTheFilteredList` — the regression guard for the reference bug: build a
    list, filter it, page it, assert `totalItems` equals the filtered size.
  - `pagePastTheEndIsEmptyNotAnError`.
  - `pageSizeRejectsZeroAndAboveMax` — table test with the exact `ValidationError`.
  - `pageIsOneBased` — page 1 returns the first `pageSize` items.
  - `mapPreservesPaginationMetadata`.

## Observability / Degraded behavior

Not applicable.

## Docs to update

None.
