package kui.ui.topics

import io.circe.parser.decode
import munit.FunSuite
import sttp.client4.UriContext
import sttp.tapir.client.sttp4.SttpClientInterpreter

import kui.contracts.capability.ReasonCode
import kui.gateway.contract.TopicOverviewEndpoints
import kui.gateway.contract.dto.TopicOverviewDto
import kui.contracts.{PublicApi, Section}
import kui.kernel.search.SearchMode
import kui.kernel.{ClusterId, PageRequest, PageSize, PositiveInt, Sort, SortOrder, TopicName}
import kui.topic.contract.dto.*
import kui.topic.contract.{GoldenDocuments, TopicEndpoints, TopicListParams, TopicQueryCodecs, TopicSortField}

/** The browser half of the seam.
  *
  * ## What this suite is for
  *
  * Two failures, and both of them are silent.
  *
  * The first is a **path** that drifts. A segment renamed in `services/topic/contract` while this module
  * keeps calling the old address compiles perfectly — a segment is a `String` — and 404s in production. The
  * first half of this suite asserts every URL against the contract's own constants, so a rename cannot pass.
  *
  * The second is a **document** nobody sends. In M1 the dashboard declared `ClustersResponse` while the
  * gateway sent `ClusterOverviewDto`; the decoder defaulted a missing `items` to `Nil`, every response
  * decoded successfully into zero rows, and the page rendered "No clusters yet" under a "last updated just
  * now" timestamp against a working broker, with no error anywhere. Both modules' own suites were green,
  * because each tested itself against its own idea of the payload.
  *
  * So the second half decodes `GoldenDocuments` — the topic contract's *committed* sample documents, the same
  * constants the contract's own suite asserts against and the same bytes the JVM `GoldenFilesSuite` proves
  * are the files on disk. They are read from the contract's test module rather than copied into this one on
  * purpose: a copy is a second belief about what the server sends, free to drift, which is precisely the
  * thing that failed. The topic service's four read endpoints are proxied through the gateway unchanged
  * (`ARCHITECTURE.md` §5), so these bytes are what a browser actually receives.
  */
final class TopicsApiSuite extends FunSuite {

  private val base = uri"https://kui.example"
  private val interpreter = SttpClientInterpreter()

  private def pathOf[I](endpoint: sttp.tapir.PublicEndpoint[I, ?, ?, Any], input: I): String =
    interpreter.toRequest(endpoint, Some(base))(input).uri.toString.stripPrefix(base.toString)

  private val cluster = ClusterId.from("prod-eu").getOrElse(fail("`prod-eu` should be a legal cluster id"))
  private val topic = TopicName.from("orders").getOrElse(fail("`orders` should be a legal topic name"))

  private val defaultParams = TopicListParams.Default

  private val calls: List[(String, String)] = List(
    "list" -> pathOf(TopicsApi.list, (cluster, defaultParams)),
    "topic" -> pathOf(TopicsApi.topic, (cluster, topic)),
    "overview" -> pathOf(TopicsApi.overview, (cluster, topic)),
    "config" -> pathOf(TopicsApi.config, (cluster, topic)),
    "partitions" -> pathOf(TopicsApi.partitions, (cluster, topic)),
    "refresh" -> pathOf(TopicsApi.refresh, cluster)
  )

  test("everyClientTargetsThePublicPrefix") {
    // Every endpoint, not a hand-written list of them: adding a sixth without a test is not possible.
    assertEquals(calls.length, TopicsApi.all.length)
    calls.foreach { (name, path) =>
      assert(path.startsWith(PublicApi.Prefix), s"$name calls $path")
      assert(!path.contains("/internal"), s"$name calls $path")
    }
  }

