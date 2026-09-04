package kui.schema.api

import cats.effect.kernel.Async
import cats.syntax.all.*
import sttp.tapir.server.ServerEndpoint

import kui.http.principal.SecuredRoutes
import kui.schema.application.*
import kui.schema.contract.SchemaMutationEndpoints
import kui.schema.contract.dto.*

/** The three bodied routes: two that set a compatibility level, and one that only asks a question.
  *
  * ==Why all three use `withBody`==
  *
  * ADR-020 binds the gateway's signed principal to one call by hashing the method, the path **and the body**.
  * Tapir's security stage runs before the body is decoded and cannot see those bytes, so a bodied route
  * verified there refuses every call as `request_mismatch` — which is what happened the first time the
  * consumer service's reset wizard ran against a real cluster. `SecuredRoutes.withBody` verifies one stage
  * later and reconstructs the signed bytes by re-encoding the decoded input with the same codec the gateway
  * encoded it with (ADR-020 Amendment 1).
  *
  * That applies to the compatibility *check* as much as to the two writes, even though the check changes
  * nothing: it is a property of the request shape, not of what the request does.
  *
  * ==Read-only and audit are not decided here==
  *
  * `SetCompatibilityUseCase` owns both. It resolves the cluster, refuses a read-only one with `KUI-READ-ONLY`
  * **before contacting the registry**, and records the attempt either way with the level that was in force
  * before the change. A read-only check written out in this file would be a second copy of the rule, and the
  * copy that can disagree.
  *
  * The check route is not guarded and is not audited, deliberately: it registers nothing, and refusing a
  * read-only operator an answer to "would this schema be accepted" would send them to ask the registry
  * directly, which is worse for everyone than answering.
  */
object SchemaMutationRoutes {

  def apply[F[_]: Async](
      set: SetCompatibilityUseCase[F],
      check: CompatibilityCheckUseCase[F],
      secured: SchemaApi.Securing[F]
  ): List[ServerEndpoint[Any, F]] =
    List(setGlobal(set, secured), setForSubject(set, secured), checkCompatibility(check, secured))

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
