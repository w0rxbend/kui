package kui.cluster.infrastructure.store

import scala.concurrent.duration.*

import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Decoder, DecodingFailure, Encoder, Json}

import kui.cluster.domain.{ClusterProfile, ProfileOrigin, ProfileVersion}
import kui.config.store.SecretJson
import kui.kernel.cluster.*
import kui.kernel.{ClusterId, Secret}

/** The JSON a `cluster/<id>` record holds, written by hand.
  *
  * Explicit codecs rather than derivation (ADR-007), because this is the on-disk format of an operator's
  * configuration: a field renamed by a refactoring would silently stop being read, and every cluster in the
  * store would come back missing a setting. A hand-written codec makes that a compile error or a test failure
  * instead.
  *
  * ==Where the secrets go==
  *
  * Every `Secret[String]` is written as the store's plaintext marker, `{"$secret": "..."}`. It is the store's
  * crypto layer that replaces each marker with a ciphertext node before the record reaches Kafka, and this
  * module never encrypts anything itself — there is one AES-GCM implementation in KUI and it is not here.
  * What this file owes that layer is narrower and testable: every secret must be in the marker shape, and no
  * secret may reach the JSON through any other path. `ClusterRecordCodecSuite` asserts both, by encoding a
  * profile whose every secret is a distinctive token and checking that each token appears only under a
  * marker.
  *
  * ==What is not in the payload==
  *
  * The cluster id, because it is the key. The version and the origin, because they are facts about the
  * *record*, not about the profile: the store owns the version, and the origin is decided by the registry
  * that overlays a stored record on static configuration. Writing either of them into the payload would give
  * KUI two sources of truth for the same number.
  */
object ClusterRecordCodec {

  // ------------------------------------------------------------------ secrets

  private given Encoder[Secret[String]] = SecretJson.encoder
  private given Decoder[Secret[String]] = SecretJson.decoder

  // ------------------------------------------------------------------ stores

  private given Encoder[StoreType] = Encoder.encodeString.contramap {
    case StoreType.Jks => "jks"
    case StoreType.Pkcs12 => "pkcs12"
    case StoreType.Pem => "pem"
  }

  private given Decoder[StoreType] = Decoder.decodeString.emap {
    case "jks" => Right(StoreType.Jks)
    case "pkcs12" => Right(StoreType.Pkcs12)
    case "pem" => Right(StoreType.Pem)
    case other => Left(s"'$other' is not a store type; expected jks, pkcs12 or pem")
  }

  private given Encoder[StoreSource] = Encoder.instance {
    case StoreSource.Inline(base64) => Json.obj("type" -> "inline".asJson, "base64" -> base64.asJson)
    case StoreSource.FromPath(path) => Json.obj("type" -> "path".asJson, "path" -> path.asJson)
  }