  test("pathsAreBuiltFromTheContractsOwnSegments") {
    val topics = s"${PublicApi.Prefix}/${TopicEndpoints.ClustersSegment}/${cluster.value}/" +
      TopicEndpoints.TopicsSegment
    val one = s"$topics/${topic.value}"
    assertEquals(pathOf(TopicsApi.topic, (cluster, topic)), one)
    assertEquals(pathOf(TopicsApi.config, (cluster, topic)), s"$one/${TopicEndpoints.ConfigSegment}")
    assertEquals(pathOf(TopicsApi.partitions, (cluster, topic)), s"$one/${TopicEndpoints.PartitionsSegment}")
    assertEquals(pathOf(TopicsApi.refresh, cluster), s"$topics/${TopicEndpoints.RefreshSegment}")
    // The overview is the gateway's own path and its constants are the gateway contract's, spelled out
    // there rather than imported from the topic contract — so this is the browser-side half of the check
    // that the two agree. The JVM-side half is `TopicOverviewSuite` in services/gateway/api.
    assertEquals(
      pathOf(TopicsApi.overview, (cluster, topic)),
      s"$one/${TopicOverviewEndpoints.OverviewSegment}"
    )
    assert(pathOf(TopicsApi.list, (cluster, defaultParams)).startsWith(topics))
  }

  test("queryParametersAreEncodedIntoTheUrl") {
    val params = TopicListParams(
      q = Some("ord"),
      mode = SearchMode.Fts,
      showInternal = true,
      sort = Some(Sort(TopicSortField.Size, SortOrder.Desc)),
      page = PageRequest(PositiveInt.from(3).getOrElse(fail("3 is positive")), PageSize.unsafe(100))
    )
    val query = pathOf(TopicsApi.list, (cluster, params)).dropWhile(_ != '?')
    assert(query.contains(s"${TopicQueryCodecs.QParam}=ord"), query)
    assert(query.contains(s"${TopicQueryCodecs.ModeParam}=${SearchMode.Fts.wire}"), query)
    assert(query.contains(s"${TopicQueryCodecs.ShowInternalParam}=true"), query)
    assert(query.contains(s"${TopicQueryCodecs.PageParam}=3"), query)
    assert(query.contains(s"${TopicQueryCodecs.PageSizeParam}=100"), query)
    // `sort=size:desc` — one parameter, not two, so the state "direction given, field not" cannot be sent.
    assert(query.contains(s"${TopicQueryCodecs.SortParam}=size"), query)
    assert(query.contains(SortOrder.Desc.wire), query)
  }

  test("anAbsentSearchTermIsAbsentRatherThanEmpty") {
    // Not `?q=`, which the server would have to decide the meaning of — "match everything" and "match the
    // empty string" are different questions, and neither of them is what "the user typed nothing" means.
    val query = pathOf(TopicsApi.list, (cluster, defaultParams))
    assert(!query.contains(s"${TopicQueryCodecs.QParam}="), query)
    assert(!query.contains(s"${TopicQueryCodecs.SortParam}="), query)
  }

  test("topicNamesAreEncodedNotConcatenated") {
    // Topic names cannot contain a slash (`TopicName`'s own pattern), which is exactly why this is worth
    // asserting: the invariant is enforced somewhere else, and this proves the client does not silently
    // depend on it.
    val path = interpreter
      .toRequest(TopicsApi.topic, Some(base))((cluster, TopicName.unsafe("a b/c")))
      .uri
      .toString
    assert(!path.contains("a b/c"), path)
  }

  // --- The recorded documents ------------------------------------------------------------------

  test("theRecordedListResponseDecodes") {
    val decoded = decode[TopicsResponse](GoldenDocuments.topicsResponse)
      .fold(error => fail(s"the contract's own list document did not decode: $error"), identity)
    val page = decoded.topics.toOption.getOrElse(fail("the recorded section is ok and must carry a page"))
    assertEquals(page.items.map(_.name.value), List("orders", "payments.dlq"))
    assertEquals(page.page.totalItems, Some(2L))
    assertEquals(decoded.incompleteTopics, 1)
    // The row the whole screen turns on: a count that is genuinely unknown, arriving as null and staying
    // absent rather than becoming a zero somewhere between the wire and the table.
    assertEquals(page.items.map(_.messageCount), List(Some(1234567L), None))
  }

