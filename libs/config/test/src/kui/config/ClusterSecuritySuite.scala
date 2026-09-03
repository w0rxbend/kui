package kui.config

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import org.scalacheck.{Gen, Prop}

import kui.kernel.cluster.{ClusterSecurity, SaslMechanism, SaslProtocol}
import kui.testkit.KuiSuite

/** The mechanism table of `docs/operations/configuration.md`, asserted one row at a time.
  *
  * Every row an operator can write down has to decode into the ADT case the renderer expects, and every row
  * with a field missing has to say which field — once, naming that field, and not naming the other seven.
  * An operator filling in a Kerberos cluster should learn about all three missing keys in one restart.
  */
final class ClusterSecuritySuite extends KuiSuite {

  private def load(yaml: String, env: Map[String, String] = Map.empty): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource
      .loadFrom[IO](Nil, List(ConfigFixtures.yaml(yaml)), env, UrlPolicy.Dev)
      .unsafeRunSync()

  /** One cluster whose `security` block is the indented text supplied. */
  private def cluster(security: String): String =
    s"""kui:
       |  clusters:
       |    - name: One
       |      bootstrapServers: broker:9092
       |${if security.isEmpty then "" else s"      security:\n$security"}
       |""".stripMargin

  private def securityOf(yaml: String, env: Map[String, String] = Map.empty): ClusterSecurity =
    load(yaml, env).fold(errors => fail(errors.render), _.clusters.head.security)

