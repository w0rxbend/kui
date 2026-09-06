package kui.schema.application

import cats.effect.kernel.Temporal
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.{ClusterId, Subject}
import kui.schema.domain.*
import kui.security.Principal

/** Registering a schema under a subject: the only thing KUI does that adds to a registry's contents.
  *
  * ==Why this is an endpoint of this service and not a fold in the gateway==
  *
  * Everything a registration needs is here. The registry client with its breaker, its bulkhead and its
  * credentials is this service's; the read-only flag is on this service's own cluster profile; the schema
  * text has to be validated against a bound before it is forwarded, and the bound is this service's. A
  * gateway fold would have to be given all four, and the gateway does not decode service request bodies at
  * all (`NameSource.RequestBody`) — it proxies them. `SchemaMutationEndpoints` is already in
  * `ServiceContracts.byService`, so publishing the write here gives the gateway its public route with no
  * gateway change.
  *
  * ==The three refusals, in the order they are decided==
  *
  *   1. an unknown cluster is a 404, because the caller followed a link to something that is not there;
  *   1. a **read-only** cluster is `KUI-READ-ONLY`, decided before the registry is contacted, so an
  *      operator's proxy never logs a write KUI was always going to refuse (ADR-047 §2);
  *   1. a cluster with no registry is `KUI-UNSUPPORTED` naming the configuration key.
  *
  * The permission half — `SCHEMA:CREATE` over the subject — is declared on the endpoint and enforced by
  * `SecuredRoutes`' guard and by the gateway, which is the same path both compatibility writes take. It is
  * deliberately not re-decided here: two spellings of one rule is how the two enforcement points come to
  * disagree.
  *
  * ==What this does not do, and it is a real gap==
  *
  * It writes no audit record. ADR-047 §3 requires one for every mutation, and the record's `kind` is a
  * `kui.security.audit.MutationKind`, a sealed enum in `libs/security-core` with no case for registration.
  * Inventing a second vocabulary here — a string, a nearest-fitting neighbour — is exactly the drift
  * `SchemaEndpointClassificationSuite` exists to catch, so the gap is left open, logged at INFO with the same
  * four facts a record would carry, and named in that suite by an assertion that fails the day the enum grows
  * the case. Adding `case RegisterSchema extends MutationKind("schema.subject.version.register")` is the
  * whole of the fix, and this use case then takes an `AuditSink` the way [[SetCompatibilityUseCase]] does.
  */
trait RegisterSchemaUseCase[F[_]] {

  def register(
      principal: Principal,
      cluster: ClusterId,
      subject: Subject,
      proposed: ProposedSchema
  ): F[Either[KuiError, RegisteredVersion]]
}

object RegisterSchemaUseCase {

  /** The largest schema document KUI will forward to a registry.
    *
    * The compatibility check's bound, deliberately the same value and read from the same place: a document
    * KUI will check and refuse to register, or the other way round, is a difference an operator would find by
    * hitting it. See [[CompatibilityCheckUseCase.MaxDefinitionBytes]] for why the bound exists at all.
    */
  val MaxDefinitionBytes: Int = CompatibilityCheckUseCase.MaxDefinitionBytes

  def make[F[_]: Temporal](
      registries: ClusterRegistries[F],
      logger: StructuredLogger[F]
  ): RegisterSchemaUseCase[F] =
    new RegisterSchemaUseCase[F] {

      def register(
          principal: Principal,
          cluster: ClusterId,
          subject: Subject,
          proposed: ProposedSchema
      ): F[Either[KuiError, RegisteredVersion]] =
        validate(proposed) match {
          case Some(error) => error.asLeft[RegisteredVersion].pure[F]
          case None =>
            registries.profile(cluster).flatMap {
              case None =>
                RegistryAccess.unknownCluster(cluster).asLeft[RegisteredVersion].pure[F]

              // Before the registry is contacted, and before the port is even resolved: a read-only
              // deployment that opened a connection to say no would still have said no in the registry's
              // access log, which is the thing an operator then has to explain.
              case Some(profile) if profile.readOnly =>
                val refusal = ApplicationError.Refused(
                  ErrorCode.ReadOnly,
                  s"cluster ${profile.displayName} is configured read-only, so " +
                    s"$Operation is not accepted"
                )
                logger
                  .info(context(cluster, subject, principal))("refused: the cluster is read-only")
                  .as(refusal.asLeft[RegisteredVersion])

              case Some(_) =>
                registries.registry(cluster).flatMap {
                  case None =>
                    RegistryAccess.notConfigured(cluster).asLeft[RegisteredVersion].pure[F]
                  case Some(port) => through(port, cluster, subject, principal, proposed)
                }
            }
        }

      /** The registry call, and the log line that stands in for the audit record this build cannot write.
        *
        * Both outcomes are logged, for the reason ADR-047 gives about records: what somebody *tried* to
        * register on a production cluster is often the more interesting half.
        */
      private def through(
          port: SchemaRegistryPort[F],
          cluster: ClusterId,
          subject: Subject,
          principal: Principal,
          proposed: ProposedSchema
      ): F[Either[KuiError, RegisteredVersion]] =
        port.register(subject, proposed).flatTap {
          case Right(registered) =>
            logger.info(context(cluster, subject, principal))(
              s"registered schema id ${registered.id.value} under ${subject.value}" +
                registered.version.fold(
                  ", and the registry did not say which version it became"
                )(version => s" as version ${version.value}")
            )
          case Left(error) =>
            logger.warn(context(cluster, subject, principal))(
              s"the registration was refused: ${error.code.wire}: ${error.message}"
            )
        }

      /** Two refusals KUI makes itself, before anything leaves the process.
        *
        * An empty document is refused rather than forwarded because the registry's own answer to it is a 500
        * on several implementations, and "the registry is broken" is the wrong sentence for "you did not
        * paste anything". The size bound is the one every endpoint that forwards operator text to an upstream
        * needs.
        */
      private def validate(proposed: ProposedSchema): Option[KuiError] =
        if proposed.definition.trim.isEmpty then
          Some(
            ApplicationError.Invalid(
              "the schema is empty; paste the schema text to register it",
              Nil
            )
          )
        else if proposed.definition.length > MaxDefinitionBytes then
          Some(
            ApplicationError.Invalid(
              s"the schema is ${proposed.definition.length} characters, and the limit is " +
                s"$MaxDefinitionBytes",
              Nil
            )
          )
        else None

      private def context(
          cluster: ClusterId,
          subject: Subject,
          principal: Principal
      ): Map[String, String] =
        Map(
          "service.name" -> SchemaService.Id.value,
          "cluster.id" -> cluster.value,
          "operation" -> Operation,
          "resource" -> subject.value,
          "principal" -> principal.name.value
        )
    }

  /** The operation name, in the one place the use case and its log lines read it from.
    *
    * It is the same string as `SchemaMutationEndpoints.RegisterVersionOperation`, and the api module's suite
    * — the only module that can see a contract and an application type at once — is what asserts that,
    * exactly as it does for the two compatibility writes.
    */
  val Operation: String = "schema.subject.version.register"
}
