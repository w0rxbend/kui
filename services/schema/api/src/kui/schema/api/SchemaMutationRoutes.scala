package kui.schema.api

import cats.effect.kernel.Async
import cats.syntax.all.*
import sttp.tapir.server.ServerEndpoint

import kui.http.principal.SecuredRoutes
import kui.schema.application.*
import kui.schema.contract.SchemaMutationEndpoints
import kui.schema.contract.dto.*

/** The four bodied routes: two that set a compatibility level, one that registers a schema, and one that only
  * asks a question.
  *
  * ==Why all four use `withBody`==
  *
  * ADR-020 binds the gateway's signed principal to one call by hashing the method, the path **and the body**.
  * Tapir's security stage runs before the body is decoded and cannot see those bytes, so a bodied route
  * verified there refuses every call as `request_mismatch` — which is what happened the first time the
  * consumer service's reset wizard ran against a real cluster. `SecuredRoutes.withBody` verifies one stage
  * later and reconstructs the signed bytes by re-encoding the decoded input with the same codec the gateway
  * encoded it with (ADR-020 Amendment 1).
  *
  * That applies to the compatibility *check* as much as to the three writes, even though the check changes
  * nothing: it is a property of the request shape, not of what the request does.
  *
  * ==Read-only and audit are not decided here==
  *
  * `SetCompatibilityUseCase` owns both, and `RegisterSchemaUseCase` owns the read-only half for the
  * registration. Each resolves the cluster and refuses a read-only one with `KUI-READ-ONLY` **before
  * contacting the registry**, and the compatibility one records the attempt either way with the level that
  * was in force before the change. A read-only check written out in this file would be a second copy of the
  * rule, and the copy that can disagree.
  *
  * Registration writes no audit record, and `RegisterSchemaUseCase`'s header says why: `MutationKind` has no
  * case for it. That is a gap in this build rather than a decision this file gets to make.
  *
  * The check route is not guarded and is not audited, deliberately: it registers nothing, and refusing a
  * read-only operator an answer to "would this schema be accepted" would send them to ask the registry
  * directly, which is worse for everyone than answering.
  */
object SchemaMutationRoutes {

  def apply[F[_]: Async](
      set: SetCompatibilityUseCase[F],
      register: RegisterSchemaUseCase[F],
      check: CompatibilityCheckUseCase[F],
      secured: SchemaApi.Securing[F]
  ): List[ServerEndpoint[Any, F]] =
    List(
      setGlobal(set, secured),
      setForSubject(set, secured),
      registerVersion(register, secured),
      checkCompatibility(check, secured)
    )

  /** The registration. The principal reaches the use case, unlike the check's, because it is a mutation and
    * the log line that stands in for its audit record names who asked for it.
    */
  private def registerVersion[F[_]: Async](
      register: RegisterSchemaUseCase[F],
      secured: SchemaApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(SchemaMutationEndpoints.registerVersion)((_, _, _, request) =>
      SecuredRoutes.bodyBytes(request)
    ) { principal => (_, cluster, subject, request) =>
      register
        .register(principal, cluster, subject, SchemaMapping.toRegister(request))
        .map(_.map(SchemaMapping.registered))
    }

  private def setGlobal[F[_]: Async](
      set: SetCompatibilityUseCase[F],
      secured: SchemaApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(SchemaMutationEndpoints.setGlobalCompatibility)((_, _, request) =>
      SecuredRoutes.bodyBytes(request)
    ) { principal => (_, cluster, request) =>
      SchemaMapping.level(request.level) match {
        case Left(error) => error.asLeft[CompatibilityDto].pure[F]
        case Right(level) =>
          set.setGlobal(principal, cluster, level).map(_.map(SchemaMapping.global))
      }
    }

  private def setForSubject[F[_]: Async](
      set: SetCompatibilityUseCase[F],
      secured: SchemaApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(SchemaMutationEndpoints.setSubjectCompatibility)((_, _, _, request) =>
      SecuredRoutes.bodyBytes(request)
    ) { principal => (_, cluster, subject, request) =>
      SchemaMapping.level(request.level) match {
        case Left(error) => error.asLeft[CompatibilityDto].pure[F]
        case Right(level) =>
          set
            .setForSubject(principal, cluster, subject, level)
            .map(_.map(SchemaMapping.subjectCompatibility))
      }
    }

  private def checkCompatibility[F[_]: Async](
      check: CompatibilityCheckUseCase[F],
      secured: SchemaApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(SchemaMutationEndpoints.checkCompatibility)((_, _, _, request) =>
      SecuredRoutes.bodyBytes(request)
    ) { _ => (cluster, subject, rawVersion, request) =>
      SchemaMapping.version(rawVersion) match {
        case Left(error) => error.asLeft[CompatibilityCheckDto].pure[F]
        case Right(version) =>
          check
            .check(cluster, subject, version, SchemaMapping.proposed(request))
            .map(_.map(SchemaMapping.verdict))
      }
    }
}
