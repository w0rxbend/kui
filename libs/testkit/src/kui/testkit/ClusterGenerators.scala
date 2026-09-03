package kui.testkit

import scala.concurrent.duration.*

import org.scalacheck.Gen

import kui.kernel.cluster.*
import kui.kernel.{ClusterId, Secret}

/** ScalaCheck generators for the `kui.kernel.cluster` connection ADT (KAFKA-001).
  *
  * They live here rather than in one module's test sources because four areas need them — `libs/kafka-auth`'s
  * rendering properties, `libs/config`'s decoder tests, the cluster domain's invariants and the contract
  * module's redaction tests — and four hand-written copies would drift until a test somewhere stopped
  * generating the awkward case it was written for.
  *
  * `genAwkwardSecretString` is the one that matters most: it is the input to KAFKA-002's JAAS injection
  * property, so it deliberately produces the characters that break a naive `String.format` — quotes,
  * backslashes, `=`, `;` and spaces.
  */
object ClusterGenerators {

  private val hostChar: Gen[Char] = Gen.oneOf(('a' to 'z') ++ ('0' to '9'))

  val genHost: Gen[String] =
    Gen.chooseNum(1, 12).flatMap(n => Gen.listOfN(n, hostChar).map(_.mkString))

  val genPort: Gen[Int] = Gen.chooseNum(1, 65535)

  val genHostPort: Gen[String] = for {
    host <- genHost
    port <- genPort
  } yield s"$host:$port"

  /** A bootstrap list with no duplicate entry, because a duplicate is exactly what the smart constructor
    * refuses and a property about accepted values must not generate one.
    */
  val genBootstrapServers: Gen[BootstrapServers] =
    Gen
      .chooseNum(1, 5)
      .flatMap(n => Gen.listOfN(n, genHostPort))
      .map(_.distinct)
      .map(entries => BootstrapServers.unsafe(entries.mkString(",")))

  /** How every generated secret starts.
    *
    * A leak assertion searches a rendered string for the secret it was given, so a generated secret of `"a"`
    * would "leak" into the word `Sasl` and fail a test that found nothing wrong. The marker makes a generated
    * secret impossible to produce by accident, which is what turns a leak assertion from noisy into
    * trustworthy. Everything after it is still adversarial.
    */
  val SecretMarker: String = "kUiS3cr3t"

  /** Passwords built from the characters that break naive quoting. Never empty, never containing a line break
    * — KAFKA-002 refuses those at validation because the JAAS grammar has no escape for one, so generating
    * them here would only produce failures the renderer is right to have.
    */
  val genAwkwardSecretString: Gen[String] = {
    val awkward: Gen[Char] =
      Gen.oneOf('"', '\\', '=', ';', ' ', '\'', '{', '}', 'a', 'Z', '9', 'é', '→')

    Gen.chooseNum(1, 24).flatMap(n => Gen.listOfN(n, awkward).map(SecretMarker + _.mkString))
  }

  val genSecretString: Gen[Secret[String]] = genAwkwardSecretString.map(Secret(_))