  private def problemKeys(yaml: String): List[String] =
    load(yaml) match {
      case Left(errors) => errors.problems.toList.map(_.key).sorted
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  private def saslOf(security: ClusterSecurity): SaslMechanism =
    security match {
      case ClusterSecurity.Sasl(_, mechanism, _) => mechanism
      case other => fail(s"expected a SASL security model, got $other")
    }

  // -------------------------------------------------------------------------------------------
  // Every mechanism decodes
  // -------------------------------------------------------------------------------------------

  test("aClusterWithNoSecurityKeyIsPlaintext") {
    assertEquals(securityOf(cluster("")), ClusterSecurity.Plaintext)
  }

  test("plainDecodes") {
    val security = securityOf(
      cluster("""        protocol: SASL_PLAINTEXT
                |        mechanism: PLAIN
                |        username: kui
                |        password: pw""".stripMargin)
    )

    assertEquals(saslOf(security), SaslMechanism.Plain("kui", kui.kernel.Secret("pw")))
    security match {
      case ClusterSecurity.Sasl(protocol, _, tls) =>
        assertEquals(protocol, SaslProtocol.SaslPlaintext)
        assertEquals(tls, None)
      case other => fail(s"expected SASL, got $other")
    }
  }

  test("scramSha256Decodes") {
    val security = securityOf(
      cluster("""        protocol: SASL_PLAINTEXT
                |        mechanism: SCRAM-SHA-256
                |        username: kui
                |        password: pw""".stripMargin)
    )
    assertEquals(saslOf(security), SaslMechanism.ScramSha256("kui", kui.kernel.Secret("pw")))
  }

  test("scramSha512Decodes") {
    val security = securityOf(
      cluster("""        protocol: SASL_SSL
                |        mechanism: SCRAM-SHA-512
                |        username: kui
                |        password: pw""".stripMargin)
    )
    assertEquals(saslOf(security), SaslMechanism.ScramSha512("kui", kui.kernel.Secret("pw")))
  }

  test("gssapiDecodes") {
    val security = securityOf(
      cluster("""        protocol: SASL_PLAINTEXT
                |        mechanism: GSSAPI
                |        serviceName: kafka
                |        principal: kui@EXAMPLE.COM
                |        keytab: /etc/kui/kui.keytab""".stripMargin)
    )
    assertEquals(
      saslOf(security),
      SaslMechanism.Gssapi("kafka", "kui@EXAMPLE.COM", Some("/etc/kui/kui.keytab"), false, true)
    )
  }

  test("oauthBearerDecodes") {
    val security = securityOf(
      cluster("""        protocol: SASL_SSL
                |        mechanism: OAUTHBEARER
                |        tokenEndpoint: https://login.example.com/token
                |        clientId: kui
                |        clientSecret: env:OAUTH
                |        scope: kafka.read""".stripMargin),
      env = Map("OAUTH" -> "oauth-secret")
    )
    assertEquals(
      saslOf(security),
      SaslMechanism.OAuthBearer(
        "https://login.example.com/token",
        "kui",
        kui.kernel.Secret("oauth-secret"),
        Some("kafka.read")
      )
    )
  }

  test("awsMskIamDecodes") {
    val security = securityOf(
      cluster("""        protocol: SASL_SSL
                |        mechanism: AWS_MSK_IAM
                |        profile: kui-readonly""".stripMargin)
    )
    assertEquals(saslOf(security), SaslMechanism.AwsMskIam(Some("kui-readonly"), None, None))
  }

  test("azureEntraDecodes") {
    val security = securityOf(
      cluster("""        protocol: SASL_SSL
                |        mechanism: AZURE_ENTRA
                |        namespace: kui-namespace""".stripMargin)
    )
    assertEquals(saslOf(security), SaslMechanism.AzureEntra("kui-namespace", None))
  }

  test("gcpDecodes") {
    val security = securityOf(
      cluster("""        protocol: SASL_SSL
                |        mechanism: GCP""".stripMargin)
    )
    assertEquals(saslOf(security), SaslMechanism.GcpManagedKafka)
  }

  test("sslWithoutSaslDecodes") {
    val security = securityOf(
      cluster("""        protocol: SSL
                |        ssl:
                |          verifyHostname: false""".stripMargin)
    )
    security match {
      case ClusterSecurity.Ssl(tls) => assertEquals(tls.verifyHostname, false)
      case other => fail(s"expected SSL, got $other")
    }
  }

  // -------------------------------------------------------------------------------------------
  // Every mechanism says which field is missing
  // -------------------------------------------------------------------------------------------

  test("aMissingPasswordNamesThatKeyAndNoOther") {
    assertEquals(
      problemKeys(
        cluster("""        protocol: SASL_PLAINTEXT
                  |        mechanism: PLAIN
                  |        username: kui""".stripMargin)
      ),
      List("kui.clusters.0.security.password")
    )
  }

  test("everyMissingKerberosKeyIsNamedAtOnce") {
    assertEquals(
      problemKeys(
        cluster("""        protocol: SASL_PLAINTEXT
                  |        mechanism: GSSAPI""".stripMargin)
      ),
      List("kui.clusters.0.security.principal", "kui.clusters.0.security.serviceName")
    )
  }

  test("everyMissingOAuthKeyIsNamedAtOnce") {
    assertEquals(
      problemKeys(
        cluster("""        protocol: SASL_SSL
                  |        mechanism: OAUTHBEARER""".stripMargin)
      ),
      List(
        "kui.clusters.0.security.clientId",
        "kui.clusters.0.security.clientSecret",
        "kui.clusters.0.security.tokenEndpoint"
      )
    )
  }

  test("aMissingAzureNamespaceIsNamed") {
    assertEquals(
      problemKeys(
        cluster("""        protocol: SASL_SSL
                  |        mechanism: AZURE_ENTRA""".stripMargin)
      ),
      List("kui.clusters.0.security.namespace")
    )
  }

  // -------------------------------------------------------------------------------------------
  // The two cross-field rules
  // -------------------------------------------------------------------------------------------

  test("aSaslProtocolWithNoMechanismNamesMechanism") {
    val yaml = cluster("        protocol: SASL_SSL")
    assertEquals(problemKeys(yaml), List("kui.clusters.0.security.mechanism"))
  }

  test("aMechanismUnderANonSaslProtocolIsRefusedRatherThanIgnored") {
    val yaml = cluster("""        protocol: PLAINTEXT
                         |        mechanism: SCRAM-SHA-512
                         |        username: kui
                         |        password: pw""".stripMargin)

    assertEquals(problemKeys(yaml), List("kui.clusters.0.security.mechanism"))
    load(yaml) match {
      case Left(errors) =>
        assert(errors.render.contains("only meaningful for a SASL protocol"), errors.render)
      case Right(_) => fail("expected the load to fail")
    }
  }

  test("anUnknownMechanismNamesTheEightLegalValues") {
    val yaml = cluster("""        protocol: SASL_SSL
                         |        mechanism: scram512""".stripMargin)

    load(yaml) match {
      case Left(errors) =>
        val message = errors.render
        assert(message.contains("SCRAM-SHA-512"), message)
        assert(message.contains("AWS_MSK_IAM"), message)
        assert(message.contains("'SCRAM512'"), message)
      case Right(_) => fail("expected the load to fail")
    }
  }

  // -------------------------------------------------------------------------------------------
  // The loader does not mangle a password on the way in
  // -------------------------------------------------------------------------------------------

  /** Anything an operator might realistically paste into a password field, including the four characters
    * that break a naively assembled JAAS string (Kouncil's `String.format` injection,
    * `research/scala/security-research.md` §3). Rendering it is KAFKA-002's property test; this one asserts
    * the *loader* hands over exactly the bytes it was given, which is where the same bug would be invisible.
    */
  private val awkwardPassword: Gen[String] =
    Gen
      .nonEmptyListOf(
        Gen.oneOf(
          Gen.alphaNumChar,
          Gen.oneOf('"', '\\', ' ', '=', ';', '\'', '$', '{', '}', '\n', '\t', 'é')
        )
      )
      .map(_.mkString)

  property("aPasswordRoundTripsThroughTheEnvironmentUnchanged") {
    Prop.forAll(awkwardPassword) { password =>
      val security = securityOf(
        cluster("""        protocol: SASL_PLAINTEXT
                  |        mechanism: PLAIN
                  |        username: kui
                  |        password: env:KUI_PW""".stripMargin),
        env = Map("KUI_PW" -> password)
      )

      saslOf(security) match {
        case SaslMechanism.Plain(_, secret) => secret.value == password
        case other => fail(s"expected PLAIN, got $other")
      }
    }
  }
}
