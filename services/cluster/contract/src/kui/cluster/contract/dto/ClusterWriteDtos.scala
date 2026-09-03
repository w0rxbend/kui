package kui.cluster.contract.dto

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.kernel.Secret

/** Keystore or truststore material, as a caller supplies it.
  *
  * The `Secret` values are unwrapped by hand in each codec below rather than through a published
  * `Codec[Secret[String]]`. A secret that could be encoded from anywhere is a secret that will be encoded
  * from somewhere; keeping the unwrapping local to the one family of types that legitimately receives
  * credentials keeps "which shapes can put a secret on the wire" answerable by reading one file.
  *
  * Inline base64 rather than a path: a KUI running in a container cannot read a file on the machine an
  * operator is typing on, and a path would be a promise the deployment could not keep.
  */
final case class StoreMaterialWrite(base64: Secret[String], password: Option[Secret[String]])

/** The one place a credential acquires a wire form, and it is write-only.
  *
  * A `Secret` that could be encoded from anywhere is a secret that will be encoded from somewhere, so the
  * unwrapping lives here, beside the only family of types that legitimately receives credentials, rather than
  * in `KernelCodecs` where every contract in KUI could reach it.
  */
object WriteSecrets {

  /** Documented as a plain string: the document has to describe the field a caller sends. What makes it safe
    * is that no response type in KUI has a field of this type, not that the schema hides it.
    */
  given Schema[Secret[String]] =
    Schema.string[Secret[String]].description("a credential; no endpoint ever returns one")
}

object StoreMaterialWrite {
  import WriteSecrets.given

  given Codec[StoreMaterialWrite] = Codec.from(
    (cursor: HCursor) =>
      for {
        base64 <- cursor.get[String]("base64")
        password <- cursor.get[Option[String]]("password")
      } yield StoreMaterialWrite(Secret(base64), password.map(Secret(_))),
    (dto: StoreMaterialWrite) =>
      Json.obj(
        "base64" -> dto.base64.value.asJson,
        "password" -> dto.password.map(_.value).asJson
      )
  )

  given Schema[StoreMaterialWrite] = Schema
    .derived[StoreMaterialWrite]
    .description("A keystore or truststore, base64 encoded, with its password")

  given CanEqual[StoreMaterialWrite, StoreMaterialWrite] = CanEqual.derived
}

/** How a caller says a cluster should be connected to.
  *
  * The protocol and mechanism are strings, matching what an operator writes in a configuration file and what
  * `ClusterSecurityDto` reports back — one vocabulary for reading and writing rather than two.
  */
final case class ClusterSecurityWrite(
    protocol: String,
    mechanism: Option[String],
    username: Option[String],
    password: Option[Secret[String]],
    truststore: Option[StoreMaterialWrite],
    keystore: Option[StoreMaterialWrite],
    verifyHostname: Boolean
)

object ClusterSecurityWrite {
  import WriteSecrets.given

  given Codec[ClusterSecurityWrite] = Codec.from(
    (cursor: HCursor) =>
      for {
        protocol <- cursor.get[String]("protocol")
        mechanism <- cursor.get[Option[String]]("mechanism")
        username <- cursor.get[Option[String]]("username")
        password <- cursor.get[Option[String]]("password")
        truststore <- cursor.get[Option[StoreMaterialWrite]]("truststore")
        keystore <- cursor.get[Option[StoreMaterialWrite]]("keystore")
        verifyHostname <- cursor.getOrElse[Boolean]("verifyHostname")(true)
      } yield ClusterSecurityWrite(
        protocol,
        mechanism,
        username,
        password.map(Secret(_)),
        truststore,
        keystore,
        verifyHostname
      ),
    (dto: ClusterSecurityWrite) =>
      Json.obj(
        "protocol" -> dto.protocol.asJson,
        "mechanism" -> dto.mechanism.asJson,
        "username" -> dto.username.asJson,
        "password" -> dto.password.map(_.value).asJson,
        "truststore" -> dto.truststore.asJson,
        "keystore" -> dto.keystore.asJson,
        "verifyHostname" -> dto.verifyHostname.asJson
      )
  )

  given Schema[ClusterSecurityWrite] =
    Schema.derived[ClusterSecurityWrite].description("How KUI should authenticate to this cluster")

  given CanEqual[ClusterSecurityWrite, ClusterSecurityWrite] = CanEqual.derived
}

/** The admin-client knobs a caller may set. Milliseconds and counts, because JSON has neither durations nor
  * refinement types, and the domain validates the ranges.
  */
final case class AdminTuningWrite(timeoutMs: Long, batchSize: Int, parallelism: Int)

object AdminTuningWrite {

  given Codec[AdminTuningWrite] = Codec.from(
    (cursor: HCursor) =>
      for {
        timeoutMs <- cursor.get[Long]("timeoutMs")
        batchSize <- cursor.get[Int]("batchSize")
        parallelism <- cursor.get[Int]("parallelism")
      } yield AdminTuningWrite(timeoutMs, batchSize, parallelism),
    (dto: AdminTuningWrite) =>
      Json.obj(
        "timeoutMs" -> dto.timeoutMs.asJson,
        "batchSize" -> dto.batchSize.asJson,
        "parallelism" -> dto.parallelism.asJson
      )
  )

  given Schema[AdminTuningWrite] =
    Schema.derived[AdminTuningWrite].description("Admin client timeouts, batching and concurrency")

  given CanEqual[AdminTuningWrite, AdminTuningWrite] = CanEqual.derived
}

/** What a caller sends to register or change a cluster.
  *
  * **This DTO carries secrets — that is the point of a write — and it is the only cluster type in KUI that
  * does.** Every secret field is a `Secret[String]`, whose `toString` redacts, so a body that reaches a log
  * line or an exception message carries `****` rather than a password. Nothing here is ever echoed back: the
  * response to a write is the *redacted* profile, because echoing would put every secret the caller just sent
  * back on the wire and into any proxy log between the two.
  *
  * @param properties
  *   ADR-022's override layer, applied last. Sensitive keys are classified by the kernel's own rules rather
  *   than by the caller saying which is which
  */
final case class ClusterWriteRequest(
    name: String,
    readOnly: Boolean,
    bootstrapServers: String,
    security: ClusterSecurityWrite,
    properties: Map[String, String],
    admin: AdminTuningWrite
)

object ClusterWriteRequest {

  given Codec[ClusterWriteRequest] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[String]("name")
        readOnly <- cursor.getOrElse[Boolean]("readOnly")(false)
        bootstrapServers <- cursor.get[String]("bootstrapServers")
        security <- cursor.get[ClusterSecurityWrite]("security")
        properties <- cursor.getOrElse[Map[String, String]]("properties")(Map.empty)
        admin <- cursor.get[AdminTuningWrite]("admin")
      } yield ClusterWriteRequest(name, readOnly, bootstrapServers, security, properties, admin),
    (dto: ClusterWriteRequest) =>
      Json.obj(
        "name" -> dto.name.asJson,
        "readOnly" -> dto.readOnly.asJson,
        "bootstrapServers" -> dto.bootstrapServers.asJson,
        "security" -> dto.security.asJson,
        "properties" -> dto.properties.asJson,
        "admin" -> dto.admin.asJson
      )
  )

  given Schema[ClusterWriteRequest] = Schema
    .derived[ClusterWriteRequest]
    .description("A cluster to register or replace. Carries credentials; the response does not")

  given CanEqual[ClusterWriteRequest, ClusterWriteRequest] = CanEqual.derived

}
