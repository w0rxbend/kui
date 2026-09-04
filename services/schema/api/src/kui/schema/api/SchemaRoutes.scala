package kui.schema.api

import cats.effect.kernel.Async
import cats.syntax.all.*
import sttp.tapir.server.ServerEndpoint

import kui.contracts.paging.PageDto
import kui.kernel.Subject
import kui.schema.application.*
import kui.schema.contract.SchemaEndpoints
import kui.schema.contract.dto.*

/** The five read endpoints, bound to use cases.
  *
  * One rule shapes every route in this file:
  *
  *   - a request naming a cluster KUI has never heard of **fails** with `404 KUI-CLUSTER-NOT-FOUND`;
  *   - a request naming a real cluster with no registry **fails** with `KUI-UNSUPPORTED`, which the browser
  *     has already been told about through the capability document and should never have called;
  *   - a request naming a real cluster whose registry did not answer **fails** with the upstream's own error,
  *     which the gateway turns into a degraded section rather than a broken page.
  *
  * The third case is not softened into an empty list, and that is deliberate. An empty subject list is a
  * claim that the registry holds no subjects — a claim that would send an operator looking for a deleted
  * schema rather than at a registry that is down.
  *
  * ==Nothing here decides anything==
  *
  * The three-way decision about clusters and registries lives in `RegistryQuery.on`, in the application
  * layer, and every use case goes through it. This module renames fields and nothing else.
  */
object SchemaRoutes {

  def apply[F[_]: Async](
      subjects: SubjectListUseCase[F],
      versions: SubjectVersionsUseCase[F],
      schema: SchemaVersionUseCase[F],
      compatibility: CompatibilityReadUseCase[F],
      secured: SchemaApi.Securing[F]
  ): List[ServerEndpoint[Any, F]] =
    // The order is the contract's own, and it is load bearing: `/schemas/compatibility` and
    // `/schemas/subjects/...` both sit under `/schemas`, and a router that tried the subject routes first
    // would answer the global compatibility request with a lookup of a subject named "compatibility".
    List(
      globalCompatibility(compatibility, secured),
      subjectList(subjects, secured),
      subjectCompatibility(compatibility, secured),
      subjectVersions(versions, secured),
      subjectSchema(schema, secured)
    )

  private def subjectList[F[_]: Async](
      subjects: SubjectListUseCase[F],
      secured: SchemaApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(SchemaEndpoints.subjects) { _ => (cluster, params) =>
      subjects
        .list(cluster, SchemaMapping.query(params))
        .map(_.map(page => PageDto.of[Subject, Subject](page)(identity)))
    }

  private def subjectVersions[F[_]: Async](
      versions: SubjectVersionsUseCase[F],
      secured: SchemaApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(SchemaEndpoints.versions) { _ => (cluster, subject) =>
      versions.versions(cluster, subject).map(_.map(SchemaMapping.versions(subject, _)))
    }

  private def subjectSchema[F[_]: Async](
      schema: SchemaVersionUseCase[F],
      secured: SchemaApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(SchemaEndpoints.schema) { _ => (cluster, subject, rawVersion) =>
      SchemaMapping.version(rawVersion) match {
        case Left(error) => error.asLeft[SchemaDto].pure[F]
        case Right(version) =>
          schema.schema(cluster, subject, version).map(_.map(SchemaMapping.schema))
      }
    }

  private def globalCompatibility[F[_]: Async](
      compatibility: CompatibilityReadUseCase[F],
      secured: SchemaApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(SchemaEndpoints.globalCompatibility) { _ => cluster =>
      compatibility.global(cluster).map(_.map(SchemaMapping.global))
    }

  private def subjectCompatibility[F[_]: Async](
      compatibility: CompatibilityReadUseCase[F],
      secured: SchemaApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(SchemaEndpoints.subjectCompatibility) { _ => (cluster, subject) =>
      compatibility.forSubject(cluster, subject).map(_.map(SchemaMapping.subjectCompatibility))
    }
}
