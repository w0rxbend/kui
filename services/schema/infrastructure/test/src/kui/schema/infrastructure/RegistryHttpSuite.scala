package kui.schema.infrastructure

import cats.effect.IO
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub, StubBody}
import sttp.client4.Backend
import sttp.model.StatusCode

import kui.config.SafeUrl
import kui.kernel.Subject
import kui.kernel.error.ErrorCode
import kui.schema.domain.*
import kui.testkit.KuiIOSuite

/** What the client does with each answer a Schema Registry can give.
  *
  * A stub rather than a running registry: every promise here is a promise about a *response*, and a real
  * registry is the slowest possible way to produce one — and cannot be made to produce most of them at all.
  * The registry KUI has to survive is the one answering a proxy's HTML error page, a 404 that means three
  * different things depending on the path, and a compatibility level it has never heard of.
  */
final class RegistryHttpSuite extends KuiIOSuite {

  private val base: SafeUrl = SafeUrl.unsafe("http://registry:8081")
  private val orders = Subject.unsafe("orders-value")

  private def registry(respond: PartialFunction[String, (StatusCode, String)]): RegistryHttp[IO] = {
    val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { request =>
        val path = "/" + request.uri.path.mkString("/")
        val (status, body) = respond.applyOrElse(path, (_: String) => (StatusCode.NotFound, ""))
        IO.pure(ResponseStub.adjust(body, status): sttp.client4.Response[StubBody])
      }
    new RegistryHttp[IO](backend, base, RegistryCredentials.anonymous[IO])
  }

  private val nothing: PartialFunction[String, (StatusCode, String)] = { case "/never" =>
    (StatusCode.Ok, "")
  }

  test("the subject list decodes into subjects") {
    registry { case "/subjects" => (StatusCode.Ok, """["orders-value","payments-value"]""") }.subjects
      .map(_.map(_.map(_.value)))
      .assertEquals(Right(List("orders-value", "payments-value")))
  }

