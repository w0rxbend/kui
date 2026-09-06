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

  test("requests are built relative to the root, because failover puts the base path back on") {
    // The defect this pins. `Failover.rebase` replaces the scheme and authority of each request and
    // *prefixes the base URL's own path*, so a client that also built its requests against the full
    // configured URL had that path applied twice. Apicurio serves the Confluent-compatible API at
    // `/apis/ccompat/v7` — it is the registry the quickstart runs — and every schema screen reported
    // "the configured address does not look like a Schema Registry", because the request had gone to
    // `/apis/ccompat/v7/apis/ccompat/v7/subjects` and honestly received a 404.
    //
    // The suite could not see it: every case here used a base with no path at all.
    var asked: String = ""
    val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { request =>
        asked = "/" + request.uri.path.mkString("/")
        IO.pure(ResponseStub.adjust("[]", StatusCode.Ok): sttp.client4.Response[StubBody])
      }
    val subPath = SafeUrl.unsafe("http://registry:8081/apis/ccompat/v7")

    new RegistryHttp[IO](backend, subPath, RegistryCredentials.anonymous[IO]).subjects
      .map(_ => assertEquals(asked, "/subjects"))
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

  test("a registration answers the id the registry stored and the version the lookup reports") {
    // Two requests, because the Confluent API's registration response is `{"id": N}` and carries no
    // version. The second is the registry's "which version is *this* schema" lookup and not
    // `versions/latest`, which would be a race: somebody else's registration in between would hand this
    // operator back a version that is not theirs.
    var asked: List[String] = Nil
    val client = registry {
      case "/subjects/orders-value/versions" =>
        asked = asked :+ "/subjects/orders-value/versions"
        (StatusCode.Ok, """{"id":41}""")
      case "/subjects/orders-value" =>
        asked = asked :+ "/subjects/orders-value"
        (StatusCode.Ok, """{"subject":"orders-value","id":41,"version":3,"schema":"{}"}""")
    }

    client.register(orders, ProposedSchema(SchemaFormat.Avro, """{"type":"record"}""", Nil)).map {
      case Right(registered) =>
        assertEquals(registered.id.value, 41)
        assertEquals(registered.version.map(_.value), Some(3))
        assertEquals(registered.subject.value, "orders-value")
        assertEquals(asked, List("/subjects/orders-value/versions", "/subjects/orders-value"))
      case Left(error) => fail(s"expected a registration, got $error")
    }
  }

  test("a registry that rejects the schema says so as a validation failure beside the field") {
    // The whole point of the endpoint. A 409 from a Schema Registry means "incompatible with what this
    // subject already holds", and the sentence after it names the field that broke the rule — which is the
    // only part of the answer an operator can act on. It must not arrive as a 500 and must not be
    // swallowed into "the upstream is unavailable", both of which send them to the wrong person.
    val explanation =
      "Schema being registered is incompatible with an earlier schema for subject 'orders-value'"

    registry { case "/subjects/orders-value/versions" =>
      (StatusCode.Conflict, s"""{"error_code":409,"message":"$explanation"}""")
    }.register(orders, ProposedSchema(SchemaFormat.Avro, "{}", Nil)).map {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.Validation)
        assert(clue(error.message).contains(explanation))
        // `details[0]` is where a form reads the text it puts beside the input somebody typed into.
        assertEquals(error.details.map(_.field), List(Some("definition")))
        assertEquals(error.details.flatMap(_.restrictions), List(explanation))
      case Right(registered) => fail(s"expected a refusal, got $registered")
    }
  }

  test("a registration whose version lookup does not answer is still a registration, with no version") {
    // The schema is in the registry by the time the second call is made. Answering `Left` here would tell
    // an operator to register it again, and inventing "the previous latest plus one" would print a version
    // number that may not exist. Absent is the true third answer.
    registry { case "/subjects/orders-value/versions" =>
      (StatusCode.Ok, """{"id":41}""")
    }.register(orders, ProposedSchema(SchemaFormat.Avro, "{}", Nil)).map {
      case Right(registered) =>
        assertEquals(registered.id.value, 41)
        assertEquals(registered.version, None)
      case Left(error) => fail(s"expected a registration, got $error")
    }
  }

  test("a 404 on the registration itself is an address that is not a registry") {
    // A subject that does not exist is *created* by this call, so there is nothing here that a 404 could
    // honestly mean an absence of. It is the same misconfigured ingress `GET /subjects` reports.
    registry(nothing).register(orders, ProposedSchema(SchemaFormat.Avro, "{}", Nil)).map {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.UpstreamUnavailable)
        assert(clue(error.message).contains("does not look like a Schema Registry"))
      case Right(registered) => fail(s"expected a failure, got $registered")
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

  test("a summary counts the version list rather than reading the latest version's number") {
    registry {
      case "/subjects/orders-value/versions" => (StatusCode.Ok, "[1,2,7]")
      case "/subjects/orders-value/versions/latest" =>
        (StatusCode.Ok, """{"version":7,"id":11,"schemaType":"PROTOBUF","schema":"syntax = \"proto3\";"}""")
      case "/config/orders-value" => (StatusCode.Ok, """{"compatibilityLevel":"FULL"}""")
    }.summary(orders).map {
      case Right(Some(summary)) =>
        // Versions 3 to 6 have been deleted. The latest is 7 and there are three of them, and a row
        // reading "7 versions" would be printing a number the registry never offered as a count.
        assertEquals(summary.versionCount, Some(3))
        assertEquals(summary.format, Some(SchemaFormat.Protobuf))
        assertEquals(summary.compatibility, Some(SubjectCompatibility.own(CompatibilityLevel.Full)))
      case other => fail(s"expected a summary, got $other")
    }
  }

  test("a subject with no level of its own leaves the level absent for the caller to inherit") {
    // The registry answers 404 to `/config/{subject}` for the overwhelming majority of subjects, and
    // resolving that into the global level here would be one extra request per row of every page.
    registry {
      case "/subjects/orders-value/versions" => (StatusCode.Ok, "[1]")
      case "/subjects/orders-value/versions/latest" =>
        (StatusCode.Ok, """{"version":1,"id":1,"schema":"\"string\""}""")
    }.summary(orders).map(_.map(_.flatMap(_.compatibility))).assertEquals(Right(None))
  }

  test("a subject that has gone is an absence, and the two decorating requests are never sent") {
    var asked = List.empty[String]
    val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { request =>
        asked = asked :+ ("/" + request.uri.path.mkString("/"))
        IO.pure(ResponseStub.adjust("", StatusCode.NotFound): sttp.client4.Response[StubBody])
      }

    new RegistryHttp[IO](backend, base, RegistryCredentials.anonymous[IO]).summary(orders).map { result =>
      assertEquals(result, Right(None))
      // Not three requests for a subject that is no longer there. The version call already answered
      // the only question the other two were going to decorate.
      assertEquals(asked, List("/subjects/orders-value/versions"))
    }
  }

  test("a decorating request that fails refuses the whole row rather than half of it") {
    registry {
      case "/subjects/orders-value/versions" => (StatusCode.Ok, "[1]")
      case "/subjects/orders-value/versions/latest" =>
        (StatusCode.Ok, """{"version":1,"id":1,"schema":"\"string\""}""")
      case "/config/orders-value" => (StatusCode.ServiceUnavailable, "")
    }.summary(orders).map {
      case Left(error) => assertEquals(error.code, ErrorCode.UpstreamUnavailable)
      case Right(found) => fail(s"expected a failure, got $found")
    }
  }
}