  private given Decoder[StoreSource] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "inline" => cursor.get[Secret[String]]("base64").map(StoreSource.Inline.apply)
      case "path" => cursor.get[String]("path").map(StoreSource.FromPath.apply)
      case other =>
        Left(DecodingFailure(s"'$other' is not a store source; expected inline or path", cursor.history))
    }
  }

  private given Encoder[TrustStoreRef] = Encoder.instance { ref =>
    Json.obj(
      "source" -> ref.source.asJson,
      "password" -> ref.password.asJson,
      "storeType" -> ref.storeType.asJson
    )
  }

  private given Decoder[TrustStoreRef] = Decoder.instance { cursor =>
    for {
      source <- cursor.get[StoreSource]("source")
      password <- cursor.get[Option[Secret[String]]]("password")
      storeType <- cursor.get[StoreType]("storeType")
    } yield TrustStoreRef(source, password, storeType)
  }

  private given Encoder[KeyStoreRef] = Encoder.instance { ref =>
    Json.obj(
      "source" -> ref.source.asJson,
      "password" -> ref.password.asJson,
      "keyPassword" -> ref.keyPassword.asJson,
      "storeType" -> ref.storeType.asJson
    )
  }

  private given Decoder[KeyStoreRef] = Decoder.instance { cursor =>
    for {
      source <- cursor.get[StoreSource]("source")
      password <- cursor.get[Option[Secret[String]]]("password")
      keyPassword <- cursor.get[Option[Secret[String]]]("keyPassword")
      storeType <- cursor.get[StoreType]("storeType")
    } yield KeyStoreRef(source, password, keyPassword, storeType)
  }

  private given Encoder[TlsConfig] = Encoder.instance { tls =>
    Json.obj(
      "truststore" -> tls.truststore.asJson,
      "keystore" -> tls.keystore.asJson,
      "verifyHostname" -> tls.verifyHostname.asJson,
      "enabledProtocols" -> tls.enabledProtocols.asJson,
      "cipherSuites" -> tls.cipherSuites.asJson
    )
  }

  private given Decoder[TlsConfig] = Decoder.instance { cursor =>
    for {
      truststore <- cursor.get[Option[TrustStoreRef]]("truststore")
      keystore <- cursor.get[Option[KeyStoreRef]]("keystore")
      // No default. Hostname verification being off is a setting that must be visible in whatever wrote it,
      // and a decoder that supplied `true` for a missing field would let a record written by a future,
      // buggier writer come back as safe when it was not.
      verifyHostname <- cursor.get[Boolean]("verifyHostname")
      protocols <- cursor.get[Option[List[String]]]("enabledProtocols")
      ciphers <- cursor.get[Option[List[String]]]("cipherSuites")
    } yield TlsConfig(truststore, keystore, verifyHostname, protocols, ciphers)
  }

  // ------------------------------------------------------------------ SASL

  private given Encoder[SaslProtocol] = Encoder.encodeString.contramap {
    case SaslProtocol.SaslPlaintext => "sasl_plaintext"
    case SaslProtocol.SaslSsl => "sasl_ssl"
  }

  private given Decoder[SaslProtocol] = Decoder.decodeString.emap {
    case "sasl_plaintext" => Right(SaslProtocol.SaslPlaintext)
    case "sasl_ssl" => Right(SaslProtocol.SaslSsl)
    case other => Left(s"'$other' is not a SASL protocol; expected sasl_plaintext or sasl_ssl")
  }

  /** The discriminator is KUI's own token and not the Kafka `sasl.mechanism` value.
    *
    * Azure Entra, GCP Managed Kafka and a plain OAuth cluster all send `OAUTHBEARER` on the wire, so the wire
    * name cannot round-trip: a record written as Azure Entra would come back as generic OAuth, with a
    * different callback handler and a connection that fails to authenticate.
    */
  private given Encoder[SaslMechanism] = Encoder.instance {
    case SaslMechanism.Plain(username, password) =>
      Json.obj("type" -> "plain".asJson, "username" -> username.asJson, "password" -> password.asJson)
    case SaslMechanism.ScramSha256(username, password) =>
      Json.obj("type" -> "scram-sha-256".asJson, "username" -> username.asJson, "password" -> password.asJson)
    case SaslMechanism.ScramSha512(username, password) =>
      Json.obj("type" -> "scram-sha-512".asJson, "username" -> username.asJson, "password" -> password.asJson)
    case SaslMechanism.Gssapi(serviceName, principal, keyTab, useTicketCache, storeKey) =>
      Json.obj(
        "type" -> "gssapi".asJson,
        "serviceName" -> serviceName.asJson,
        "principal" -> principal.asJson,
        "keyTab" -> keyTab.asJson,
        "useTicketCache" -> useTicketCache.asJson,
        "storeKey" -> storeKey.asJson
      )
    case SaslMechanism.OAuthBearer(tokenEndpoint, clientId, clientSecret, scope) =>
      Json.obj(
        "type" -> "oauthbearer".asJson,
        "tokenEndpoint" -> tokenEndpoint.asJson,
        "clientId" -> clientId.asJson,
        "clientSecret" -> clientSecret.asJson,
        "scope" -> scope.asJson
      )
    case SaslMechanism.AwsMskIam(profile, roleArn, stsRegion) =>
      Json.obj(
        "type" -> "aws-msk-iam".asJson,
        "profile" -> profile.asJson,
        "roleArn" -> roleArn.asJson,
        "stsRegion" -> stsRegion.asJson
      )
    case SaslMechanism.AzureEntra(namespace, tokenEndpoint) =>
      Json.obj(
        "type" -> "azure-entra".asJson,
        "namespace" -> namespace.asJson,
        "tokenEndpoint" -> tokenEndpoint.asJson
      )
    case SaslMechanism.GcpManagedKafka => Json.obj("type" -> "gcp-managed-kafka".asJson)
  }

  private given Decoder[SaslMechanism] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "plain" =>
        (cursor.get[String]("username"), cursor.get[Secret[String]]("password"))
          .mapN(SaslMechanism.Plain.apply)
      case "scram-sha-256" =>
        (cursor.get[String]("username"), cursor.get[Secret[String]]("password"))
          .mapN(SaslMechanism.ScramSha256.apply)
      case "scram-sha-512" =>
        (cursor.get[String]("username"), cursor.get[Secret[String]]("password"))
          .mapN(SaslMechanism.ScramSha512.apply)
      case "gssapi" =>
        (
          cursor.get[String]("serviceName"),
          cursor.get[String]("principal"),
          cursor.get[Option[String]]("keyTab"),
          cursor.get[Boolean]("useTicketCache"),
          cursor.get[Boolean]("storeKey")
        ).mapN(SaslMechanism.Gssapi.apply)
      case "oauthbearer" =>
        (
          cursor.get[String]("tokenEndpoint"),
          cursor.get[String]("clientId"),
          cursor.get[Secret[String]]("clientSecret"),
          cursor.get[Option[String]]("scope")
        ).mapN(SaslMechanism.OAuthBearer.apply)
      case "aws-msk-iam" =>
        (
          cursor.get[Option[String]]("profile"),
          cursor.get[Option[String]]("roleArn"),
          cursor.get[Option[String]]("stsRegion")
        ).mapN(SaslMechanism.AwsMskIam.apply)
      case "azure-entra" =>
        (cursor.get[String]("namespace"), cursor.get[Option[String]]("tokenEndpoint"))
          .mapN(SaslMechanism.AzureEntra.apply)
      case "gcp-managed-kafka" => Right(SaslMechanism.GcpManagedKafka)
      case other =>
        Left(DecodingFailure(s"'$other' is not a SASL mechanism KUI knows", cursor.history))
    }
  }

  private given Encoder[ClusterSecurity] = Encoder.instance {
    case ClusterSecurity.Plaintext => Json.obj("type" -> "plaintext".asJson)
    case ClusterSecurity.Ssl(tls) => Json.obj("type" -> "ssl".asJson, "tls" -> tls.asJson)
    case ClusterSecurity.Sasl(protocol, mechanism, tls) =>
      Json.obj(
        "type" -> "sasl".asJson,
        "protocol" -> protocol.asJson,
        "mechanism" -> mechanism.asJson,
        "tls" -> tls.asJson
      )
  }

  private given Decoder[ClusterSecurity] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "plaintext" => Right(ClusterSecurity.Plaintext)
      case "ssl" => cursor.get[TlsConfig]("tls").map(ClusterSecurity.Ssl.apply)
      case "sasl" =>
        (
          cursor.get[SaslProtocol]("protocol"),
          cursor.get[SaslMechanism]("mechanism"),
          cursor.get[Option[TlsConfig]]("tls")
        ).mapN(ClusterSecurity.Sasl.apply)
      case other =>
        Left(
          DecodingFailure(s"'$other' is not a security mode; expected plaintext, ssl or sasl", cursor.history)
        )
    }
  }

  // ------------------------------------------------------------------ properties and tuning

  /** A sensitive override keeps the `$secret` marker, so an operator who pasted a password into the raw
    * property map gets the same encryption as one who used the typed fields. Anything else would make the
    * escape hatch the one place secrets are stored in the clear.
    */
  private given Encoder[ClientProperties] = Encoder.instance { properties =>
    Json.fromFields(properties.keys.toList.sorted.flatMap { key =>
      properties.get(key).map {
        case PropertyValue.Plain(value) => key -> Json.obj("value" -> value.asJson)
        case PropertyValue.Sensitive(value) => key -> Json.obj("secret" -> value.asJson)
      }
    })
  }

  private given Decoder[ClientProperties] = Decoder.instance { cursor =>
    cursor.as[Map[String, Json]].flatMap { raw =>
      raw.toList
        .traverse { (key, json) =>
          val plain = json.hcursor.get[String]("value").map(PropertyValue.Plain.apply)
          val secret = json.hcursor.get[Secret[String]]("secret").map(PropertyValue.Sensitive.apply)

          plain.orElse(secret).map(key -> _)
        }
        .map(entries => ClientProperties.fromMap(entries.toMap))
    }
  }

  /** Durations are milliseconds, as a number. Not a string like `"30s"`: parsing a duration is a second
    * grammar to get wrong, and this record is written by KUI and read by KUI.
    */
  private given Encoder[AdminTuning] = Encoder.instance { tuning =>
    Json.obj(
      "requestTimeoutMs" -> tuning.requestTimeout.toMillis.asJson,
      "apiTimeoutMs" -> tuning.apiTimeout.toMillis.asJson,
      "topicChunkSize" -> tuning.topicChunkSize.asJson,
      "partitionChunkSize" -> tuning.partitionChunkSize.asJson,
      "groupChunkSize" -> tuning.groupChunkSize.asJson,
      "parallelism" -> tuning.parallelism.asJson,
      "metadataRefreshMs" -> tuning.metadataRefresh.toMillis.asJson,
      "capabilityRefreshMs" -> tuning.capabilityRefresh.toMillis.asJson
    )
  }

  private given Decoder[AdminTuning] = Decoder.instance { cursor =>
    for {
      requestTimeout <- cursor.get[Long]("requestTimeoutMs")
      apiTimeout <- cursor.get[Long]("apiTimeoutMs")
      topicChunk <- cursor.get[Int]("topicChunkSize")
      partitionChunk <- cursor.get[Int]("partitionChunkSize")
      groupChunk <- cursor.get[Int]("groupChunkSize")
      parallelism <- cursor.get[Int]("parallelism")
      metadataRefresh <- cursor.get[Long]("metadataRefreshMs")
      capabilityRefresh <- cursor.get[Long]("capabilityRefreshMs")
    } yield AdminTuning(
      requestTimeout.millis,
      apiTimeout.millis,
      topicChunk,
      partitionChunk,
      groupChunk,
      parallelism,
      metadataRefresh.millis,
      capabilityRefresh.millis
    )
  }

  // ------------------------------------------------------------------ the payload

  /** The record payload for a profile. The id, the version and the origin are deliberately absent. */
  def encode(profile: ClusterProfile): Json =
    Json.obj(
      "displayName" -> profile.displayName.asJson,
      "bootstrapServers" -> profile.bootstrap.value.asJson,
      "security" -> profile.security.asJson,
      "properties" -> profile.properties.asJson,
      "admin" -> profile.admin.asJson,
      "readOnly" -> profile.readOnly.asJson,
      "colour" -> profile.colour.map(_.token).asJson
    )

  /** Rebuilds a profile from a payload, its key and the record's version.
    *
    * The domain's own `from` does the validating, so a record that was hand-edited into an illegal state — a
    * blank display name, an override of a property KUI renders itself — is refused here with the same message
    * an operator would have seen at startup, rather than failing later inside a refresh loop on a background
    * fiber.
    */
  def decode(
      id: ClusterId,
      version: ProfileVersion,
      origin: ProfileOrigin,
      payload: Json
  ): Either[String, ClusterProfile] = {
    val cursor = payload.hcursor

    val fields =
      for {
        displayName <- cursor.get[String]("displayName")
        bootstrapRaw <- cursor.get[String]("bootstrapServers")
        security <- cursor.get[ClusterSecurity]("security")
        properties <- cursor.get[ClientProperties]("properties")
        admin <- cursor.get[AdminTuning]("admin")
        readOnly <- cursor.get[Boolean]("readOnly")
        colour <- cursor.get[Option[String]]("colour")
      } yield (displayName, bootstrapRaw, security, properties, admin, readOnly, colour)

    for {
      decoded <- fields.leftMap(failure =>
        s"${failure.message} at ${io.circe.CursorOp.opsToPath(failure.history)}"
      )
      (displayName, bootstrapRaw, security, properties, admin, readOnly, colour) = decoded
      bootstrap <- BootstrapServers.from(bootstrapRaw).leftMap(_.message)
      profile <- ClusterProfile
        .from(id, displayName, bootstrap, security, properties, admin, readOnly, colour, version, origin)
        .leftMap(_.message)
    } yield profile
  }
}