  test("theRecordedStaleResponseDecodesAndKeepsItsFetchedAt") {
    val decoded = decode[TopicsResponse](GoldenDocuments.topicsResponseStale)
      .fold(error => fail(s"the contract's own stale document did not decode: $error"), identity)
    decoded.topics match {
      case Section.Stale(data, fetchedAt, reason) =>
        assertEquals(data.items.map(_.name.value), List("orders"))
        assertEquals(fetchedAt.toString, "2026-09-03T10:11:12Z")
        assertEquals(reason, ReasonCode.UpstreamTimeout)
      case other => fail(s"a stale document must decode to a stale section, not $other")
    }
  }

  test("theRecordedUnavailableResponseCarriesItsReasonAndNotAnEmptyPage") {
    // The distinction the screen is built on. "No snapshot yet" must never arrive as a page of no rows.
    val decoded = decode[TopicsResponse](GoldenDocuments.topicsResponseUnavailable)
      .fold(error => fail(s"the contract's own unavailable document did not decode: $error"), identity)
    decoded.topics match {
      case Section.Unavailable(reason, message, _) =>
        assertEquals(reason, ReasonCode.UpstreamUnavailable)
        assert(message.contains("prod-eu"), message)
      case other => fail(s"an unavailable document must decode to an unavailable section, not $other")
    }
  }

  test("theRecordedDetailResponseDecodes") {
    val decoded = decode[TopicDetailResponse](GoldenDocuments.topicDetailResponse)
      .fold(error => fail(s"the contract's own detail document did not decode: $error"), identity)
    val detail = decoded.topic.toOption.getOrElse(fail("the recorded section is ok and must carry a topic"))
    assertEquals(detail.row.name.value, "orders")
    assertEquals(detail.row.messageCount, None)
    assertEquals(detail.partitions.map(_.partition.value), List(0, 1))
    // The leaderless partition. Null, not Kafka's node id -1, and no count for that partition.
    assertEquals(detail.partitions.map(_.leader.map(_.value)), List(Some(1), None))
    assertEquals(detail.partitions.map(_.messageCount), List(Some(617283L), None))
    assertEquals(decoded.partitionsTruncated, false)
  }

  test("theRecordedConfigResponseDecodes") {
    val decoded = decode[TopicConfigResponse](GoldenDocuments.topicConfigResponse)
      .fold(error => fail(s"the contract's own config document did not decode: $error"), identity)
    decoded.config.toOption.getOrElse(fail("the recorded section is ok")) match {
      case TopicConfigViewDto.Entries(values) =>
        assertEquals(values.map(_.name), List("cleanup.policy", "retention.ms"))
        assertEquals(values.map(_.value), List(Some("delete"), Some("604800000")))
      case other => fail(s"the recorded document is an entries view, not $other")
    }
  }

  test("theRecordedNotPermittedConfigIsItsOwnCaseAndNotAnEmptyTable") {
    // An empty table and "you may not look" call for different actions from the operator, and the remedy for
    // the second is an ACL change they will never think of if the screen says the first.
    val decoded = decode[TopicConfigResponse](GoldenDocuments.topicConfigNotPermitted)
      .fold(error => fail(s"the contract's own not-permitted document did not decode: $error"), identity)
    decoded.config.toOption.getOrElse(fail("the recorded section is ok")) match {
      case TopicConfigViewDto.NotPermitted(detail) => assert(detail.contains("describeConfigs"), detail)
      case other => fail(s"the recorded document is a not_permitted view, not $other")
    }
  }

  test("theRecordedPartitionsResponseDecodes") {
    val decoded = decode[PartitionsResponse](GoldenDocuments.partitionsResponse)
      .fold(error => fail(s"the contract's own partitions document did not decode: $error"), identity)
    val partitions = decoded.partitions.toOption.getOrElse(fail("the recorded section is ok"))
    assertEquals(partitions.map(_.partition.value), List(0))
    assertEquals(partitions.head.replicas.map(_.leader), List(true, false))
  }

  test("theRecordedRefreshResponseDecodes") {
    val decoded = decode[RefreshAcceptedDto](GoldenDocuments.refreshAccepted)
      .fold(error => fail(s"the contract's own refresh document did not decode: $error"), identity)
    assertEquals(decoded.clusterId.value, "prod-eu")
  }

