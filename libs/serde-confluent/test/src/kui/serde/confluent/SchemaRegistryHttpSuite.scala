package kui.serde.confluent

import cats.effect.IO
import sttp.client4.Backend
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub, StubBody}
import sttp.model.StatusCode

import kui.config.SafeUrl
import kui.kernel.error.{ErrorCode, InfrastructureError}
import kui.testkit.KuiIOSuite

/** What the registry client does with each answer a registry can give.
  *
  * A stub rather than a running registry: every promise here is a promise about a *response*, and a real
  * registry is the slowest possible way to produce one — and cannot be made to produce most of them at all.
  */
final class SchemaRegistryHttpSuite extends KuiIOSuite {

  private val base: SafeUrl = SafeUrl.unsafe("http://registry:8081")

  private def registry(
      auth: SchemaRegistryAuth = SchemaRegistryAuth.Anonymous
  )(respond: PartialFunction[String, (StatusCode, String)]): SchemaRegistry[IO] = {
    val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { request =>
        val path = "/" + request.uri.path.mkString("/")
        val (status, body) = respond.applyOrElse(path, (missing: String) => (StatusCode.NotFound, missing))
        IO.pure(ResponseStub.adjust(body, status): sttp.client4.Response[StubBody])
      }
    SchemaRegistry.http[IO](backend, base, auth)
  }

  test("a schema fetched by id keeps its text verbatim and takes its id from the request") {
    val client = registry() { case "/schemas/ids/7" =>
      (StatusCode.Ok, """{"schema":"\"string\"","schemaType":"AVRO"}""")
    }
    client.schemaById(7).map(assertEquals(_, Right(RegistrySchema(7, SchemaType.Avro, "\"string\""))))
  }

  test("a schema with no schemaType field is Avro, because that is what the registry means by omitting it") {
    val client = registry() { case "/schemas/ids/1" => (StatusCode.Ok, """{"schema":"\"int\""}""") }
    client.schemaById(1).map(_.map(_.schemaType)).assertEquals(Right(SchemaType.Avro))
  }

  test("a schema id the registry does not have is a not-found, not an infrastructure failure") {
    val client = registry() { case "/never" => (StatusCode.Ok, "") }
    client.schemaById(99).map {
      case Left(error) => assertEquals(error.code, ErrorCode.SchemaNotFound)
      case Right(found) => fail(s"expected a not-found, got $found")
    }
  }

  test("a subject that does not exist is an answer, not a failure") {
    val client = registry() { case "/never" => (StatusCode.Ok, "") }
    client.latestForSubject("orders-value").assertEquals(Right(None))
  }

  test("the latest version of a subject carries the id from the response body") {
    val client = registry() { case "/subjects/orders-value/versions/latest" =>
      (StatusCode.Ok, """{"subject":"orders-value","version":4,"id":31,"schema":"\"string\""}""")
    }
    client.latestForSubject("orders-value").map(_.map(_.map(_.id))).assertEquals(Right(Some(31)))
  }

  test("a rejected credential is reported as an authentication failure against the named upstream") {
    val client = registry() { case "/schemas/ids/1" => (StatusCode.Unauthorized, "nope") }
    client.schemaById(1).assertEquals(Left(InfrastructureError.AuthFailed(SchemaRegistry.UpstreamName)))
  }

  test("a 500 carries the status and never the registry's response body") {
    val client = registry() { case "/schemas/ids/1" =>
      (StatusCode.InternalServerError, "postgres password=hunter2 refused")
    }
    client.schemaById(1).map {
      case Left(error) =>
        assertEquals(error, InfrastructureError.Upstream(SchemaRegistry.UpstreamName, 500))
        assert(!error.message.contains("hunter2"), error.message)
      case Right(found) => fail(s"expected an upstream failure, got $found")
    }
  }

  test("an answer that is not the shape the registry documents is reported as such, not parsed hopefully") {
    val client = registry() { case "/schemas/ids/1" => (StatusCode.Ok, """{"unexpected":true}""") }
    client.schemaById(1).map {
      // The sentence has to be in `message`, not in a `cause` field the user never sees: an operator whose
      // "registry" is really a proxy's error page learns nothing from "schema-registry could not be reached".
      case Left(error) => assert(error.message.contains("could not be understood"), error.message)
      case Right(found) => fail(s"expected a parse failure, got $found")
    }
  }

  test("basic credentials are sent, and a registry that requires them answers") {
    val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { request =>
        val authorized = request.headers.exists(header =>
          header.name == "Authorization" && header.value.startsWith("Basic ")
        )
        val response =
          if authorized then ResponseStub.adjust("""{"schema":"\"string\""}""", StatusCode.Ok)
          else ResponseStub.adjust("", StatusCode.Unauthorized)
        IO.pure(response: sttp.client4.Response[StubBody])
      }
    SchemaRegistry
      .http[IO](backend, base, SchemaRegistryAuth.Basic("kui", "secret"))
      .schemaById(3)
      .map(_.map(_.id))
      .assertEquals(Right(3))
  }

  test("a transport failure becomes an unreachable upstream, and its sentence names only the upstream") {
    val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF(_ => IO.raiseError[sttp.client4.Response[StubBody]](new java.net.ConnectException("refused")))
    SchemaRegistry.http[IO](backend, base, SchemaRegistryAuth.Anonymous).schemaById(1).map {
      case Left(InfrastructureError.Unreachable(upstream, cause)) =>
        assertEquals(upstream, SchemaRegistry.UpstreamName)
        // The cause is for the log and does carry the exception's own text; the *message* - the sentence a
        // user sees - names only the upstream, which is `Unreachable`'s whole reason for splitting the two.
        assert(cause.startsWith("ConnectException:"), cause)
        assertEquals(InfrastructureError.Unreachable(upstream, cause).message, "schema-registry could not be reached")
      case other => fail(s"expected an unreachable upstream, got $other")
    }
  }

  test("the subject of a topic is the topic, a dash, and which half of the record it is") {
    assertEquals(SchemaRegistry.subjectFor("orders-v2", "value"), "orders-v2-value")
    assertEquals(SchemaRegistry.subjectFor("orders-v2", "key"), "orders-v2-key")
  }

}
