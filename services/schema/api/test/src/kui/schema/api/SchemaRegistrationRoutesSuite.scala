package kui.schema.api

import java.nio.charset.StandardCharsets
import java.time.Instant

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import io.circe.syntax.*
import io.circe.{Json, Printer}
import io.circe.parser.parse
import org.typelevel.otel4s.metrics.MeterProvider
import sttp.client4.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.BackendStub
import sttp.model.Uri
import sttp.tapir.server.stub4.TapirStubInterpreter

import kui.contracts.rbac.{EndpointDecision, EndpointAuthorization}
import kui.contracts.{HttpHeaders, KuiEndpoint}
import kui.http.principal.{PrincipalVerification, RbacGuard, SecuredRoutes}
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, RoleName, SchemaId, Secret, Subject, UserName}
import kui.observability.Telemetry
import kui.schema.application.*
import kui.schema.contract.SchemaMutationEndpoints
import kui.schema.contract.dto.*
import kui.schema.contract.dto.RegisterSchemaRequest.given
import kui.schema.domain.*
import kui.security.*
import kui.security.rbac.*
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** `POST …/schemas/subjects/{subject}/versions`, over HTTP, from the query string to the envelope.
  *
  * This is the M6 bullet that had no capability behind it: the Schemas screen drew `+ Register schema` with
  * `aria-disabled="true"` and a true sentence saying the gateway served no endpoint that wrote one.
  *
  * Everything asserted here is decided somewhere a use-case test cannot see it — a status code, an envelope
  * field, an `details[0]`, and the permission the endpoint declares. The registry is a recording fake and
  * everything between it and the socket is real: the path codec, the body binding of ADR-020 Amendment 1,
  * the use case's read-only refusal, the mapping and `ErrorEnvelope.statusOf`.
  *
  * ==Where the 403 actually comes from==
  *
  * This service wires `RbacGuard.allowAll` today, exactly as the two compatibility writes do, and the
  * gateway is the enforcement point for a call that arrives through it. Both are the same `EndpointDecision`
  * over the same declaration this endpoint carries, so the two cases below are the two halves of that: one
  * calls `decide` directly on the published endpoint value, and one binds the route behind a real
  * `RbacGuard` and reads the status code. Neither composes a permission by hand — both read the
  * declaration `SchemaMutationEndpoints.registerVersion` publishes, which is the thing that would be wrong
  * if `Action.SchemaCreate` were the wrong action or the declaration were missing.
  */
final class SchemaRegistrationRoutesSuite extends KuiIOSuite {

  private val cluster = ClusterId.unsafe("local")
  private val readOnly = ClusterId.unsafe("frozen")
  private val orders = Subject.unsafe("orders-value")
  private val registrar = RoleName.unsafe("registrar")

  private val Csrf = "a-csrf-token"

  private val request = RegisterSchemaRequest(
    schemaType = "AVRO",
    definition = """{"type":"record","name":"Order","fields":[]}""",
    references = Nil
  )

  /** The bytes the gateway would put on the wire: the contract's own encoder, `noSpaces` (ADR-007). */
  private def bytesOf(value: RegisterSchemaRequest): Array[Byte] =
    Printer.noSpaces.print(value.asJson).getBytes(StandardCharsets.UTF_8)

  // -----------------------------------------------------------------------------------------------
  // A registry that records what it was asked to store
  // -----------------------------------------------------------------------------------------------

  private final class RecordingRegistry(
      rejection: Option[KuiError],
      version: Option[SchemaVersion],
      val stored: Ref[IO, List[(String, String, String)]]
  ) extends SchemaRegistryPort[IO] {

    def register(subject: Subject, proposed: ProposedSchema): IO[Either[KuiError, RegisteredVersion]] =
      stored.update(_ :+ ((subject.value, proposed.format.label, proposed.definition))) *>
        IO.pure(rejection.toLeft(RegisteredVersion(subject, SchemaId.unsafe(41), version)))

    def subjects = IO.pure(Right(List(orders)))
    def summary(subject: Subject) = IO.pure(Right(None))
    def versions(subject: Subject) = IO.pure(Right(None))
    def schema(subject: Subject, version: VersionSelector) = IO.pure(Right(None))
    def globalCompatibility = IO.pure(Right(CompatibilityLevel.Backward))
    def subjectCompatibility(subject: Subject) = IO.pure(Right(None))
    def setGlobalCompatibility(level: CompatibilityLevel) = IO.pure(Right(()))
    def setSubjectCompatibility(subject: Subject, level: CompatibilityLevel) = IO.pure(Right(()))

    def checkCompatibility(subject: Subject, version: VersionSelector, proposed: ProposedSchema) =
      IO.pure(Right(None))
  }

