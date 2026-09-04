package kui.cluster.contract.dto

import scala.concurrent.duration.{DurationLong, FiniteDuration}

import io.circe.syntax.*
import io.circe.{Codec, Decoder, DecodingFailure, Encoder, HCursor, Json}
import sttp.tapir.Schema

import kui.kernel.Secret
import kui.kernel.cluster.{
  AdminTuning,
  BootstrapServers,
  ClientProperties,
  ClusterSecurity,
  KeyStoreRef,
  PropertyValue,
  SaslMechanism,
  SaslProtocol,
  StoreSource,
  StoreType,
  TlsConfig,
  TrustStoreRef
}

/** The wire form of everything a Kafka client is built from — credentials included.
  *
  * ==This is the only file in KUI that can write a credential==
  *
  * A `Secret[String]` has no `Encoder` anywhere else: `libs/contracts-core`'s `KernelCodecs` deliberately
  * gives it none, so a public DTO that tried to carry one would not compile. The encoder below is the single
  * exception, it lives in a module that only the internal profile endpoint uses, and
  * `kui.cluster.api.SecretLeakSuite` asserts on every declared endpoint that its output is the one place a
  * secret can appear.
  *
  * ==Why the codecs are written out rather than derived==
  *
  * ADR-007 forbids automatic derivation for the ordinary reason — a derived codec makes a field's wire name a
  * consequence of a Scala identifier — and here there is a second, sharper one. A derived encoder over
  * `ClusterSecurity` would pick up any field added to the ADT later, including one that is not a `Secret`,
  * and put it on the wire without anybody deciding to. Written out, adding a field is a compile error in this
  * file, which is exactly where the decision belongs.
  *
  * ==Why a discriminated object rather than a flat one==
  *
  * Each sum type encodes as `{"kind": "...", ...}`. The alternative — inferring the case from which fields
  * are present — makes every future case a potential ambiguity with an existing one, and the failure is a
  * profile silently decoding as the wrong mechanism, which shows up as an authentication error against a
  * production cluster.
  */
object ClusterConnectionCodecs {

  /** The only `Encoder[Secret[String]]` in the codebase. See the class comment. */
  given Codec[Secret[String]] = Codec.from(
    Decoder[String].map(Secret(_)),
    Encoder[String].contramap(_.value)
  )

  /** A secret is a string on the wire and an opaque one in the generated document: the description says what
    * it is so that an operator reading the OpenAPI knows this endpoint is credential-bearing.
    */
  given Schema[Secret[String]] =
    Schema.string[Secret[String]].description("A credential. Internal channel only; never on /api/v1")

