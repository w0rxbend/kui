package kui.kernel.cluster

import org.scalacheck.Prop.forAll

import kui.kernel.Secret
import kui.testkit.{ClusterGenerators, KuiSuite, RedactionAssertions}

/** The two wire-name tables, and the promise that nothing here prints a credential.
  *
  * Both tables are written as a `match` with no default case. Under `-Werror` an unmatched enum case
  * is a compile error, so adding a mechanism without deciding its `sasl.mechanism` value fails the
  * build here rather than failing authentication against a production cluster.
  */
final class ClusterSecuritySuite extends KuiSuite {

  private val password: Secret[String] = Secret("hunter2")

  private val everyMechanism: List[SaslMechanism] = List(
    SaslMechanism.Plain("u", password),
    SaslMechanism.ScramSha256("u", password),
    SaslMechanism.ScramSha512("u", password),
    SaslMechanism.Gssapi("kafka", "kui@EXAMPLE.COM", None, useTicketCache = true, storeKey = false),
    SaslMechanism.OAuthBearer("https://idp/token", "client", password, None),
    SaslMechanism.AwsMskIam(None, None, None),
    SaslMechanism.AzureEntra("ns.servicebus.windows.net", None),
    SaslMechanism.GcpManagedKafka
  )

  /** The table. Exhaustive by construction: no default case. */
  private def expectedWireName(mechanism: SaslMechanism): String = mechanism match {
    case SaslMechanism.Plain(_, _) => "PLAIN"
    case SaslMechanism.ScramSha256(_, _) => "SCRAM-SHA-256"
    case SaslMechanism.ScramSha512(_, _) => "SCRAM-SHA-512"
    case SaslMechanism.Gssapi(_, _, _, _, _) => "GSSAPI"
    case SaslMechanism.OAuthBearer(_, _, _, _) => "OAUTHBEARER"
    case SaslMechanism.AwsMskIam(_, _, _) => "AWS_MSK_IAM"
    case SaslMechanism.AzureEntra(_, _) => "OAUTHBEARER"
    case SaslMechanism.GcpManagedKafka => "OAUTHBEARER"
  }

  test("wireNameTable") {
    everyMechanism.foreach(m => assertEquals(m.wireName, expectedWireName(m)))
  }

  test("wireNameTableCoversEveryCase") {
    assertEquals(everyMechanism.map(expectedWireName).size, everyMechanism.size)
    assertEquals(everyMechanism.map(_.wireName).distinct.size, 6)
  }

  test("securityProtocolTable") {
    assertEquals(ClusterSecurity.Plaintext.securityProtocol, "PLAINTEXT")
    assertEquals(ClusterSecurity.Ssl(TlsConfig.default).securityProtocol, "SSL")
    assertEquals(
      ClusterSecurity
        .Sasl(SaslProtocol.SaslPlaintext, SaslMechanism.Plain("u", password), None)
        .securityProtocol,
      "SASL_PLAINTEXT"
    )
    assertEquals(
      ClusterSecurity
        .Sasl(SaslProtocol.SaslSsl, SaslMechanism.Plain("u", password), None)
        .securityProtocol,
      "SASL_SSL"
    )
  }

  test("saslOverTlsWithNoTlsBlockStillHasTlsDefaults") {
    val security =
      ClusterSecurity.Sasl(SaslProtocol.SaslSsl, SaslMechanism.Plain("u", password), None)

    assertEquals(security.tlsConfig, Some(TlsConfig.default))
    assertEquals(ClusterSecurity.Plaintext.tlsConfig, None)
  }

  property("usesTlsAgreesWithSecurityProtocol") {
    forAll(ClusterGenerators.genClusterSecurity) { security =>
      assertEquals(security.usesTls, security.securityProtocol.endsWith("SSL"))
    }
  }

  property("toStringNeverContainsASecret") {
    forAll(ClusterGenerators.genClusterSecurity) { security =>
      val connection = ClusterConnection(
        id = kui.kernel.ClusterId.unsafe("prod"),
        bootstrapServers = BootstrapServers.unsafe("broker:9093"),
        security = security,
        overrides = ClientProperties.empty,
        admin = AdminTuning.default
      )

      val rendered =
        connection.toString + security.toString +
          security.tlsConfig.fold("")(_.toString) +
          security.saslMechanism.fold("")(_.toString)

      ClusterGenerators
        .secretsOfSecurity(security)
        .foreach(secret => RedactionAssertions.assertNoLeak(rendered, secret))
    }
  }

  test("aConnectionRendersItsIdentityAndItsProtocolButNotItsPassword") {
    val connection = ClusterConnection(
      id = kui.kernel.ClusterId.unsafe("prod"),
      bootstrapServers = BootstrapServers.unsafe("broker:9093"),
      security =
        ClusterSecurity.Sasl(SaslProtocol.SaslSsl, SaslMechanism.Plain("u", password), None),
      overrides = ClientProperties.empty,
      admin = AdminTuning.default
    )

    val rendered = connection.toString

    assert(rendered.contains("prod"), rendered)
    assert(rendered.contains("SASL_SSL"), rendered)
    assert(rendered.contains("PLAIN"), rendered)
    RedactionAssertions.assertNoLeak(rendered, "hunter2")
  }
}