  /** The four bodied routes, with no socket, over one writable cluster and one read-only one.
    *
    * @param policy
    *   `RbacPolicy.Disabled` is what this service runs with; a real policy is passed only by the two cases
    *   that are about the permission the endpoint declares.
    */
  private def server(
      rejection: Option[KuiError] = None,
      policy: RbacPolicy = RbacPolicy.Disabled,
      version: Option[SchemaVersion] = Some(SchemaVersion.unsafe(3))
  ): Resource[IO, (Backend[IO], RecordingRegistry, FakeStructuredLogger[IO])] =
    Resource.eval(
      for {
        logger <- FakeStructuredLogger[IO]
        meter <- MeterProvider.noop[IO].get("kui.schema")
        rejections <- PrincipalVerification.rejectionCounter[IO](meter)
        stored <- Ref.of[IO, List[(String, String, String)]](Nil)
        interceptors <- SchemaApi.interceptors[IO](Telemetry.noop[IO], rejections, logger)
      } yield {
        val port = new RecordingRegistry(rejection, version, stored)

        val registries = new ClusterRegistries[IO] {
          private val profiles = List(
            RegistryProfile(cluster, "Local", hasRegistry = true, readOnly = false),
            RegistryProfile(readOnly, "Frozen", hasRegistry = true, readOnly = true)
          )
          def all = IO.pure(profiles)
          def profile(id: ClusterId) = IO.pure(profiles.find(_.cluster == id))
          def registry(id: ClusterId) = IO.pure(Option.when(profiles.exists(_.cluster == id))(port))
        }

        val guard =
          if policy.enabled then
            RbacGuard.fromPolicy[IO](policy, _ => ClusterFlags.Writable, logger)
          else RbacGuard.allowAll[IO]

        val secured = new SecuredRoutes[IO](codec, SchemaApi.Id, rejections, logger, guard)

        val routes = SchemaMutationRoutes[IO](
          SetCompatibilityUseCase
            .make[IO](registries, kui.observability.audit.LoggingAuditSink.make[IO](logger), logger),
          RegisterSchemaUseCase.make[IO](registries, logger),
          CompatibilityCheckUseCase.make[IO](registries),
          secured
        )

        val backend = TapirStubInterpreter(interceptors, BackendStub[IO](summon))
          .whenServerEndpointsRunLogic(routes)
          .backend()

        (backend, port, logger)
      }
    )

  // -----------------------------------------------------------------------------------------------
  // Speaking to it
  // -----------------------------------------------------------------------------------------------

  private val key: SigningKey =
    SigningKey("test-1", Secret(Array.fill[Byte](32)(7)), Instant.parse("2020-01-01T00:00:00Z"))

  private val codec: PrincipalCodec[IO] =
    JwsPrincipalCodec
      .make[IO](NonEmptyList.of(key), "kui-gateway")
      .getOrElse(throw new IllegalStateException("the test signing key is too short for HS256"))

  private def versionsPath(on: ClusterId): String =
    s"/internal/v1/clusters/${on.value}/schemas/subjects/${orders.value}/versions"

  /** A token bound to this method, this path **and these bytes** (ADR-020 Amendment 1). */
  private def token(
      path: String,
      body: Array[Byte],
      roles: Set[RoleName]
  ): IO[SignedPrincipal] =
    IO.realTimeInstant.flatMap(now =>
      codec.sign(
        PrincipalClaims(
          subject = UserName.unsafe("alice"),
          roles = roles,
          kind = PrincipalKind.Session,
          sessionRef = None,
          issuedAt = now,
          expiresAt = now.plusSeconds(60),
          audience = SchemaApi.Id,
          requestDigest = RequestDigests.of("POST", path, body)
        )
      )
    )

  private def post(
      backend: Backend[IO],
      path: String,
      body: RegisterSchemaRequest = request,
      roles: Set[RoleName] = Set.empty
  ): IO[Response[String]] =
    token(path, bytesOf(body), roles).flatMap(signed =>
      basicRequest
        .post(Uri.unsafeParse(s"http://schema$path"))
        .header(KuiEndpoint.PrincipalHeader, signed.value)
        .header(HttpHeaders.Csrf, Csrf)
        .contentType("application/json")
        .body(new String(bytesOf(body), StandardCharsets.UTF_8))
        .response(asStringAlways)
        .send(backend)
    )