  test("a 404 on the subject list is not 'no subjects'; it is an address that is not a registry") {
    // This is the difference between an empty Schemas screen and a message telling the operator their
    // ingress points at the wrong service, and it is the only 404 in this client that is a failure.
    registry(nothing).subjects.map {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.UpstreamUnavailable)
        assert(clue(error.message).contains("does not look like a Schema Registry"))
      case Right(found) => fail(s"expected a failure, got $found")
    }
  }

  test("a subject that does not exist is an absence rather than a failure") {
    registry(nothing).versions(orders).assertEquals(Right(None))
  }

  test("a version list arrives sorted, and a nonsense version number is dropped") {
    registry { case "/subjects/orders-value/versions" => (StatusCode.Ok, "[3,1,0,2]") }
      .versions(orders)
      .map(_.map(_.map(_.map(_.value))))
      .assertEquals(Right(Some(List(1, 2, 3))))
  }

  test("a schema keeps its text verbatim and reads its references") {
    val body =
      """{"subject":"orders-value","version":2,"id":11,"schemaType":"JSON",
        |"schema":"{\"type\":\"object\"}",
        |"references":[{"name":"Address","subject":"address-value","version":1}]}""".stripMargin

    registry { case "/subjects/orders-value/versions/2" => (StatusCode.Ok, body) }
      .schema(orders, VersionSelector.Numbered(SchemaVersion.unsafe(2)))
      .map {
        case Right(Some(schema)) =>
          assertEquals(schema.definition, """{"type":"object"}""")
          assertEquals(schema.format, SchemaFormat.Json)
          assertEquals(schema.version.value, 2)
          assertEquals(schema.id.value, 11)
          assertEquals(schema.references.map(_.name), List("Address"))
        case other => fail(s"expected a schema, got $other")
      }
  }

  test("a schema with no schemaType is Avro, because that is what omitting it means") {
    registry { case "/subjects/orders-value/versions/latest" =>
      (StatusCode.Ok, """{"version":1,"id":1,"schema":"\"string\""}""")
    }.schema(orders, VersionSelector.Latest)
      .map(_.map(_.map(_.format)))
      .assertEquals(Right(Some(SchemaFormat.Avro)))
  }

  test("an answer that is not JSON says so, and quotes nothing the registry sent") {
    registry { case "/subjects/orders-value/versions/latest" =>
      (StatusCode.Ok, "<html><body>502 Bad Gateway from squid</body></html>")
    }.schema(orders, VersionSelector.Latest)
      .map {
        case Left(error) =>
          assert(clue(error.message).contains("could not be understood"))
          assert(!error.message.contains("squid"), "an upstream body must never be echoed (ADR-034)")
        case Right(found) => fail(s"expected a failure, got $found")
      }
  }

  test("no global level configured is the registry's own default, because that is what it will apply") {
    registry(nothing).globalCompatibility.assertEquals(Right(CompatibilityLevel.Backward))
  }

  test("both spellings of the level field are read") {
    registry { case "/config" => (StatusCode.Ok, """{"compatibilityLevel":"FULL_TRANSITIVE"}""") }
      .globalCompatibility
      .assertEquals(Right(CompatibilityLevel.FullTransitive)) *>
      registry { case "/config" => (StatusCode.Ok, """{"compatibility":"NONE"}""") }.globalCompatibility
        .assertEquals(Right(CompatibilityLevel.None))
  }

  test("a subject with no level of its own answers None rather than failing") {
    registry(nothing).subjectCompatibility(orders).assertEquals(Right(None))
  }

  test("a level KUI does not know is a failure naming the seven it does") {
    registry { case "/config" => (StatusCode.Ok, """{"compatibilityLevel":"SIDEWAYS"}""") }
      .globalCompatibility
      .map {
        case Left(error) => assert(clue(error.message).contains("BACKWARD"))
        case Right(found) => fail(s"expected a failure, got $found")
      }
  }

  test("a 422 from the registry is a validation failure carrying the registry's own explanation") {
    registry { case "/config" =>
      (StatusCode.UnprocessableEntity, """{"error_code":42203,"message":"Invalid compatibility level"}""")
    }.setGlobalCompatibility(CompatibilityLevel.Full)
      .map {
        case Left(error) =>
          assertEquals(error.code, ErrorCode.Validation)
          assert(clue(error.message).contains("Invalid compatibility level"))
        case Right(_) => fail("expected a failure")
      }
  }

  test("a 401 is an authentication failure and not a generic upstream error") {
    registry { case "/subjects" => (StatusCode.Unauthorized, "nope") }.subjects.map {
      case Left(error) => assertEquals(error.code, ErrorCode.UpstreamAuth)
      case Right(found) => fail(s"expected a failure, got $found")
    }
  }

  test("a 503 becomes an upstream failure rather than an empty answer") {
    registry { case "/subjects" => (StatusCode.ServiceUnavailable, "") }.subjects.map {
      case Left(error) => assertEquals(error.code, ErrorCode.UpstreamUnavailable)
      case Right(found) => fail(s"expected a failure, got $found")
    }
  }

  test("the compatibility verdict carries the registry's messages, and an unknown subject is an absence") {
    val client = registry { case "/compatibility/subjects/orders-value/versions/latest" =>
      (StatusCode.Ok, """{"is_compatible":false,"messages":["field 'total' has no default"]}""")
    }

    client
      .checkCompatibility(orders, VersionSelector.Latest, ProposedSchema(SchemaFormat.Avro, "{}", Nil))
      .assertEquals(Right(Some(CompatibilityVerdict(false, List("field 'total' has no default"))))) *>
      registry(nothing)
        .checkCompatibility(orders, VersionSelector.Latest, ProposedSchema(SchemaFormat.Avro, "{}", Nil))
        .assertEquals(Right(None))
  }

  test("an incompatible verdict with no messages is a real state, not a decode failure") {
    registry { case "/compatibility/subjects/orders-value/versions/latest" =>
      (StatusCode.Ok, """{"is_compatible":false}""")
    }.checkCompatibility(orders, VersionSelector.Latest, ProposedSchema(SchemaFormat.Avro, "{}", Nil))
      .assertEquals(Right(Some(CompatibilityVerdict(false, Nil))))
  }
}
