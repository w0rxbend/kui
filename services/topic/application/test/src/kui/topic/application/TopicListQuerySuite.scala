package kui.topic.application

import munit.FunSuite

/** The search box the user has just emptied.
  *
  * `TopicListQuery.term`'s own comment states the rule — "a blank `q` is not a filter that matches nothing;
  * it is the search box the user has just emptied, and it must give the whole list back" — and nothing
  * asserted it. Dropping the `.filter(_.nonEmpty)` left `./mill services.topic.__.test` at 287/287 green,
  * after which clearing the box and pressing enter answers an empty topic list on a cluster with four
  * thousand topics.
  */
final class TopicListQuerySuite extends FunSuite {

  test("a blank search term is no search at all") {
    assertEquals(TopicListQuery.default.copy(q = Some("   ")).term, None)
    assertEquals(TopicListQuery.default.copy(q = Some("")).term, None)
    assertEquals(TopicListQuery.default.copy(q = None).term, None)
  }

  test("a real search term survives, trimmed") {
    assertEquals(TopicListQuery.default.copy(q = Some("  orders ")).term, Some("orders"))
  }

  test("the list a screen asks for before the user has done anything names no sort") {
    // `sort = None` is not `name:asc`: in `fts` mode the difference decides between relevance order and
    // alphabetical, and only the edge can see whether the parameter was in the query string at all.
    assertEquals(TopicListQuery.default.sort, None)
    assertEquals(TopicListQuery.default.showInternal, false)
  }
}