  private def body(response: Response[String]): Json =
    parse(response.body).fold(failure => fail(s"not JSON: ${failure.message} in ${response.body}"), identity)

  /** A policy granting `registrar` the named actions over every subject on the writable cluster. */
  private def policyGranting(actions: Action*): RbacPolicy =
    RbacPolicy(
      roles = List(
        Role(
          name = registrar,
          clusters = Set(cluster),
          subjects = Nil,
          permissions = List(
            RbacPolicy.permission(
              kui.security.rbac.Resource.Schema,
              Some(ResourcePattern.compile(".*").getOrElse(fail("'.*' does not compile"))),
              actions.toSet
            )
          )
        )
      ),
      defaultRole = None
    )

  // -----------------------------------------------------------------------------------------------

  test("a valid schema registers and the answer carries the version") {
    server().use { (backend, registry, _) =>
      for {
        response <- post(backend, versionsPath(cluster))
        stored <- registry.stored.get
      } yield {
        val json = body(response).hcursor

        assertEquals(response.code.code, 200, response.body)
        assertEquals(json.get[String]("subject"), Right(orders.value))
        assertEquals(json.get[Int]("id"), Right(41))
        assertEquals(json.get[Option[Int]]("version"), Right(Some(3)))
        // The schema text reaches the registry byte for byte. A registry stores it verbatim and an
        // operator diffs KUI's panel against their registry's own screen.
        assertEquals(stored, List((orders.value, "AVRO", request.definition)))
      }
    }
  }

  test("the principal the route verified is the one the registration is recorded against") {
    // The seam this case exists for. `SchemaMutationRoutes.registerVersion` can hand the use case
    // `Principal.Anonymous` instead of the verified principal, and every other case in this service stays
    // green — because with no MutationRecord to write, the log line is the only place who-did-it appears,
    // and the route is the only place the token's identity exists. So the token says `alice` and the
    // audit-substitute line has to say `alice`.
    server().use { (backend, _, logger) =>
      for {
        response <- post(backend, versionsPath(cluster))
        entries <- logger.entriesWith("operation")
      } yield {
        assertEquals(response.code.code, 200, response.body)
        assertEquals(
          entries.flatMap(_.context.get("principal")),
          List("alice"),
          "the verified principal did not reach the use case"
        )
      }
    }
  }

  test("the schema language the caller named reaches the registry, and is not assumed to be Avro") {
    // `SchemaMapping.toRegister` can be hard-coded to `SchemaFormat.Avro` with everything else in this
    // service green, and a Protobuf schema would then be registered under the wrong type — or refused by
    // the registry with a message about Avro that the operator cannot act on. The registry's own word
    // travels unchanged, which is also why an unrecognised one is forwarded rather than refused here.
    server().use { (backend, registry, _) =>
      for {
        protobuf <- post(backend, versionsPath(cluster), request.copy(schemaType = "PROTOBUF"))
        exotic <- post(backend, versionsPath(cluster), request.copy(schemaType = "PARQUET-ISH"))
        stored <- registry.stored.get
      } yield {
        assertEquals(protobuf.code.code, 200, protobuf.body)
        assertEquals(exotic.code.code, 200, exotic.body)
        assertEquals(stored.map(_._2), List("PROTOBUF", "PARQUET-ISH"))
      }
    }
  }

  test("a registration the registry gave no version for answers no version, and never a zero") {
    // The registry's registration response is `{"id": N}` and the version comes from a second call, so
    // this is the state where the schema is stored and its number is unknown. Until this case existed,
    // `SchemaMapping.registered` could be changed to `.orElse(Some(0))` with every suite in this service
    // green — a version zero on the wire, which the registry numbers from one and nobody could look up.
    server(version = None).use { (backend, _, _) =>
      post(backend, versionsPath(cluster)).map { response =>
        val json = body(response).hcursor

        assertEquals(response.code.code, 200, response.body)
        assertEquals(json.get[Int]("id"), Right(41))
        assertEquals(json.get[Option[Int]]("version"), Right(None))
        // Absent, and not present-and-zero. The two are different JSON and only one of them is true.
        assertEquals(json.downField("version").focus, Some(Json.Null))
      }
    }
  }

