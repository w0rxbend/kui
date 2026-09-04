package kui.schema.application

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.kernel.ClusterId
import kui.kernel.Subject
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.schema.domain.*
import kui.testkit.fakes.FakeStructuredLogger
import kui.security.audit.{AuditSink, MutationRecord}

/** A registry that answers from a map, or refuses, on demand.
  *
  * Hand-written rather than mocked, because what these suites are about is *which* answer comes back —
  * `None`, a `Left`, or a value — and a stub whose behaviour is written out is the only kind whose answers
  * can be read beside the assertions that depend on them.
  */
final class FakeRegistry(
    val subjectsByName: Map[String, List[Int]] = Map.empty,
    val schemas: Map[(String, String), RegisteredSchema] = Map.empty,
    val globalLevel: CompatibilityLevel = CompatibilityLevel.Backward,
    val subjectLevels: Map[String, CompatibilityLevel] = Map.empty,
    val failure: Option[KuiError] = None,
    val writes: Ref[IO, List[(String, CompatibilityLevel)]]
) extends SchemaRegistryPort[IO] {

  private def answer[A](value: A): IO[Either[KuiError, A]] =
    IO.pure(failure.toLeft(value))

  def subjects: IO[Either[KuiError, List[Subject]]] =
    answer(subjectsByName.keys.toList.sorted.map(Subject.unsafe))

  def versions(subject: Subject): IO[Either[KuiError, Option[List[SchemaVersion]]]] =
    answer(subjectsByName.get(subject.value).map(_.map(SchemaVersion.unsafe)))

  def schema(subject: Subject, version: VersionSelector): IO[Either[KuiError, Option[RegisteredSchema]]] =
    answer(schemas.get(subject.value -> version.path))

  def globalCompatibility: IO[Either[KuiError, CompatibilityLevel]] = answer(globalLevel)

  def subjectCompatibility(subject: Subject): IO[Either[KuiError, Option[CompatibilityLevel]]] =
    answer(subjectLevels.get(subject.value))

  def setGlobalCompatibility(level: CompatibilityLevel): IO[Either[KuiError, Unit]] =
    writes.update(_ :+ ("global" -> level)) *> answer(())

  def setSubjectCompatibility(subject: Subject, level: CompatibilityLevel): IO[Either[KuiError, Unit]] =
    writes.update(_ :+ (subject.value -> level)) *> answer(())

  def checkCompatibility(
      subject: Subject,
      version: VersionSelector,
      proposed: ProposedSchema
  ): IO[Either[KuiError, Option[CompatibilityVerdict]]] =
    answer(
      Option.when(subjectsByName.contains(subject.value))(
        CompatibilityVerdict(proposed.definition.contains("compatible"), List("the registry said so"))
      )
    )
}

/** The clusters a suite pretends this deployment was configured with. */
final class FakeRegistries(
    val profiles: List[RegistryProfile],
    val ports: Map[ClusterId, SchemaRegistryPort[IO]]
) extends ClusterRegistries[IO] {

  def all: IO[List[RegistryProfile]] = IO.pure(profiles)

  def profile(cluster: ClusterId): IO[Option[RegistryProfile]] =
    IO.pure(profiles.find(_.cluster == cluster))

  def registry(cluster: ClusterId): IO[Option[SchemaRegistryPort[IO]]] = IO.pure(ports.get(cluster))
}

/** An audit sink that keeps what it was given, so a suite can assert what was recorded. */
final class RecordingAudit(val records: Ref[IO, List[MutationRecord]]) extends AuditSink[IO] {
  def record(entry: MutationRecord): IO[Unit] = records.update(_ :+ entry)
}

object SchemaRig {

  val WithRegistry: ClusterId = ClusterId.unsafe("with-registry")
  val WithoutRegistry: ClusterId = ClusterId.unsafe("no-registry")
  val ReadOnly: ClusterId = ClusterId.unsafe("read-only")
  val Unknown: ClusterId = ClusterId.unsafe("never-heard-of-it")

  def logger: IO[StructuredLogger[IO]] = FakeStructuredLogger[IO].widen

  val unreachable: KuiError = InfrastructureError.Unreachable("schema-registry", "connection refused")

  /** The three clusters every suite here uses: one with a registry, one without, one read-only. */
  def profiles: List[RegistryProfile] =
    List(
      RegistryProfile(WithRegistry, "With a registry", hasRegistry = true, readOnly = false),
      RegistryProfile(WithoutRegistry, "No registry here", hasRegistry = false, readOnly = false),
      RegistryProfile(ReadOnly, "Read only", hasRegistry = true, readOnly = true)
    )

  def registries(registry: FakeRegistry): FakeRegistries =
    new FakeRegistries(profiles, Map(WithRegistry -> registry, ReadOnly -> registry))

  def registry(
      subjects: Map[String, List[Int]] = Map.empty,
      schemas: Map[(String, String), RegisteredSchema] = Map.empty,
      globalLevel: CompatibilityLevel = CompatibilityLevel.Backward,
      subjectLevels: Map[String, CompatibilityLevel] = Map.empty,
      failure: Option[KuiError] = None
  ): IO[FakeRegistry] =
    Ref
      .of[IO, List[(String, CompatibilityLevel)]](Nil)
      .map(new FakeRegistry(subjects, schemas, globalLevel, subjectLevels, failure, _))
}
