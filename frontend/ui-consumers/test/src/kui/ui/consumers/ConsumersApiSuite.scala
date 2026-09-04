package kui.ui.consumers

import io.circe.parser.decode
import munit.FunSuite
import sttp.client4.UriContext
import sttp.tapir.client.sttp4.SttpClientInterpreter

import kui.consumer.contract.dto.{GroupDetailDto, GroupPageDto, LagDeltaDto}
import kui.consumer.contract.{ConsumerEndpoints, GoldenDocuments, GroupListParams}
import kui.contracts.PublicApi
import kui.contracts.consumer.{AssignmentFreshness, GroupSortField}
import kui.kernel.group.{GroupState, LagAnomaly}
import kui.kernel.{ClusterId, GroupId, SortOrder}

/** The browser half of the seam.
  *
  * ## What this suite is for
  *
  * Two failures, and both of them are silent.
  *
  * The first is a **path** that drifts. A segment renamed in `services/consumer/contract` while this module
  * keeps calling the old address compiles perfectly — a segment is a `String` — and 404s in production. The
  * first half of this suite asserts every URL against the contract's own constants, so a rename cannot pass.
  *
  * The second is a **document** nobody sends. In M1 the dashboard declared `ClustersResponse` while the
  * gateway sent `ClusterOverviewDto`; the decoder defaulted a missing `items` to `Nil`, every response
  * decoded successfully into zero rows, and the page rendered "No clusters yet" under a "last updated just
  * now" timestamp against a working broker, with no error anywhere. Both modules' own suites were green,
  * because each tested itself against its own idea of the payload.
  *
  * So the second half decodes `GoldenDocuments` — the consumer contract's *committed* sample documents, the
  * same constants the contract's own suite asserts against and the same bytes its JVM `GoldenFilesSuite`
  * proves are the files on disk. They are read from the contract's test module rather than copied into this
  * one on purpose: a copy is a second belief about what the server sends, free to drift, which is precisely
  * the thing that failed.
  */
final class ConsumersApiSuite extends FunSuite {

  private val base = uri"https://kui.example"
  private val interpreter = SttpClientInterpreter()

  private def pathOf[I](endpoint: sttp.tapir.PublicEndpoint[I, ?, ?, Any], input: I): String =
    interpreter.toRequest(endpoint, Some(base))(input).uri.toString.stripPrefix(base.toString)

  private val cluster = ClusterId.from("prod-eu").getOrElse(fail("`prod-eu` should be a legal cluster id"))
  private val group = GroupId.from("orders-indexer").getOrElse(fail("a plain group id should be legal"))

  private val defaultParams =
    GroupListParams(
      states = Set.empty,
      q = None,
      sort = GroupSortField.Default,
      direction = SortOrder.Asc,
      page = ConsumerEndpoints.DefaultPage,
      pageSize = ConsumerEndpoints.DefaultPageSize
    )

  // --- The addresses ---------------------------------------------------------------------------

  test("everyCallIsUnderThePublicPrefix") {
    // `/api/v1`, never `/internal/v1`. The internal prefix belongs to the service and a browser that reached
    // for it would be asking the gateway for an address it does not serve — and would be sending a request
    // that expects a signed principal header no browser may mint.
    val paths = List(
      pathOf(ConsumersApi.list, (cluster, defaultParams)),
      pathOf(ConsumersApi.detail, (cluster, group)),
      pathOf(ConsumersApi.lag, (cluster, Set.empty[GroupId], None))
    )
    paths.foreach(path => assert(path.startsWith(PublicApi.Prefix), path))
  }

  test("theListPathIsTheContractsOwnSegments") {
    assertEquals(
      pathOf(ConsumersApi.list, (cluster, defaultParams)).takeWhile(_ != '?'),
      s"${PublicApi.Prefix}/${ConsumerEndpoints.ClustersSegment}/${cluster.value}" +
        s"/${ConsumerEndpoints.GroupsSegment}"
    )
  }

  test("theDetailPathIsTheListPathPlusTheGroupId") {
    assertEquals(
      pathOf(ConsumersApi.detail, (cluster, group)),
      s"${PublicApi.Prefix}/${ConsumerEndpoints.ClustersSegment}/${cluster.value}" +
        s"/${ConsumerEndpoints.GroupsSegment}/${group.value}"
    )
  }

  test("theLagPathSitsBesideTheGroupsAndNotUnderAGroupId") {
    // `.../consumer-groups/lag`, which is why `lag` may never be a legal group id: a group called `lag` would
    // otherwise shadow the delta endpoint. The contract owns that decision and this asserts we follow it.
    assertEquals(
      pathOf(ConsumersApi.lag, (cluster, Set.empty[GroupId], None)).takeWhile(_ != '?'),
      s"${PublicApi.Prefix}/${ConsumerEndpoints.ClustersSegment}/${cluster.value}" +
        s"/${ConsumerEndpoints.GroupsSegment}/${ConsumerEndpoints.LagSegment}"
    )
  }

