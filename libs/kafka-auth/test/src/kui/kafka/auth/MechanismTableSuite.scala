package kui.kafka.auth

import scala.io.Source
import scala.util.Using

import kui.kernel.cluster.*
import kui.kernel.{ClusterId, Secret}
import kui.testkit.KuiSuite

/** One golden file per SASL mechanism.
  *
  * Risk R-1 of the milestone plan says that GSSAPI, OAUTHBEARER, AWS MSK IAM, Azure Entra and GCP
  * Managed Kafka cannot be integration-tested locally: no container speaks Kerberos to a test, and
  * none of the three cloud handlers authenticates without a real account. The answer is not to ship
  * them untested. It is to assert the exact property map each one renders against a committed file
  * taken from the vendor's own documentation, so that "KUI supports AWS MSK IAM" means "these are
  * the properties KUI produces, and here they are, in the repository, in a diff".
  *
  * The fixtures carry fake credentials (`golden-user` / `golden-secret`), so the
  * `sasl.jaas.config` line is written out unredacted and a human can read the diff.
  *
  * They are read from the test classpath rather than from a path, because Mill runs a test in a
  * sandbox directory rather than in the module directory, so a relative path finds nothing.
  */
final class MechanismTableSuite extends KuiSuite {

  private val user = "golden-user"
  private val secret: Secret[String] = Secret("golden-secret")

  private def connection(security: ClusterSecurity): ClusterConnection = ClusterConnection(
    id = ClusterId.unsafe("golden"),
    bootstrapServers = BootstrapServers.unsafe("broker-1:9093,broker-2:9093"),
    security = security,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private def sasl(mechanism: SaslMechanism): ClusterSecurity =
    ClusterSecurity.Sasl(SaslProtocol.SaslSsl, mechanism, None)

  /** The rendered map as a `.properties` file: sorted by key, values unredacted. */
  private def rendered(security: ClusterSecurity): String =
    ClientPropertyRenderer
      .render(connection(security), ClientPurpose.Admin, "kui-admin-golden")
      .fold(
        errors => fail(s"render failed: ${errors.toList.map(_.message).mkString("; ")}"),
        properties =>
          properties.unsafeValues.toList.sortBy(_._1).map((k, v) => s"$k=$v").mkString("\n")
      )

  private def readGolden(name: String): String =
    Using
      .resource(Option(getClass.getResourceAsStream(s"/golden/$name")).getOrElse {
        fail(s"libs/kafka-auth/test/resources/golden/$name is missing")
      })(stream => Source.fromInputStream(stream, "UTF-8").mkString)
      .stripLineEnd

  private def assertGolden(name: String, security: ClusterSecurity): Unit =
    assertNoDiff(
      rendered(security),
      readGolden(name),
      clue = s"libs/kafka-auth/test/resources/golden/$name no longer describes what KUI renders"
    )

  /** Every mechanism, and the fixture that records what it renders.
    *
    * The `match` has no default case, so adding a mechanism to the ADT fails to compile here until
    * somebody decides what it renders and commits the evidence.
    */
  private def goldenFileFor(mechanism: SaslMechanism): String = mechanism match {
    case SaslMechanism.Plain(_, _) => "properties-sasl-ssl-plain.properties"
    case SaslMechanism.ScramSha256(_, _) => "properties-sasl-ssl-scram256.properties"
    case SaslMechanism.ScramSha512(_, _) => "properties-sasl-ssl-scram512.properties"
    case SaslMechanism.Gssapi(_, _, _, _, _) => "properties-gssapi.properties"
    case SaslMechanism.OAuthBearer(_, _, _, _) => "properties-oauthbearer.properties"
    case SaslMechanism.AwsMskIam(_, _, _) => "properties-aws-msk-iam.properties"
    case SaslMechanism.AzureEntra(_, _) => "properties-azure-entra.properties"
    case SaslMechanism.GcpManagedKafka => "properties-gcp-managed-kafka.properties"
  }

  private val everyMechanism: List[SaslMechanism] = List(
    SaslMechanism.Plain(user, secret),
    SaslMechanism.ScramSha256(user, secret),
    SaslMechanism.ScramSha512(user, secret),
    SaslMechanism.Gssapi(
      serviceName = "kafka",
      principal = "kui@EXAMPLE.COM",
      keyTab = Some("/etc/kui/kui.keytab"),
      useTicketCache = false,
      storeKey = true
    ),
    SaslMechanism.OAuthBearer(
      tokenEndpoint = "https://idp.example.com/oauth2/token",
      clientId = user,
      clientSecret = secret,
      scope = Some("kafka:read")
    ),
    SaslMechanism.AwsMskIam(
      profile = Some("kui"),
      roleArn = Some("arn:aws:iam::123456789012:role/kui"),
      stsRegion = Some("eu-west-1")
    ),
    SaslMechanism.AzureEntra(namespace = "kui.servicebus.windows.net", tokenEndpoint = None),
    SaslMechanism.GcpManagedKafka
  )

  everyMechanism.foreach { mechanism =>
    test(s"renders ${goldenFileFor(mechanism)}") {
      assertGolden(goldenFileFor(mechanism), sasl(mechanism))
    }
  }

  test("everyMechanismHasAGoldenFile") {
    everyMechanism.foreach(mechanism => assert(readGolden(goldenFileFor(mechanism)).nonEmpty))

    assertEquals(everyMechanism.map(goldenFileFor).distinct.size, everyMechanism.size)
  }

  test("aTlsOnlyClusterRendersTheTlsBlockAndNoSaslBlock") {
    assertGolden(
      "properties-ssl-only.properties",
      ClusterSecurity.Ssl(
        TlsConfig(
          truststore = Some(
            TrustStoreRef(
              StoreSource.FromPath("/etc/kui/truststore.p12"),
              Some(Secret("golden-truststore")),
              StoreType.Pkcs12
            )
          ),
          keystore = Some(
            KeyStoreRef(
              StoreSource.FromPath("/etc/kui/keystore.p12"),
              Some(Secret("golden-keystore")),
              Some(Secret("golden-key")),
              StoreType.Pkcs12
            )
          ),
          verifyHostname = true,
          enabledProtocols = Some(List("TLSv1.3", "TLSv1.2")),
          cipherSuites = None
        )
      )
    )
  }

  test("aPlaintextClusterRendersNeitherBlock") {
    assertGolden("properties-plaintext.properties", ClusterSecurity.Plaintext)
  }
}
