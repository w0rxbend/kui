package kui.identity.application

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Ref

import kui.identity.domain.*
import kui.kernel.{RoleName, Secret, UserName}
import kui.security.audit.{AuthAuditSink, AuthenticationRecord}
import kui.security.rbac.*

/** The fakes the sign-in suites are written against.
  *
  * Hand-written rather than mocked, for the reason this codebase gives everywhere else: a mock verifies that a
  * method was called, and what these suites need to know is what the *system* did — which record reached the
  * audit trail, whether the directory was written to, how many times the hasher was asked to work.
  */
object IdentityFixtures {

  val Now: Instant = Instant.parse("2026-09-04T09:00:00Z")

  val Ada: UserName = UserName.unsafe("ada")

  /** A directory whose passwords are compared as plain strings.
    *
    * The real key derivation function is tested in `Pbkdf2PasswordHasherSuite`; using it here would add a
    * hundred milliseconds to every case and would test it a second time. What these suites are about is the
    * decisions taken *around* the check.
    */
  final class FakeHasher(val calls: Ref[IO, Int]) extends PasswordHasher[IO] {

    def hash(password: Secret[String]): IO[PasswordHash] =
      IO.pure(PasswordHash(PasswordAlgorithm.Current, 1, "c2FsdA", encode(password.value)))

    def verify(password: Secret[String], against: PasswordHash): IO[Boolean] =
      calls.update(_ + 1).as(against.hashBase64 == encode(password.value))

    private def encode(raw: String): String =
      java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(raw.getBytes("UTF-8"))
  }

  object FakeHasher {
    def make: IO[FakeHasher] = Ref.of[IO, Int](0).map(new FakeHasher(_))
  }

  /** An account whose password is `password`, hashed the way [[FakeHasher]] hashes. */
  def account(
      name: UserName = Ada,
      password: String = "correct horse battery staple",
      groups: Set[String] = Set.empty,
      mustChangePassword: Boolean = false
  ): UserRecord =
    UserRecord(
      name,
      PasswordHash(
        PasswordAlgorithm.Current,
        1,
        "c2FsdA",
        java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(password.getBytes("UTF-8"))
      ),
      groups,
      mustChangePassword
    )

  /** A directory that remembers what was written to it, and can be told to refuse writes. */
  final class FakeDirectory(
      state: Ref[IO, Map[String, UserRecord]],
      writable: Boolean
  ) extends UserDirectory[IO] {

    def find(username: String): IO[Option[UserRecord]] =
      state.get.map(_.get(username.trim.toLowerCase))

    def update(record: UserRecord): IO[Either[UpdateRefused, Unit]] =
      if !writable then IO.pure(Left(UpdateRefused("this deployment keeps its accounts in a file")))
      else state.update(_ + (record.name.value.toLowerCase -> record)).as(Right(()))

    def snapshot: IO[Map[String, UserRecord]] = state.get
  }

  object FakeDirectory {
    def make(records: List[UserRecord], writable: Boolean = true): IO[FakeDirectory] =
      Ref
        .of[IO, Map[String, UserRecord]](records.map(r => r.name.value.toLowerCase -> r).toMap)
        .map(new FakeDirectory(_, writable))
  }

  /** An audit sink that keeps everything it was given, in order. */
  final class RecordingAudit(val entries: Ref[IO, List[AuthenticationRecord]]) extends AuthAuditSink[IO] {
    def record(entry: AuthenticationRecord): IO[Unit] = entries.update(_ :+ entry)
  }

  object RecordingAudit {
    def make: IO[RecordingAudit] = Ref.of[IO, List[AuthenticationRecord]](Nil).map(new RecordingAudit(_))
  }

  /** A policy that puts everyone in the group `platform` — from KUI's own accounts — into `operators`. */
  val PlatformRole: Role =
    Role(
      name = RoleName.unsafe("operators"),
      clusters = Set(kui.kernel.ClusterId.unsafe("local")),
      subjects = List(Subject(Provider.Form, SubjectKind.Group, "platform", isRegex = false)),
      permissions = List(RbacPolicy.allPermission(Resource.Topic, Some(ResourcePattern.Everything)))
    )

  val Policy: RbacPolicy = RbacPolicy(List(PlatformRole), None)

  def formConfig(policy: RbacPolicy = RbacPolicy.Disabled): IdentityConfig =
    IdentityConfig(AuthMode.Form, None, policy)
}