  test("theListQueryStringUsesTheContractsParameterNames") {
    val query = pathOf(
      ConsumersApi.list,
      (
        cluster,
        defaultParams.copy(
          states = Set(GroupState.Stable),
          q = Some("orders"),
          sort = GroupSortField.Lag,
          direction = SortOrder.Desc,
          page = 3
        )
      )
    ).dropWhile(_ != '?')

    // The names, not the shape: a renamed parameter is answered by the server with a 400 rather than by
    // being ignored, so this is the difference between a working screen and an error on the one this
    // milestone is judged on.
    assert(query.contains(s"${ConsumerEndpoints.StateParam}=${GroupState.Stable.wire}"), query)
    assert(query.contains(s"${ConsumerEndpoints.QueryParam}=orders"), query)
    assert(query.contains(s"${ConsumerEndpoints.SortParam}=${GroupSortField.Lag.wire}"), query)
    assert(query.contains(s"${ConsumerEndpoints.DirectionParam}=${SortOrder.Desc.wire}"), query)
    assert(query.contains(s"${ConsumerEndpoints.PageParam}=3"), query)
  }

  test("aGroupIdWithASlashIsEncodedRatherThanSplitIntoSegments") {
    // Kafka permits both, and a naive path builder turns `a/b` into two segments and 404s. The path codec
    // the contract supplies is what prevents it, and this is the assertion that says we use it.
    val awkward = GroupId.unsafe("a/b c%d")
    val path = pathOf(ConsumersApi.detail, (cluster, awkward))
    assert(!path.contains("a/b"), path)
    assert(path.contains("a%2Fb"), path)
  }

  test("everyEndpointIsInTheAllList") {
    // A client written and then forgotten here is a client no suite walks.
    assertEquals(ConsumersApi.all.size, 4)
  }

  // --- The recorded documents ------------------------------------------------------------------

  test("theRecordedGroupPageDecodes") {
    val decoded = decode[GroupPageDto](GoldenDocuments.groupPage)
      .fold(error => fail(s"the contract's own list document did not decode: $error"), identity)

    assertEquals(decoded.items.map(_.groupId.value), List("orders-indexer", "billing-replay"))
    assertEquals(decoded.page.totalItems, Some(2L))
    // The row the whole screen turns on: a lag that is genuinely unknown, arriving as null and staying
    // absent rather than becoming a zero somewhere between the wire and the table.
    assertEquals(decoded.items.map(_.totalLag), List(Some(1240L), None))
    assertEquals(decoded.items.map(_.state), List(GroupState.Stable, GroupState.Empty))
    assertEquals(decoded.items.last.excludedPartitions, 3)
    assert(decoded.items.last.incomplete.exists(_.note.contains("no leader")))
  }

  test("theRecordedGroupDetailDecodesWithItsAnomaliesIntact") {
    val decoded = decode[GroupDetailDto](GoldenDocuments.groupDetail)
      .fold(error => fail(s"the contract's own detail document did not decode: $error"), identity)

    assertEquals(decoded.groupId.value, "orders-indexer")
    assertEquals(decoded.state, GroupState.PreparingRebalance)
    assertEquals(decoded.assignments.status, AssignmentFreshness.LastSeen)
    assertEquals(decoded.members.map(_.clientId), List("orders-indexer"))
    assert(decoded.members.forall(_.rebalancing))

    val partitions = decoded.topics.flatMap(_.partitions)
    assertEquals(partitions.map(_.partition), List(0, 1, 2))
    // Two partitions with no lag and two different reasons. Neither may become a zero, and neither may
    // become the other: "never committed" and "committed past the end of the log" call for different acts.
    assertEquals(partitions.map(_.lag), List(Some(1240L), None, None))
    assertEquals(
      partitions.map(_.anomalies),
      List(Nil, List(LagAnomaly.NoCommit), List(LagAnomaly.CommittedBeyondEnd))
    )
  }

  test("theRecordedLagDeltaDecodesAndKeepsItsToken") {
    val decoded = decode[LagDeltaDto](GoldenDocuments.lagDelta)
      .fold(error => fail(s"the contract's own delta document did not decode: $error"), identity)

    // The token is the server's, opaque, and round-trips untouched. A browser that reinterpreted it — or
    // sent its own clock instead — would silently drop or replay updates whenever the two clocks disagreed.
    assert(decoded.token.nonEmpty, decoded.token)
    assert(decoded.nextPollMs > 0L, decoded.nextPollMs.toString)
  }

  test("aTruncatedDocumentFailsToDecodeRatherThanBecomingAnEmptyPage") {
    // The M1 defect, as an assertion. `page` is required, so a document missing it is an error and not a
    // successful decode into zero rows under a "last updated just now" timestamp.
    val truncated = """{"items": []}"""
    assert(decode[GroupPageDto](truncated).isLeft, "a page document with no `page` must not decode")
  }

  test("anErrorEnvelopeIsNotAGroupPage") {
    val envelope =
      """{"code":"KUI-UPSTREAM-UNAVAILABLE","message":"prod-eu is unreachable","details":[],
        |"correlationId":"abc","retryable":true,"timestamp":"2026-09-04T09:15:00Z"}""".stripMargin
    assert(decode[GroupPageDto](envelope).isLeft, "an error envelope must not decode as a page of groups")
  }
}