  given Codec[BootstrapServers] = Codec.from(
    Decoder[String].emap(BootstrapServers.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Schema[BootstrapServers] =
    Schema.string[BootstrapServers].description("host:port[,host:port] as Kafka's bootstrap.servers wants it")

  given Codec[StoreType] = Codec.from(
    Decoder[String].emap(raw =>
      StoreType.values.find(_.wireName == raw).toRight(s"'$raw' is not a key or trust store type")
    ),
    Encoder[String].contramap(_.wireName)
  )

  given Schema[StoreType] = Schema.string[StoreType].description("JKS, PKCS12 or PEM")

  given Codec[StoreSource] = Codec.from(
    (cursor: HCursor) =>
      cursor.get[String]("kind").flatMap {
        case "inline" => cursor.get[Secret[String]]("base64").map(StoreSource.Inline(_))
        case "path" => cursor.get[String]("path").map(StoreSource.FromPath(_))
        case other => Left(DecodingFailure(s"'$other' is not a store source", cursor.history))
      },
    {
      case StoreSource.Inline(base64) =>
        Json.obj("kind" -> "inline".asJson, "base64" -> base64.asJson)
      case StoreSource.FromPath(path) =>
        Json.obj("kind" -> "path".asJson, "path" -> path.asJson)
    }
  )

  given Schema[StoreSource] = Schema.derived[StoreSource]

  given Codec[TrustStoreRef] = Codec.from(
    (cursor: HCursor) =>
      for {
        source <- cursor.get[StoreSource]("source")
        password <- cursor.get[Option[Secret[String]]]("password")
        storeType <- cursor.get[StoreType]("storeType")
      } yield TrustStoreRef(source, password, storeType),
    (ref: TrustStoreRef) =>
      Json.obj(
        "source" -> ref.source.asJson,
        "password" -> ref.password.asJson,
        "storeType" -> ref.storeType.asJson
      )
  )

  given Schema[TrustStoreRef] = Schema.derived[TrustStoreRef]

  given Codec[KeyStoreRef] = Codec.from(
    (cursor: HCursor) =>
      for {
        source <- cursor.get[StoreSource]("source")
        password <- cursor.get[Option[Secret[String]]]("password")
        keyPassword <- cursor.get[Option[Secret[String]]]("keyPassword")
        storeType <- cursor.get[StoreType]("storeType")
      } yield KeyStoreRef(source, password, keyPassword, storeType),
    (ref: KeyStoreRef) =>
      Json.obj(
        "source" -> ref.source.asJson,
        "password" -> ref.password.asJson,
        "keyPassword" -> ref.keyPassword.asJson,
        "storeType" -> ref.storeType.asJson
      )
  )

  given Schema[KeyStoreRef] = Schema.derived[KeyStoreRef]

  given Codec[TlsConfig] = Codec.from(
    (cursor: HCursor) =>
      for {
        truststore <- cursor.get[Option[TrustStoreRef]]("truststore")
        keystore <- cursor.get[Option[KeyStoreRef]]("keystore")
        // No default. `verifyHostname` has no safe default that is also honest: `true` would silently
        // re-enable a check the producer had turned off, and `false` would silently disable one it had
        // not. A profile that does not say is a profile this consumer must refuse.
        verifyHostname <- cursor.get[Boolean]("verifyHostname")
        enabledProtocols <- cursor.get[Option[List[String]]]("enabledProtocols")
        cipherSuites <- cursor.get[Option[List[String]]]("cipherSuites")
      } yield TlsConfig(truststore, keystore, verifyHostname, enabledProtocols, cipherSuites),
    (tls: TlsConfig) =>
      Json.obj(
        "truststore" -> tls.truststore.asJson,
        "keystore" -> tls.keystore.asJson,
        "verifyHostname" -> tls.verifyHostname.asJson,
        "enabledProtocols" -> tls.enabledProtocols.asJson,
        "cipherSuites" -> tls.cipherSuites.asJson
      )
  )

  given Schema[TlsConfig] = Schema.derived[TlsConfig]

  given Codec[SaslProtocol] = Codec.from(
    Decoder[String].emap {
      case "SASL_PLAINTEXT" => Right(SaslProtocol.SaslPlaintext)
      case "SASL_SSL" => Right(SaslProtocol.SaslSsl)
      case other => Left(s"'$other' is not a SASL protocol")
    },
    Encoder[String].contramap {
      case SaslProtocol.SaslPlaintext => "SASL_PLAINTEXT"
      case SaslProtocol.SaslSsl => "SASL_SSL"
    }
  )

  given Schema[SaslProtocol] = Schema.string[SaslProtocol].description("SASL_PLAINTEXT or SASL_SSL")

  /** The `kind` discriminator, one per case.
    *
    * Spelled out rather than derived from the case name, for the reason `SearchMode.wire` gives: renaming a
    * Scala case must not change a wire contract that a running consumer is already decoding.
    */
  private object MechanismKind {
    val Plain = "plain"
    val ScramSha256 = "scram-sha-256"
    val ScramSha512 = "scram-sha-512"
    val Gssapi = "gssapi"
    val OAuthBearer = "oauthbearer"
    val AwsMskIam = "aws-msk-iam"
    val AzureEntra = "azure-entra"
    val GcpManagedKafka = "gcp-managed-kafka"
  }

  given Codec[SaslMechanism] = Codec.from(
    (cursor: HCursor) =>
      cursor.get[String]("kind").flatMap {
        case MechanismKind.Plain => userAndPassword(cursor).map(SaslMechanism.Plain(_, _))
        case MechanismKind.ScramSha256 => userAndPassword(cursor).map(SaslMechanism.ScramSha256(_, _))
        case MechanismKind.ScramSha512 => userAndPassword(cursor).map(SaslMechanism.ScramSha512(_, _))
        case MechanismKind.Gssapi =>
          for {
            serviceName <- cursor.get[String]("serviceName")
            principal <- cursor.get[String]("principal")
            keyTab <- cursor.get[Option[String]]("keyTab")
            useTicketCache <- cursor.get[Boolean]("useTicketCache")
            storeKey <- cursor.get[Boolean]("storeKey")
          } yield SaslMechanism.Gssapi(serviceName, principal, keyTab, useTicketCache, storeKey)
        case MechanismKind.OAuthBearer =>
          for {
            tokenEndpoint <- cursor.get[String]("tokenEndpoint")
            clientId <- cursor.get[String]("clientId")
            clientSecret <- cursor.get[Secret[String]]("clientSecret")
            scope <- cursor.get[Option[String]]("scope")
          } yield SaslMechanism.OAuthBearer(tokenEndpoint, clientId, clientSecret, scope)
        case MechanismKind.AwsMskIam =>
          for {
            profile <- cursor.get[Option[String]]("profile")
            roleArn <- cursor.get[Option[String]]("roleArn")
            stsRegion <- cursor.get[Option[String]]("stsRegion")
          } yield SaslMechanism.AwsMskIam(profile, roleArn, stsRegion)
        case MechanismKind.AzureEntra =>
          for {
            namespace <- cursor.get[String]("namespace")
            tokenEndpoint <- cursor.get[Option[String]]("tokenEndpoint")
          } yield SaslMechanism.AzureEntra(namespace, tokenEndpoint)
        case MechanismKind.GcpManagedKafka => Right(SaslMechanism.GcpManagedKafka)
        case other => Left(DecodingFailure(s"'$other' is not a SASL mechanism", cursor.history))
      },
    {
      case SaslMechanism.Plain(username, password) => credential(MechanismKind.Plain, username, password)
      case SaslMechanism.ScramSha256(username, password) =>
        credential(MechanismKind.ScramSha256, username, password)
      case SaslMechanism.ScramSha512(username, password) =>
        credential(MechanismKind.ScramSha512, username, password)
      case SaslMechanism.Gssapi(serviceName, principal, keyTab, useTicketCache, storeKey) =>
        Json.obj(
          "kind" -> MechanismKind.Gssapi.asJson,
          "serviceName" -> serviceName.asJson,
          "principal" -> principal.asJson,
          "keyTab" -> keyTab.asJson,
          "useTicketCache" -> useTicketCache.asJson,
          "storeKey" -> storeKey.asJson
        )
      case SaslMechanism.OAuthBearer(tokenEndpoint, clientId, clientSecret, scope) =>
        Json.obj(
          "kind" -> MechanismKind.OAuthBearer.asJson,
          "tokenEndpoint" -> tokenEndpoint.asJson,
          "clientId" -> clientId.asJson,
          "clientSecret" -> clientSecret.asJson,
          "scope" -> scope.asJson
        )
      case SaslMechanism.AwsMskIam(profile, roleArn, stsRegion) =>
        Json.obj(
          "kind" -> MechanismKind.AwsMskIam.asJson,
          "profile" -> profile.asJson,
          "roleArn" -> roleArn.asJson,
          "stsRegion" -> stsRegion.asJson
        )
      case SaslMechanism.AzureEntra(namespace, tokenEndpoint) =>
        Json.obj(
          "kind" -> MechanismKind.AzureEntra.asJson,
          "namespace" -> namespace.asJson,
          "tokenEndpoint" -> tokenEndpoint.asJson
        )
      case SaslMechanism.GcpManagedKafka => Json.obj("kind" -> MechanismKind.GcpManagedKafka.asJson)
    }
  )

  given Schema[SaslMechanism] = Schema.derived[SaslMechanism]

  private def userAndPassword(cursor: HCursor): Decoder.Result[(String, Secret[String])] =
    for {
      username <- cursor.get[String]("username")
      password <- cursor.get[Secret[String]]("password")
    } yield (username, password)

  private def credential(kind: String, username: String, password: Secret[String]): Json =
    Json.obj("kind" -> kind.asJson, "username" -> username.asJson, "password" -> password.asJson)

  given Codec[ClusterSecurity] = Codec.from(
    (cursor: HCursor) =>
      cursor.get[String]("kind").flatMap {
        case "plaintext" => Right(ClusterSecurity.Plaintext)
        case "ssl" => cursor.get[TlsConfig]("tls").map(ClusterSecurity.Ssl(_))
        case "sasl" =>
          for {
            protocol <- cursor.get[SaslProtocol]("protocol")
            mechanism <- cursor.get[SaslMechanism]("mechanism")
            tls <- cursor.get[Option[TlsConfig]]("tls")
          } yield ClusterSecurity.Sasl(protocol, mechanism, tls)
        case other => Left(DecodingFailure(s"'$other' is not a cluster security mode", cursor.history))
      },
    {
      case ClusterSecurity.Plaintext => Json.obj("kind" -> "plaintext".asJson)
      case ClusterSecurity.Ssl(tls) => Json.obj("kind" -> "ssl".asJson, "tls" -> tls.asJson)
      case ClusterSecurity.Sasl(protocol, mechanism, tls) =>
        Json.obj(
          "kind" -> "sasl".asJson,
          "protocol" -> protocol.asJson,
          "mechanism" -> mechanism.asJson,
          "tls" -> tls.asJson
        )
    }
  )

  given Schema[ClusterSecurity] = Schema.derived[ClusterSecurity]

  /** A property value carries whether it is sensitive, so that the consumer rebuilds a `ClientProperties`
    * that still knows which of its own keys must never be logged. Losing that flag in transit would mean the
    * consuming service redacts nothing, which is how `sasl.jaas.config` ends up in a log line two services
    * away from the one that was careful about it.
    */
  given Codec[PropertyValue] = Codec.from(
    (cursor: HCursor) =>
      for {
        sensitive <- cursor.getOrElse[Boolean]("sensitive")(false)
        value <- cursor.get[String]("value")
      } yield if sensitive then PropertyValue.Sensitive(Secret(value)) else PropertyValue.Plain(value),
    (property: PropertyValue) =>
      Json.obj(
        "sensitive" -> (property match {
          case PropertyValue.Plain(_) => false
          case PropertyValue.Sensitive(_) => true
        }).asJson,
        "value" -> property.unsafeValue.asJson
      )
  )

  given Schema[PropertyValue] = Schema.derived[PropertyValue]

  given Codec[ClientProperties] = Codec.from(
    Decoder[Map[String, PropertyValue]].map(ClientProperties.fromMap),
    // Keys sorted, so the same overrides always produce the same bytes. A `Map`'s own iteration order is
    // not a contract, and an ETag-driven consumer that re-fetched because two identical profiles hashed
    // differently would rebuild every Kafka client for nothing.
    (properties: ClientProperties) =>
      Json.fromFields(properties.entries.toList.sortBy(_._1).map((key, value) => key -> value.asJson))
  )

  given Schema[ClientProperties] =
    Schema.schemaForMap[PropertyValue].as[ClientProperties].description("Raw Kafka client property overrides")

  /** Durations travel as whole milliseconds, which is the unit every Kafka client property is expressed in
    * anyway, so nothing has to decide what `"30s"` means on the far side.
    */
  given Codec[AdminTuning] = Codec.from(
    (cursor: HCursor) =>
      for {
        requestTimeoutMs <- cursor.get[Long]("requestTimeoutMs")
        apiTimeoutMs <- cursor.get[Long]("apiTimeoutMs")
        topicChunkSize <- cursor.get[Int]("topicChunkSize")
        partitionChunkSize <- cursor.get[Int]("partitionChunkSize")
        groupChunkSize <- cursor.get[Int]("groupChunkSize")
        parallelism <- cursor.get[Int]("parallelism")
        metadataRefreshMs <- cursor.get[Long]("metadataRefreshMs")
        capabilityRefreshMs <- cursor.get[Long]("capabilityRefreshMs")
      } yield AdminTuning(
        requestTimeout = millis(requestTimeoutMs),
        apiTimeout = millis(apiTimeoutMs),
        topicChunkSize = topicChunkSize,
        partitionChunkSize = partitionChunkSize,
        groupChunkSize = groupChunkSize,
        parallelism = parallelism,
        metadataRefresh = millis(metadataRefreshMs),
        capabilityRefresh = millis(capabilityRefreshMs)
      ),
    (admin: AdminTuning) =>
      Json.obj(
        "requestTimeoutMs" -> admin.requestTimeout.toMillis.asJson,
        "apiTimeoutMs" -> admin.apiTimeout.toMillis.asJson,
        "topicChunkSize" -> admin.topicChunkSize.asJson,
        "partitionChunkSize" -> admin.partitionChunkSize.asJson,
        "groupChunkSize" -> admin.groupChunkSize.asJson,
        "parallelism" -> admin.parallelism.asJson,
        "metadataRefreshMs" -> admin.metadataRefresh.toMillis.asJson,
        "capabilityRefreshMs" -> admin.capabilityRefresh.toMillis.asJson
      )
  )

  /** The documented shape of the admin block.
    *
    * Described rather than derived, because `AdminTuning`'s Scala fields are `FiniteDuration`s and its wire
    * fields are whole milliseconds. A derived schema would document a shape the encoder above does not
    * produce, which is worse than no schema: a generated client would be built against it.
    */
  final private case class AdminTuningDocument(
      requestTimeoutMs: Long,
      apiTimeoutMs: Long,
      topicChunkSize: Int,
      partitionChunkSize: Int,
      groupChunkSize: Int,
      parallelism: Int,
      metadataRefreshMs: Long,
      capabilityRefreshMs: Long
  )

  given Schema[AdminTuning] = Schema
    .derived[AdminTuningDocument]
    .as[AdminTuning]
    .description("Admin client timeouts, chunk sizes and refresh intervals, in whole milliseconds")

  private def millis(value: Long): FiniteDuration = value.millis
}