  test("a registry rejection becomes KUI-VALIDATION with the registry's message") {
    // Not a 500 and not a swallowed error. The registry's sentence names the field that broke the rule,
    // and it is the only part of the answer an operator can act on; `details[0]` is where a form reads
    // the text it puts beside the input somebody typed into.
    val explanation =
      "Schema being registered is incompatible with an earlier schema for subject 'orders-value'"

    val rejection = kui.kernel.error.ApplicationError.Invalid(
      s"the schema registry refused the request: $explanation",
      List(kui.kernel.error.FieldError(Some("definition"), List(explanation)))
    )

    server(rejection = Some(rejection)).use { (backend, _, _) =>
      post(backend, versionsPath(cluster)).map { response =>
        val json = body(response).hcursor

        assertEquals(response.code.code, 400, response.body)
        assertEquals(json.get[String]("code"), Right("KUI-VALIDATION"))
        assertEquals(
          json.downField("details").downN(0).get[List[String]]("restrictions"),
          Right(List(explanation))
        )
        assertEquals(json.downField("details").downN(0).get[Option[String]]("field"), Right(Some("definition")))
      }
    }
  }

  test("a read-only cluster refuses with KUI-READ-ONLY and stores nothing") {
    server().use { (backend, registry, _) =>
      for {
        response <- post(backend, versionsPath(readOnly))
        stored <- registry.stored.get
      } yield {
        assertEquals(response.code.code, 405, response.body)
        assertEquals(body(response).hcursor.get[String]("code"), Right("KUI-READ-ONLY"))
        assertEquals(stored, Nil, "a refused registration still reached the registry")
      }
    }
  }

  test("a principal without SCHEMA:CREATE gets 403, and one with it is served") {
    // Both halves, because a refusal on its own is satisfiable by an endpoint that refuses everybody.
    // The policy differs by exactly one action and nothing else moves.
    server(policy = policyGranting(Action.SchemaView)).use { (backend, registry, _) =>
      for {
        refused <- post(backend, versionsPath(cluster), roles = Set(registrar))
        stored <- registry.stored.get
      } yield {
        assertEquals(refused.code.code, 403, refused.body)
        assertEquals(body(refused).hcursor.get[String]("code"), Right("KUI-FORBIDDEN"))
        assertEquals(stored, Nil)
      }
    } *>
      server(policy = policyGranting(Action.SchemaCreate)).use { (backend, _, _) =>
        post(backend, versionsPath(cluster), roles = Set(registrar)).map(response =>
          assertEquals(response.code.code, 200, response.body)
        )
      }
  }

  test("the endpoint declares SCHEMA:CREATE over the subject in the path, and the decision reads it") {
    // The declaration is the rule, and the gateway and this service are two enforcement points over the
    // one value. `decide` is the function both of them call, so this asserts the published endpoint
    // rather than an arrangement a case composed: change the action and this goes red.
    val segments = versionsPath(cluster).split('/').iterator.filter(_.nonEmpty).toList
    val holder = Principal(UserName.unsafe("alice"), Set(registrar), PrincipalKind.Session)

    def decide(policy: RbacPolicy): Either[String, Decision] =
      EndpointDecision.decide(
        policy,
        holder,
        ClusterFlags.Writable,
        SchemaMutationEndpoints.registerVersion,
        Some(cluster),
        segments
      )

    assertEquals(decide(policyGranting(Action.SchemaCreate)), Right(Decision.Allowed))
    assert(decide(policyGranting(Action.SchemaView)).exists(_ != Decision.Allowed))

    val declared = EndpointAuthorization
      .of(SchemaMutationEndpoints.registerVersion)
      .getOrElse(fail("the registration endpoint carries no authorization declaration"))

    assertEquals(declared.requirements.flatMap(_.actions.toList), List(Action.SchemaCreate))
  }

  test("a read-only cluster is refused before the permission is even the question") {
    // ADR-047 §2: the read-only gate is a property of the deployment's link to the cluster, not of
    // anybody's role, so a principal holding SCHEMA:CREATE is still refused.
    val segments = versionsPath(readOnly).split('/').iterator.filter(_.nonEmpty).toList
    val holder = Principal(UserName.unsafe("alice"), Set(registrar), PrincipalKind.Session)

    val decision = EndpointDecision.decide(
      policyGranting(Action.SchemaCreate),
      holder,
      ClusterFlags(readOnly = true),
      SchemaMutationEndpoints.registerVersion,
      Some(cluster),
      segments
    )

    assert(decision.exists(_ != Decision.Allowed), decision.toString)
  }
}
