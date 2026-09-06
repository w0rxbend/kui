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
    val formats: Map[String, SchemaFormat] = Map.empty,
    val unenrichable: Set[String] = Set.empty,
    val vanished: Set[String] = Set.empty,
    val failure: Option[KuiError] = None,
    val writes: Ref[IO, List[(String, CompatibilityLevel)]],
    val enrichments: Ref[IO, List[String]],
    val globalReads: Ref[IO, Int]
) extends SchemaRegistryPort[IO] {

  private def answer[A](value: A): IO[Either[KuiError, A]] =
    IO.pure(failure.toLeft(value))

  /** `vanished` names are listed and known to nothing else, which is the shape of a subject deleted between
    * the list call and the call that would have enriched it.
    */
  def subjects: IO[Either[KuiError, List[Subject]]] =
    answer((subjectsByName.keySet ++ vanished).toList.sorted.map(Subject.unsafe))

  /** Every call is recorded, because the number of them is the promise the list page makes.
    *
    * A subject in `unenrichable` refuses the way a registry that stopped answering mid-page does. It is a
    * per-subject switch rather than the whole-registry `failure` for exactly that reason: the interesting
    * case is one row failing while the page around it succeeds.
    */
  def summary(subject: Subject): IO[Either[KuiError, Option[SubjectSummary]]] =
    enrichments.update(_ :+ subject.value) *> {
      if unenrichable.contains(subject.value) then IO.pure(Left(SchemaRig.unreachable))
      else
        answer(
          subjectsByName
            .get(subject.value)
            .map(versions =>
              SubjectSummary(
                subject = subject,
                format = formats.get(subject.value),
                versionCount = Some(versions.size),
                compatibility = subjectLevels.get(subject.value).map(SubjectCompatibility.own)
              )
            )
        )
    }

  def versions(subject: Subject): IO[Either[KuiError, Option[List[SchemaVersion]]]] =
    answer(subjectsByName.get(subject.value).map(_.map(SchemaVersion.unsafe)))

  def schema(subject: Subject, version: VersionSelector): IO[Either[KuiError, Option[RegisteredSchema]]] =
    answer(schemas.get(subject.value -> version.path))

  /** Counted, for the same reason the per-subject calls are.
    *
    * The registry-wide compatibility level is one call the list page makes on top of its rows, and whether it
    * is made is the difference between a page that short-circuited and one that did not. Nothing about the
    * rows shows it: an empty page has no rows either way. Until this counter existed, the case named "a page
    * with no rows asks the registry nothing beyond the list itself" asserted only that no *row* was enriched,
    * and turning the short-circuit off left it green.
    */
  def globalCompatibility: IO[Either[KuiError, CompatibilityLevel]] =
    globalReads.update(_ + 1) *> answer(globalLevel)

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
      formats: Map[String, SchemaFormat] = Map.empty,
      unenrichable: Set[String] = Set.empty,
      vanished: Set[String] = Set.empty,
      failure: Option[KuiError] = None
  ): IO[FakeRegistry] =
    for {
      writes <- Ref.of[IO, List[(String, CompatibilityLevel)]](Nil)
      enrichments <- Ref.of[IO, List[String]](Nil)
      globalReads <- Ref.of[IO, Int](0)
    } yield new FakeRegistry(
      subjects,
      schemas,
      globalLevel,
      subjectLevels,
      formats,
      unenrichable,
      vanished,
      failure,
      writes,
      enrichments,
      globalReads
    )
}
