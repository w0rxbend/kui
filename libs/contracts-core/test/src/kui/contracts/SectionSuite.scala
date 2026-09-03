package kui.contracts

import java.time.Instant

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

import kui.contracts.capability.ReasonCode
import kui.kernel.error.*

/** How a partial answer is shaped, and which failure becomes which section.
  *
  * The mapping table is the interesting half. A user interface says different things about "the
  * upstream is unreachable" and "we have stopped calling it while it recovers", and both of those
  * carry the same error code, so the mapping has to be by failure case.
  */
final class SectionSuite extends FunSuite {

  private val at    = Instant.parse("2026-09-03T10:11:12Z")
  private val since = Instant.parse("2026-09-03T09:00:00Z")

  test("a success becomes an ok section carrying the moment it was fetched") {
    assertEquals(Section.fromEither(Right(List("a")), at), Section.Ok(List("a"), at))
  }

  private val mappings: List[(String, KuiError, Section[Nothing])] = List(
    (
      "a denied read",
      ApplicationError.Forbidden("no role permits this"),
      Section.Forbidden
    ),
    (
      "a feature this deployment does not have",
      ApplicationError.Unsupported("schema registry"),
      Section.NotConfigured
    ),
    (
      "an open circuit",
      InfrastructureError.CircuitOpen("schema-registry", since),
      Section.Unavailable(
        ReasonCode.CircuitOpen,
        "calls to schema-registry are suspended while it recovers",
        Some(since)
      )
    ),
    (
      "a timeout",
      InfrastructureError.Timeout("listSubjects", 5000L),
      Section.Unavailable(ReasonCode.UpstreamTimeout, "listSubjects did not finish within 5000ms", Some(at))
    ),
    (
      "rejected credentials",
      InfrastructureError.AuthFailed("schema-registry"),
      Section.Unavailable(
        ReasonCode.UpstreamAuth,
        "KUI is not authenticated against schema-registry",
        Some(at)
      )
    ),
    (
      "an unreachable upstream",
      InfrastructureError.Unreachable("schema-registry", "connection refused"),
      Section.Unavailable(
        ReasonCode.UpstreamUnavailable,
        "schema-registry could not be reached",
        Some(at)
      )
    ),
    (
      "an upstream error status",
      InfrastructureError.Upstream("schema-registry", 500),
      Section.Unavailable(
        ReasonCode.UpstreamUnavailable,
        "schema-registry answered with status 500",
        Some(at)
      )
    ),
    (
      "anything else",
      ApplicationError.Conflict("someone else changed it"),
      Section.Unavailable(ReasonCode.Unknown, "someone else changed it", Some(at))
    )
  )

  mappings.foreach { row =>
    test(s"${row._1} becomes ${row._3.status}") {
      assertEquals(Section.fromEither[Nothing](Left(row._2), at), row._3)
    }
  }

  test("a circuit-open section keeps the moment the circuit opened, not the moment we asked") {
    Section.fromEither[Nothing](Left(InfrastructureError.CircuitOpen("ksql", since)), at) match {
      case Section.Unavailable(_, _, Some(actual)) => assertEquals(actual, since)
      case other                                   => fail(s"expected an unavailable section, got $other")
    }
  }

  test("every case round-trips through its codec") {
    val cases: List[Section[List[String]]] = List(
      Section.Ok(List("orders"), at),
      Section.Stale(List("orders"), at, ReasonCode.UpstreamTimeout),
      Section.Unavailable(ReasonCode.CircuitOpen, "down", Some(since)),
      Section.Unavailable(ReasonCode.Unknown, "down", None),
      Section.Forbidden,
      Section.NotConfigured
    )

    cases.foreach { section =>
      assertEquals(decode[Section[List[String]]](section.asJson.noSpaces), Right(section), clue = section.status)
    }
  }

  test("stale carries both the data and the reason it is old") {
    val stale = Section.Stale(List("orders"), at, ReasonCode.UpstreamUnavailable)
    assertEquals(
      stale.asJson.noSpaces,
      """{"status":"stale","data":["orders"],"fetchedAt":"2026-09-03T10:11:12.000Z","reason":"UPSTREAM_UNAVAILABLE"}"""
    )
    assertEquals(stale.toOption, Some(List("orders")))
  }

  test("the status strings are the five the contract fixes") {
    assertEquals(
      List(
        Section.Ok((), at),
        Section.Stale((), at, ReasonCode.Unknown),
        Section.Unavailable(ReasonCode.Unknown, "", None),
        Section.Forbidden,
        Section.NotConfigured
      ).map(_.status),
      List("ok", "stale", "unavailable", "forbidden", "not_configured")
    )
  }

  test("a section with no data offers none, and one with stale data still offers it") {
    assertEquals(Section.Forbidden.toOption, None)
    assertEquals(Section.Unavailable(ReasonCode.Unknown, "", None).toOption, None)
    assertEquals(Section.Stale(1, at, ReasonCode.Unknown).toOption, Some(1))
  }

  test("a status a newer gateway invented is a decode failure, not a silent empty section") {
    assert(decode[Section[List[String]]]("""{"status":"partial"}""").isLeft)
  }
}
