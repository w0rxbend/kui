package kui.contracts.cluster

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

/** How a cluster's connection is secured, with nothing secret in it.
  *
  * Two strings and two booleans, because that is everything a screen may know: which protocol, which SASL
  * mechanism, whether a truststore and a keystore were configured. A username is a secret-adjacent value — it
  * identifies a service account — and is deliberately absent, as are the password, the keystore bytes and the
  * rendered JAAS string. ADR-022's free-form `properties` override map is absent for a stronger reason: it
  * can hold arbitrary keys including `sasl.jaas.config`, and the only safe redaction of an arbitrary map is
  * not to send it at all.
  *
  * `protocol` and `mechanism` are plain strings rather than enums for the reason `ReasonCode.fromWire`
  * already gives: a browser that meets a mechanism a newer KUI supports must render the name it was given
  * rather than fail to decode the whole response.
  *
  * @param protocol
  *   `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT` or `SASL_SSL`
  * @param mechanism
  *   `PLAIN`, `SCRAM-SHA-256`, ...; `None` when the protocol is not a SASL one
  * @param truststoreConfigured
  *   whether a truststore was supplied — not what is in it
  * @param keystoreConfigured
  *   whether a client keystore was supplied, which is what mutual TLS looks like from outside
  */
final case class ClusterSecurityDto(
    protocol: String,
    mechanism: Option[String],
    truststoreConfigured: Boolean,
    keystoreConfigured: Boolean
)

object ClusterSecurityDto {

  given Codec[ClusterSecurityDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        protocol <- cursor.get[String]("protocol")
        mechanism <- cursor.get[Option[String]]("mechanism")
        truststore <- cursor.getOrElse[Boolean]("truststoreConfigured")(false)
        keystore <- cursor.getOrElse[Boolean]("keystoreConfigured")(false)
      } yield ClusterSecurityDto(protocol, mechanism, truststore, keystore),
    (dto: ClusterSecurityDto) =>
      Json.obj(
        "protocol" -> dto.protocol.asJson,
        "mechanism" -> dto.mechanism.asJson,
        "truststoreConfigured" -> dto.truststoreConfigured.asJson,
        "keystoreConfigured" -> dto.keystoreConfigured.asJson
      )
  )

  given Schema[ClusterSecurityDto] = Schema
    .derived[ClusterSecurityDto]
    .description("The shape of a cluster's security settings, with no credential in it")

  given CanEqual[ClusterSecurityDto, ClusterSecurityDto] = CanEqual.derived
}