  test("aDocumentThatIsNotATopicsResponseIsAFailureAndNotAnEmptyPage") {
    // The exact failure that turned M1's wrong document into a silently empty page. The dashboard declared
    // one type, the gateway sent another, every response decoded successfully into zero rows, and the screen
    // said "nothing here" against a working broker. What stops it here is that `topics` is required: a
    // document produced by any other encoder does not have it and cannot decode at all.
    val someoneElsesDocument = """{"clusters":{"status":"ok","data":[]},"generatedAt":"2026-09-03T10:11:12.000Z"}"""
    assert(decode[TopicsResponse](someoneElsesDocument).isLeft, "a foreign document must not decode")
  }

  test("aPageWithNoItemsFieldDecodesEmpty_recordingADisagreementWithThisTasksSpec") {
    // TOP-028 asks for this to be a decode *failure*. It is not, and the difference is deliberate on the
    // other side of the seam: `PageDto`'s codec defaults a missing `items` to `Nil` and argues, in its own
    // comment, that the default is safe because `page` is required — so a truncated or foreign document
    // still fails, as the test above shows.
    //
    // This assertion exists to make the disagreement visible rather than to bless it. If `PageDto` is ever
    // tightened, this test fails and is deleted, which is the point: an undocumented difference between two
    // tasks' beliefs about one decoder is exactly the kind of thing that is invisible from either side alone.
    val pageWithoutItems =
      """{"topics":{"status":"ok","data":{"page":{"page":1,"pageSize":25,"totalItems":2,""" +
        """"pageCount":1,"nextPageToken":null}},"fetchedAt":"2026-09-03T10:11:12.000Z"},""" +
        """"incompleteTopics":0}"""
    val decoded = decode[TopicsResponse](pageWithoutItems)
      .fold(error => fail(s"PageDto is documented as lenient here: $error"), identity)
    val page = decoded.topics.toOption.getOrElse(fail("the section is ok"))
    assertEquals(page.items, Nil)
    // And the inconsistency the leniency admits, stated out loud: no rows, and a total that says there are
    // two. The screen renders "2 topics" over an empty table.
    assertEquals(page.page.totalItems, Some(2L))
  }

  test("theOverviewDecodesToItsFiveSectionsWithFourOfThemHidden") {
    // The gateway's *own* type, not the topic service's. Decoding an aggregation against the owning
    // service's type is what made the M1 dashboard render "No clusters yet" against a working broker, so
    // this document is asserted here even though nothing else in this suite is the gateway's.
    val recorded =
      """{"topic":{"status":"ok","data":{"row":{"name":"orders","internal":false,"partitionCount":2,""" +
        """"replicationFactor":3,"outOfSyncReplicas":0,"offlinePartitions":0,"messageCount":10,""" +
        """"sizeBytes":2048},"partitions":[],"cleanupPolicy":"delete","segmentCount":4},""" +
        """"fetchedAt":"2026-09-03T10:11:12.000Z"},"consumerGroups":{"status":"not_configured"},""" +
        """"connectors":{"status":"not_configured"},"acls":{"status":"not_configured"},""" +
        """"schemas":{"status":"not_configured"},"generatedAt":"2026-09-03T10:11:12.000Z"}"""
    val decoded = decode[TopicOverviewDto](recorded)
      .fold(error => fail(s"the gateway's overview document did not decode: $error"), identity)
    assertEquals(decoded.topic.toOption.map(_.row.name.value), Some("orders"))
    // Four `not_configured` sections, which the screen hides rather than drawing as four red panels.
    assertEquals(
      TopicOverviewDto.statuses(decoded).filterNot(_._1 == TopicOverviewDto.TopicSection).values.toSet,
      Set("not_configured")
    )
  }

  test("aMissingTopicsSectionIsAFailureNotAnEmptySection") {
    val withoutTopics = """{"incompleteTopics":0}"""
    assert(decode[TopicsResponse](withoutTopics).isLeft, "a document with no topics section must not decode")
  }
}