  val genUsername: Gen[String] =
    Gen.chooseNum(1, 16).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))

  val genStoreType: Gen[StoreType] = Gen.oneOf(StoreType.Jks, StoreType.Pkcs12, StoreType.Pem)

  val genStoreSource: Gen[StoreSource] = Gen.oneOf(
    genSecretString.map(StoreSource.Inline(_)),
    genHost.map(name => StoreSource.FromPath(s"/etc/kui/$name.p12"))
  )

  val genTrustStoreRef: Gen[TrustStoreRef] = for {
    source <- genStoreSource
    password <- Gen.option(genSecretString)
    storeType <- genStoreType
  } yield TrustStoreRef(source, password, storeType)

  val genKeyStoreRef: Gen[KeyStoreRef] = for {
    source <- genStoreSource
    password <- Gen.option(genSecretString)
    keyPassword <- Gen.option(genSecretString)
    storeType <- genStoreType
  } yield KeyStoreRef(source, password, keyPassword, storeType)

  val genTlsConfig: Gen[TlsConfig] = for {
    truststore <- Gen.option(genTrustStoreRef)
    keystore <- Gen.option(genKeyStoreRef)
    verifyHostname <- Gen.oneOf(true, false)
    protocols <- Gen.option(Gen.const(List("TLSv1.3", "TLSv1.2")))
    ciphers <- Gen.option(Gen.const(List("TLS_AES_128_GCM_SHA256")))
  } yield TlsConfig(truststore, keystore, verifyHostname, protocols, ciphers)

  val genSaslMechanism: Gen[SaslMechanism] = Gen.oneOf(
    for {
      user <- genUsername
      password <- genSecretString
    } yield SaslMechanism.Plain(user, password),
    for {
      user <- genUsername
      password <- genSecretString
    } yield SaslMechanism.ScramSha256(user, password),
    for {
      user <- genUsername
      password <- genSecretString
    } yield SaslMechanism.ScramSha512(user, password),
    for {
      keyTab <- Gen.option(Gen.const("/etc/kui/kui.keytab"))
      useTicketCache <- Gen.oneOf(true, false)
      storeKey <- Gen.oneOf(true, false)
    } yield SaslMechanism.Gssapi("kafka", "kui@EXAMPLE.COM", keyTab, useTicketCache, storeKey),
    for {
      clientId <- genUsername
      clientSecret <- genSecretString
      scope <- Gen.option(Gen.const("kafka:read"))
    } yield SaslMechanism.OAuthBearer("https://idp.example.com/token", clientId, clientSecret, scope),
    for {
      profile <- Gen.option(genUsername)
      roleArn <- Gen.option(Gen.const("arn:aws:iam::123456789012:role/kui"))
      region <- Gen.option(Gen.const("eu-west-1"))
    } yield SaslMechanism.AwsMskIam(profile, roleArn, region),
    for {
      endpoint <- Gen.option(Gen.const("https://login.microsoftonline.com/tenant/oauth2/v2.0/token"))
    } yield SaslMechanism.AzureEntra("kui.servicebus.windows.net", endpoint),
    Gen.const(SaslMechanism.GcpManagedKafka)
  )

  val genSaslProtocol: Gen[SaslProtocol] =
    Gen.oneOf(SaslProtocol.SaslPlaintext, SaslProtocol.SaslSsl)

  val genClusterSecurity: Gen[ClusterSecurity] = Gen.oneOf(
    Gen.const(ClusterSecurity.Plaintext),
    genTlsConfig.map(ClusterSecurity.Ssl(_)),
    for {
      protocol <- genSaslProtocol
      mechanism <- genSaslMechanism
      tls <- Gen.option(genTlsConfig)
    } yield ClusterSecurity.Sasl(protocol, mechanism, tls)
  )

  private val genPropertyKey: Gen[String] = Gen.oneOf(
    "sasl.jaas.config",
    "ssl.truststore.location",
    "ssl.key.password",
    "ssl.keystore.password",
    "client.dns.lookup",
    "metadata.max.age.ms",
    "sasl.oauthbearer.token.endpoint.url",
    "some.vendor.credential",
    "some.vendor.secret"
  )

  val genClientProperties: Gen[ClientProperties] =
    Gen
      .mapOf(for {
        key <- genPropertyKey
        value <- genAwkwardSecretString
        // Suffixed with the key so that no two generated values can be substrings of each other:
        // a leak assertion looks for a secret inside a rendered string, and two colliding values
        // would fail it for a reason that is not a leak.
      } yield key -> s"$value-$key")
      .map(ClientProperties.fromRaw)

  val genAdminTuning: Gen[AdminTuning] = for {
    requestSeconds <- Gen.chooseNum(1, 30)
    extraSeconds <- Gen.chooseNum(0, 60)
    topics <- Gen.chooseNum(1, 500)
    partitions <- Gen.chooseNum(1, 500)
    groups <- Gen.chooseNum(1, 200)
    parallelism <- Gen.chooseNum(1, 16)
  } yield AdminTuning(
    requestTimeout = requestSeconds.seconds,
    apiTimeout = (requestSeconds + extraSeconds).seconds,
    topicChunkSize = topics,
    partitionChunkSize = partitions,
    groupChunkSize = groups,
    parallelism = parallelism,
    metadataRefresh = 30.seconds,
    capabilityRefresh = 1.hour
  )

  val genClusterConnection: Gen[ClusterConnection] = for {
    id <- Generators.validSlug.map(ClusterId.unsafe)
    bootstrap <- genBootstrapServers
    security <- genClusterSecurity
    overrides <- genClientProperties
    tuning <- genAdminTuning
  } yield ClusterConnection(id, bootstrap, security, overrides, tuning)

  /** Every secret string reachable from a connection, for a leak assertion that must not miss one because a
    * new field was added to the ADT and not to the test.
    */
  def secretsOf(connection: ClusterConnection): List[String] =
    secretsOfSecurity(connection.security) ++
      connection.overrides.keys.toList.flatMap { key =>
        connection.overrides.get(key).collect { case PropertyValue.Sensitive(value) =>
          value.value
        }
      }

  def secretsOfSecurity(security: ClusterSecurity): List[String] =
    security.tlsConfig.toList.flatMap(secretsOfTls) ++
      security.saslMechanism.toList.flatMap(secretsOfMechanism)

  def secretsOfTls(tls: TlsConfig): List[String] = {
    val trust = tls.truststore.toList.flatMap { ref =>
      secretsOfSource(ref.source) ++ ref.password.map(_.value)
    }
    val key = tls.keystore.toList.flatMap { ref =>
      secretsOfSource(ref.source) ++ ref.password.map(_.value) ++ ref.keyPassword.map(_.value)
    }
    trust ++ key
  }

  private def secretsOfSource(source: StoreSource): List[String] = source match {
    case StoreSource.Inline(base64) => List(base64.value)
    case StoreSource.FromPath(_) => Nil
  }

  def secretsOfMechanism(mechanism: SaslMechanism): List[String] = mechanism match {
    case SaslMechanism.Plain(_, password) => List(password.value)
    case SaslMechanism.ScramSha256(_, password) => List(password.value)
    case SaslMechanism.ScramSha512(_, password) => List(password.value)
    case SaslMechanism.Gssapi(_, _, _, _, _) => Nil
    case SaslMechanism.OAuthBearer(_, _, clientSecret, _) => List(clientSecret.value)
    case SaslMechanism.AwsMskIam(_, _, _) => Nil
    case SaslMechanism.AzureEntra(_, _) => Nil
    case SaslMechanism.GcpManagedKafka => Nil
  }
}
