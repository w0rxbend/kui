package kui.topic.api

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.testkit.TestControl
import io.circe.Json
import io.circe.parser.parse
import cats.syntax.all.*
import munit.CatsEffectSuite
import sttp.client4.*
import sttp.model.StatusCode

import kui.cache.Snapshot
import kui.kernel.error.InfrastructureError
import kui.kernel.{BrokerId, ClusterId, PartitionId, TopicName}
import kui.topic.application.{Fresh, TopicCapability}
import kui.topic.domain.*

/** The five endpoints, through the real interceptor chain and the real routes.
  *
  * The suite is about two things and nothing else: what a caller sees, and what a caller sees when something
  * upstream is broken. Business rules — which topics match, how a page is cut — belong to
  * `services/topic/application` and are asserted there; a duplicate here would be a second opinion about a
  * rule with one owner.
  *
  * One assertion is deliberately made at two levels, here and in the application layer: that hiding internal
  * topics changes the *total* on the wire. That is the reference product's page-count defect, and the whole
  * value of catching it at the outermost layer as well is that the outermost layer is where a caller would
  * see it.
  */
final class TopicRoutesSuite extends CatsEffectSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")
  private val since = Instant.parse("2026-09-03T10:10:00Z")

  private def summary(name: String, internal: Boolean, partitions: Int = 1): TopicSummary =
    TopicSummary(
      name = TopicName.unsafe(name),
      isInternal = internal,
      partitionCount = partitions,
      replicationFactor = Some(3),
      outOfSyncReplicas = 0,
      offlinePartitions = 0,
      messageCount = Some(10L),
      sizeBytes = Some(1024L)
    )

  /** Three topics, one of which Kafka flags internal. */
  private val snapshot: TopicSnapshot =
    TopicSnapshot.of(
      Vector(summary("orders", internal = false), summary("payments", internal = false), summary("__consumer_offsets", internal = true)),
      at
    )

  private def partitionOf(id: Int, leader: Option[Int]): PartitionView =
    PartitionView
      .from(
        PartitionId.unsafe(id),
        leader.map(BrokerId.unsafe),
        replicas = List(BrokerId.unsafe(1)),
        inSync = List(BrokerId.unsafe(1)),
        earliestOffset = leader.map(_ => 0L),
        latestOffset = leader.map(_ => 10L),
        sizeBytes = Some(1024L)
      )
      .fold(error => fail(s"the fixture partition should be valid: ${error.message}"), identity)

  private def detailOf(partitions: Int): TopicDetail =
    TopicDetail.of(
      TopicName.unsafe("orders"),
      isInternal = false,
      partitions = (0 until partitions).toList.map(id => partitionOf(id, Some(1))),
      cleanupPolicy = Some("delete"),
      segmentCount = Some(4)
    )

  private def get(server: TopicTestServer, path: String): IO[Response[Either[String, String]]] =
    TopicTestServer
      .token(path)
      .flatMap(token =>
        basicRequest
          .get(uri"${TopicTestServer.uri(path)}")
          .header(kui.contracts.KuiEndpoint.PrincipalHeader, token.value)
          .send(server.backend)
      )

  private def post(server: TopicTestServer, path: String): IO[Response[Either[String, String]]] =
    TopicTestServer
      .token(path, method = "POST")
      .flatMap(token =>
        basicRequest
          .post(uri"${TopicTestServer.uri(path)}")
          .header(kui.contracts.KuiEndpoint.PrincipalHeader, token.value)
          .send(server.backend)
      )

  private def body(response: Response[Either[String, String]]): Json =
    parse(response.body.fold(identity, identity))
      .fold(failure => fail(s"the response is not JSON: ${failure.message}"), identity)

  private def field(json: Json, path: String*): Option[Json] =
    path.foldLeft(Option(json))((current, key) => current.flatMap(_.hcursor.downField(key).focus))

  private def listPath(query: String = ""): String =
    s"/internal/v1/clusters/${TopicTestServer.Cluster.value}/topics$query"

  // -----------------------------------------------------------------------------------------------

  test("theListEndpointAnswersAPageWithATotal") {
    TopicTestServer.resource(TopicTestServer.online(snapshot)).use { server =>
      get(server, listPath()).map { response =>
        assertEquals(response.code, StatusCode.Ok, response.body.toString)
        val json = body(response)

        assertEquals(field(json, "topics", "status"), Some(Json.fromString("ok")))
        assertEquals(field(json, "topics", "data", "page", "totalItems"), Some(Json.fromInt(2)))
        assertEquals(field(json, "topics", "data", "page", "pageCount"), Some(Json.fromInt(1)))
        assertEquals(field(json, "incompleteTopics"), Some(Json.fromInt(0)))
      }
    }
  }

  test("hidingInternalTopicsChangesTheTotalOnTheWire") {
    // The brief's named defect, asserted at the outermost layer as well as in the application layer. Two
    // assertions at two levels, deliberately: the reference product computes its page count before its
    // internal filter runs, so a user hides internal topics, is told "page 1 of 3", clicks page 2 and gets
    // an empty screen.
    TopicTestServer.resource(TopicTestServer.online(snapshot)).use { server =>
      for {
        hidden <- get(server, listPath())
        shown <- get(server, listPath("?showInternal=true"))
      } yield {
        assertEquals(field(body(hidden), "topics", "data", "page", "totalItems"), Some(Json.fromInt(2)))
        assertEquals(field(body(shown), "topics", "data", "page", "totalItems"), Some(Json.fromInt(3)))
      }
    }
  }

  test("aStaleSnapshotIsSectionStaleWithItsScrapedAtAndTheRealReason") {
    val state = TopicTestServer.stale(snapshot, TopicTestServer.Timeout, since)

    TopicTestServer.resource(state).use { server =>
      get(server, listPath()).map { response =>
        assertEquals(response.code, StatusCode.Ok, response.body.toString)
        val json = body(response)

        assertEquals(field(json, "topics", "status"), Some(Json.fromString("stale")))
        assertEquals(field(json, "topics", "reason"), Some(Json.fromString("UPSTREAM_TIMEOUT")))
        assertEquals(field(json, "topics", "fetchedAt"), Some(Json.fromString("2026-09-03T10:11:12.000Z")))
        // Still a page, still with the rows: a screen greys them and says when they were taken.
        assertEquals(field(json, "topics", "data", "page", "totalItems"), Some(Json.fromInt(2)))
      }
    }
  }

  test("aNeverScrapedClusterIsSectionUnavailableNotAnEmptyPage") {
    val state: Snapshot[TopicSnapshot] =
      TopicTestServer.neverScraped(InfrastructureError.Unreachable("kafka", "connection refused"), since)

    TopicTestServer.resource(state).use { server =>
      get(server, listPath()).map { response =>
        assertEquals(response.code, StatusCode.Ok, response.body.toString)
        val json = body(response)

        assertEquals(field(json, "topics", "status"), Some(Json.fromString("unavailable")))
        assertEquals(field(json, "topics", "reason"), Some(Json.fromString("UPSTREAM_UNAVAILABLE")))
        // The thing that must not happen: no `data`, so nothing can render this as "no topics".
        assertEquals(field(json, "topics", "data"), None)
      }
    }
  }

  test("incompleteTopics is reported even when the section is stale") {
    val partial = TopicSnapshot.of(
      Vector(summary("orders", internal = false)),
      at,
      incomplete = Map(TopicName.unsafe("payments") -> "describeTopics timed out")
    )

    TopicTestServer.resource(TopicTestServer.stale(partial, TopicTestServer.Timeout, since)).use { server =>
      get(server, listPath()).map(response =>
        assertEquals(field(body(response), "incompleteTopics"), Some(Json.fromInt(1)))
      )
    }
  }

  test("anUnknownClusterIs404WithTheClusterCode") {
    TopicTestServer.resource(TopicTestServer.online(snapshot)).use { server =>
      val path = s"/internal/v1/clusters/${TopicTestServer.Missing.value}/topics"

      get(server, path).map { response =>
        assertEquals(response.code, StatusCode.NotFound, response.body.toString)
        assertEquals(field(body(response), "code"), Some(Json.fromString("KUI-CLUSTER-NOT-FOUND")))
      }
    }
  }

  test("anUnknownTopicIs404WithTheTopicCode") {
    val missing = Left(TopicError.NotFound(TopicName.unsafe("nope")))

    TopicTestServer.resource(TopicTestServer.online(snapshot), detail = missing).use { server =>
      get(server, listPath("/nope")).map { response =>
        assertEquals(response.code, StatusCode.NotFound, response.body.toString)
        assertEquals(field(body(response), "code"), Some(Json.fromString("KUI-TOPIC-NOT-FOUND")))
      }
    }
  }

  test("aMalformedSortIs400NamingTheField, and the same for mode, page and pageSize") {
    TopicTestServer.resource(TopicTestServer.online(snapshot)).use { server =>
      val cases = List(
        "?sort=nonsense:asc" -> "sort",
        "?sort=name" -> "sort",
        "?mode=whatever" -> "mode",
        "?page=0" -> "page",
        s"?pageSize=${kui.kernel.PageSize.Max.value + 1}" -> "pageSize"
      )

      cases.traverse { case (query, field0) =>
        get(server, listPath(query)).map { response =>
          assertEquals(response.code, StatusCode.BadRequest, s"$query -> ${response.body}")
          val text = response.body.fold(identity, identity)
          assert(text.contains("KUI-VALIDATION"), s"$query -> $text")
          assert(text.contains(field0), s"$query -> $text")
        }
      }.void
    }
  }

  test("a valid sort and mode are accepted") {
    TopicTestServer.resource(TopicTestServer.online(snapshot)).use { server =>
      get(server, listPath("?sort=messageCount:desc&mode=fts&q=ord&page=1&pageSize=10")).map(response =>
        assertEquals(response.code, StatusCode.Ok, response.body.toString)
      )
    }
  }

  test("refreshAnswers202Immediately") {
    // With a cell whose refresh never completes and a virtual clock. A regression that awaited the scrape
    // fails this suite rather than merely slowing it: `TestControl.executeEmbed` raises rather than
    // returning when the program cannot make progress.
    val program = Deferred[IO, Unit].flatMap { blocked =>
      TopicTestServer
        .resource(TopicTestServer.online(snapshot), refreshBlocks = Some(blocked))
        .use { server =>
          for {
            response <- post(server, listPath("/refresh"))
            asked <- server.refreshes.get
          } yield {
            assertEquals(response.code, StatusCode.Accepted, response.body.toString)
            assertEquals(field(body(response), "clusterId"), Some(Json.fromString("local")))
            assert(field(body(response), "requestedAt").isDefined, response.body.toString)
            assertEquals(asked, List(TopicTestServer.Cluster))
          }
        }
    }

    TestControl.executeEmbed(program)
  }

  test("a refresh for an unknown cluster is 404, not a silent 202") {
    TopicTestServer.resource(TopicTestServer.online(snapshot)).use { server =>
      val path = s"/internal/v1/clusters/${TopicTestServer.Missing.value}/topics/refresh"

      post(server, path).map { response =>
        assertEquals(response.code, StatusCode.NotFound, response.body.toString)
        assertEquals(field(body(response), "code"), Some(Json.fromString("KUI-CLUSTER-NOT-FOUND")))
      }
    }
  }

  test("theDetailEndpointTruncatesAtFiveHundredPartitionsAndSaysSo") {
    val limit = kui.topic.contract.dto.TopicDetailResponse.EmbeddedPartitionLimit
    val big = Right(Fresh.Live(detailOf(limit + 1)))
    val exact = Right(Fresh.Live(detailOf(limit)))

    for {
      truncated <- TopicTestServer.resource(TopicTestServer.online(snapshot), detail = big).use { server =>
        get(server, listPath("/orders")).map(body)
      }
      whole <- TopicTestServer.resource(TopicTestServer.online(snapshot), detail = exact).use { server =>
        get(server, listPath("/orders")).map(body)
      }
    } yield {
      assertEquals(field(truncated, "partitionsTruncated"), Some(Json.fromBoolean(true)))
      assertEquals(
        field(truncated, "topic", "data", "partitions").flatMap(_.asArray).map(_.size),
        Some(limit)
      )
      // A topic with exactly the limit is not truncated. This is why the flag is sent rather than derived
      // from the length of the list.
      assertEquals(field(whole, "partitionsTruncated"), Some(Json.fromBoolean(false)))
    }
  }

  test("the partitions endpoint returns every partition, uncapped") {
    val limit = kui.topic.contract.dto.TopicDetailResponse.EmbeddedPartitionLimit
    val big = Right(Fresh.Live(detailOf(limit + 3)))

    TopicTestServer.resource(TopicTestServer.online(snapshot), detail = big).use { server =>
      get(server, listPath("/orders/partitions")).map { response =>
        assertEquals(
          field(body(response), "partitions", "data").flatMap(_.asArray).map(_.size),
          Some(limit + 3)
        )
      }
    }
  }

  test("a detail served from the snapshot is Stale, not Ok") {
    val fallback = Right(Fresh.FromSnapshot(detailOf(2), at, "the describe timed out"))

    TopicTestServer.resource(TopicTestServer.online(snapshot), detail = fallback).use { server =>
      get(server, listPath("/orders")).map { response =>
        assertEquals(field(body(response), "topic", "status"), Some(Json.fromString("stale")))
        assertEquals(field(body(response), "topic", "reason"), Some(Json.fromString("UPSTREAM_TIMEOUT")))
      }
    }
  }

  test("a settings tab the caller may not read is a 200 with not_permitted, never a 403") {
    // A 403 would take the partitions the user *is* entitled to see down with the tab they are not.
    val refused = Right(TopicConfigView.NotPermitted("the cluster refused describeConfigs"))

    TopicTestServer.resource(TopicTestServer.online(snapshot), config = refused).use { server =>
      get(server, listPath("/orders/config")).map { response =>
        assertEquals(response.code, StatusCode.Ok, response.body.toString)
        assertEquals(field(body(response), "config", "data", "status"), Some(Json.fromString("not_permitted")))
      }
    }
  }

  test("an empty settings tab and a refused one are different documents") {
    for {
      empty <- TopicTestServer.resource(TopicTestServer.online(snapshot)).use { server =>
        get(server, listPath("/orders/config")).map(body)
      }
      refused <- TopicTestServer
        .resource(
          TopicTestServer.online(snapshot),
          config = Right(TopicConfigView.NotPermitted("no DESCRIBE_CONFIGS"))
        )
        .use(server => get(server, listPath("/orders/config")).map(body))
    } yield {
      assertEquals(field(empty, "config", "data", "status"), Some(Json.fromString("entries")))
      assertEquals(field(refused, "config", "data", "status"), Some(Json.fromString("not_permitted")))
      assertNotEquals(empty, refused)
    }
  }

  test("a request with no principal header is 401, not 400") {
    // `KuiEndpoint.internal` declares the header as a security input whose codec refuses a blank value, so
    // a missing header fails while Tapir is still decoding and the shared handler would call it a
    // validation error. Two distinguishable refusals let a caller learn which half of a forged request to
    // fix; `PrincipalInterceptor` is what makes both look the same.
    TopicTestServer.resource(TopicTestServer.online(snapshot)).use { server =>
      basicRequest
        .get(uri"${TopicTestServer.uri(listPath())}")
        .send(server.backend)
        .map { response =>
          assertEquals(response.code, StatusCode.Unauthorized, response.body.toString)
          assertEquals(field(body(response), "code"), Some(Json.fromString("KUI-UNAUTHENTICATED")))
        }
    }
  }

  test("a token minted for another path does not open this one") {
    // ADR-020 binds a token to one method and one path. Without that, an intercepted header from a list
    // request would work on every other endpoint of the service.
    TopicTestServer.resource(TopicTestServer.online(snapshot)).use { server =>
      TopicTestServer
        .token(listPath("/orders"))
        .flatMap(token =>
          basicRequest
            .get(uri"${TopicTestServer.uri(listPath())}")
            .header(kui.contracts.KuiEndpoint.PrincipalHeader, token.value)
            .send(server.backend)
        )
        .map(response => assertEquals(response.code, StatusCode.Unauthorized, response.body.toString))
    }
  }

  test("a token for another service does not open this one") {
    TopicTestServer.resource(TopicTestServer.online(snapshot)).use { server =>
      TopicTestServer
        .token(listPath(), audience = kui.kernel.ServiceId.unsafe("cluster"))
        .flatMap(token =>
          basicRequest
            .get(uri"${TopicTestServer.uri(listPath())}")
            .header(kui.contracts.KuiEndpoint.PrincipalHeader, token.value)
            .send(server.backend)
        )
        .map(response => assertEquals(response.code, StatusCode.Unauthorized, response.body.toString))
    }
  }

  test("the capability document names every configured cluster and this service") {
    val report = List(
      TopicTestServer.Cluster -> TopicCapability.Available(at),
      ClusterId.unsafe("prod-eu") -> TopicCapability.Degraded("the scrape timed out", since, Some(at))
    )

    TopicTestServer.resource(TopicTestServer.online(snapshot), capabilities = report).use { server =>
      basicRequest
        .get(uri"${TopicTestServer.uri("/capabilities")}")
        .send(server.backend)
        .map { response =>
          assertEquals(response.code, StatusCode.Ok, response.body.toString)
          val json = body(response)

          assertEquals(field(json, "service"), Some(Json.fromString("topic")))
          assertEquals(field(json, "clusters", "local", "status"), Some(Json.fromString("available")))
          assertEquals(field(json, "clusters", "prod-eu", "status"), Some(Json.fromString("degraded")))
          assertEquals(
            field(json, "clusters", "prod-eu", "reason"),
            Some(Json.fromString("the scrape timed out"))
          )
        }
    }
  }

  test("everyEndpointEmitsItsDurationMetric") {
    // Read back off the OpenTelemetry testkit rather than asserted against a fake, because a fake can
    // agree with a bug: the question is whether the real interceptor chain is wired in.
    TopicTestServer.resource(TopicTestServer.online(snapshot)).use { server =>
      for {
        _ <- get(server, listPath())
        metrics <- server.telemetry.collectMetrics
      } yield assert(
        metrics.exists(_.getName == kui.observability.MetricNames.HttpServerDuration),
        metrics.map(_.getName).toString
      )
    }
  }

  test("every endpoint is reachable and none of them 404s on its own path") {
    val big = Right(Fresh.Live(detailOf(2)))

    TopicTestServer.resource(TopicTestServer.online(snapshot), detail = big).use { server =>
      for {
        list <- get(server, listPath())
        one <- get(server, listPath("/orders"))
        config <- get(server, listPath("/orders/config"))
        partitions <- get(server, listPath("/orders/partitions"))
        refresh <- post(server, listPath("/refresh"))
      } yield assertEquals(
        List(list, one, config, partitions, refresh).map(_.code),
        List(StatusCode.Ok, StatusCode.Ok, StatusCode.Ok, StatusCode.Ok, StatusCode.Accepted)
      )
    }
  }
}
