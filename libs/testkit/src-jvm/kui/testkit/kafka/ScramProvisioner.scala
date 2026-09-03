package kui.testkit.kafka

import scala.jdk.CollectionConverters.*

import cats.effect.Async
import cats.syntax.all.*
import org.apache.kafka.clients.admin.{
  Admin,
  ScramCredentialInfo,
  ScramMechanism,
  UserScramCredentialUpsertion
}

/** Creates the SCRAM user the SASL fixture authenticates as, on a broker that is already running.
  *
  * ==Why not `kafka-storage.sh format --add-scram`==
  *
  * The other way to get a SCRAM user is to write it into the storage directory before the broker starts,
  * which means overriding the image's entrypoint and formatting the log directory by hand. That couples the
  * fixture to one image's internal layout and breaks on any change to it. `Admin.alterUserScramCredentials`
  * has existed since Kafka 2.7 — below ADR-030's 2.8 floor — is plain Java, and needs no `docker exec`.
  *
  * ==The cost, stated plainly==
  *
  * Creating a user over the network means having a way in before any user exists, so the client listener also
  * offers `PLAIN` with one bootstrap account. That does not weaken what the SASL mode demonstrates. The
  * property being asserted is that **a client presenting no credentials at all is refused**, which is what
  * distinguishes a secured broker from an open one. "Exactly one mechanism is enabled" is a different claim,
  * and not one KUI makes.
  */
object ScramProvisioner {

  /** Bounded, because a broker that has logged `Kafka Server started` may still be a moment away from
    * answering, and a provisioning step that waits forever is indistinguishable from a hung test.
    */
  private val Attempts: Int = 20

  def create[F[_]: Async](bootstrap: String, admin: ScramCredentials, user: ScramCredentials): F[Unit] = {
    val properties = Map(
      "bootstrap.servers" -> bootstrap,
      "security.protocol" -> "SASL_PLAINTEXT",
      "sasl.mechanism" -> "PLAIN",
      "request.timeout.ms" -> "10000",
      "default.api.timeout.ms" -> "10000",
      "sasl.jaas.config" ->
        ("org.apache.kafka.common.security.plain.PlainLoginModule required " +
          s"""username="${admin.username}" password="${admin.password}";""")
    )

    retrying(Attempts) {
      Async[F].blocking {
        val client = Admin.create(properties.map((key, value) => key -> (value: Object)).asJava)
        try {
          val upsert = UserScramCredentialUpsertion(
            user.username,
            ScramCredentialInfo(ScramMechanism.SCRAM_SHA_512, 4096),
            user.password
          )
          val _ = client
            .alterUserScramCredentials(
              List(upsert: org.apache.kafka.clients.admin.UserScramCredentialAlteration).asJava
            )
            .all()
            .get()
        } finally client.close()
      }
    }
  }

  /** One retry loop, written out rather than pulled in, because the only thing being retried is "the broker
    * is not quite listening yet" and the only sensible response to the last failure is to let it out.
    */
  private def retrying[F[_]: Async](attempts: Int)(action: F[Unit]): F[Unit] =
    action.handleErrorWith { error =>
      if attempts <= 1 then Async[F].raiseError(error)
      else Async[F].sleep(scala.concurrent.duration.DurationInt(500).millis) *> retrying(attempts - 1)(action)
    }
}
