package kui.identity.infrastructure

import java.time.Instant

import cats.effect.IO
import fs2.Stream
import io.circe.{Json, JsonObject}

import kui.config.store.{
  ConfigStore,
  SecretJson,
  StoreChange,
  StoreHealth,
  StoreKey,
  StoreRecord,
  StoreSection
}
import kui.identity.domain.{PasswordAlgorithm, PasswordHash, UserDirectory, UserRecord}
import kui.kernel.error.KuiError
import kui.kernel.{Secret, UserName}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The two account directories, and the three rules their comments argue for that nothing asserted.
  *
  * Measured one mutation at a time against `./mill services.identity.__.test + apps.allinone.test`, which
  * stayed at 98/98 green for each:
  *
  *   - `ConfiguredUserDirectory.key` losing its `toLowerCase`, so a person who signed in as `Admin` on
  *     Monday and `admin` on Tuesday is two people and the second has no account;
  *   - `ConfiguredUserDirectory.update` answering `Right(())`, so a password change against a file-backed
  *     deployment reports success and is gone at the next restart — the exact failure the refusal's own
  *     message was written to prevent;
  *   - `StoredUserDirectory.find` answering `None` when a stored record will not parse, which locks an
  *     operator out of the KUI they would use to fix it while their configured password still works.
  */
final class UserDirectoriesSuite extends KuiIOSuite {

  private val hash: PasswordHash =
    PasswordHash(PasswordAlgorithm.Pbkdf2HmacSha256, 210_000, "c2FsdA", "aGFzaA")

  private val changed: PasswordHash =
    PasswordHash(PasswordAlgorithm.Pbkdf2HmacSha256, 210_000, "c2FsdDI", "aGFzaDI")

  private val account: UserRecord =
    UserRecord(UserName.unsafe("Admin"), hash, Set("operators"), mustChangePassword = false)

  private val configured: UserDirectory[IO] = ConfiguredUserDirectory.fromRecords[IO](List(account))

  test("an account is found however the person capitalised their name when they signed in") {
    for {
      exact <- configured.find("Admin")
      lower <- configured.find("admin")
      shouty <- configured.find("ADMIN")
      padded <- configured.find("  admin  ")
      absent <- configured.find("someone-else")
    } yield {
      assertEquals(exact.map(_.name.value), Some("Admin"))
      assertEquals(lower.map(_.name.value), Some("Admin"))
      assertEquals(shouty.map(_.name.value), Some("Admin"))
      assertEquals(padded.map(_.name.value), Some("Admin"))
      // The other half of the rule: matching loosely must not mean matching everything.
      assertEquals(absent, None)
    }
  }

  test("a file-backed deployment refuses a password change and says what to configure instead") {
    configured.update(account.withPassword(changed)).map {
      case Left(refused) =>
        // The message names the key, because "you cannot do that" without "here is how you could" is the
        // refusal that turns into a support ticket.
        assert(refused.message.contains("kui.store"), s"the refusal must name the key: ${refused.message}")
      case Right(()) =>
        fail("a configuration file is the operator's; a KUI that rewrote it would fight whatever deployed it")
    }
  }

  test("a stored password overrides the configured one") {
    for {
      logger <- FakeStructuredLogger[IO]
      stored = StoredUserDirectory.make[IO](configured, holding(payloadOf(changed)), logger)
      found <- stored.find("admin")
    } yield assertEquals(found.map(_.hash), Some(changed))
  }

  test("a stored record that will not parse leaves the configured password working") {
    // Not `None`. A record KUI cannot read is a reason to log loudly and carry on, not a reason to lock an
    // operator out of the interface they would use to fix it.
    for {
      logger <- FakeStructuredLogger[IO]
      stored = StoredUserDirectory.make[IO](configured, holding(Json.obj()), logger)
      found <- stored.find("admin")
      lines <- logger.entries
    } yield {
      assertEquals(found.map(_.hash), Some(hash))
      assert(lines.nonEmpty, "an unreadable stored password must not be ignored silently")
    }
  }

  test("a metadata store that will not answer leaves the configured password working") {
    // A KUI nobody can sign in to during a Kafka outage is a KUI nobody can use to diagnose the outage.
    for {
      logger <- FakeStructuredLogger[IO]
      stored = StoredUserDirectory.make[IO](configured, unreachable, logger)
      found <- stored.find("admin")
    } yield assertEquals(found.map(_.hash), Some(hash))
  }

  test("the store may not introduce an account the configuration does not declare") {
    // A record that could would be a privilege escalation through a password-change endpoint.
    for {
      logger <- FakeStructuredLogger[IO]
      stored = StoredUserDirectory.make[IO](configured, holding(payloadOf(changed)), logger)
      found <- stored.find("nobody")
    } yield assertEquals(found, None)
  }

  private def payloadOf(value: PasswordHash): Json =
    Json.fromJsonObject(JsonObject("passwordHash" -> SecretJson.encoder(Secret(value.encoded))))

  /** A store holding one record, under whichever key it is asked for. */
  private def holding(payload: Json): ConfigStore[IO] =
    new StubStore {
      override def get(key: StoreKey): IO[Option[StoreRecord]] =
        IO.pure(
          Some(
            StoreRecord(
              envelopeVersion = 1,
              key = key,
              version = 1L,
              updatedAt = Instant.parse("2026-09-06T10:00:00Z"),
              updatedBy = "admin",
              deleted = false,
              payload = payload
            )
          )
        )
    }

  private def unreachable: ConfigStore[IO] =
    new StubStore {
      override def get(key: StoreKey): IO[Option[StoreRecord]] =
        IO.raiseError(new RuntimeException("the metadata store is not reachable"))
    }

  private abstract class StubStore extends ConfigStore[IO] {
    def get(key: StoreKey): IO[Option[StoreRecord]] = IO.pure(None)
    def list(section: StoreSection): IO[List[StoreRecord]] = IO.pure(Nil)

    def put(
        key: StoreKey,
        payload: Json,
        baseVersion: Option[Long],
        updatedBy: String
    ): IO[Either[KuiError, StoreRecord]] = IO.raiseError(new RuntimeException("not reached"))

    def delete(key: StoreKey, baseVersion: Long, updatedBy: String): IO[Either[KuiError, Unit]] =
      IO.raiseError(new RuntimeException("not reached"))

    def changes: Stream[IO, StoreChange] = Stream.empty
    def health: IO[StoreHealth] = IO.pure(StoreHealth.ReadOnly("a stub", Nil))
  }
}
