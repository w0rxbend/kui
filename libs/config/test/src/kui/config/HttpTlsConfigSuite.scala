package kui.config

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.testkit.KuiSuite

final class HttpTlsConfigSuite extends KuiSuite {

  private def load(
      yaml: String,
      env: Map[String, String]
  ): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource
      .loadFrom[IO](Nil, List(ConfigFixtures.yaml(yaml)), env, UrlPolicy.Dev)
      .unsafeRunSync()

  private def loaded(yaml: String, env: Map[String, String] = Map.empty): KuiConfig =
    load(yaml, env).fold(errors => fail(errors.render), identity)

  private def problems(yaml: String, env: Map[String, String] = Map.empty): List[ConfigProblem] =
    load(yaml, env) match {
      case Left(errors) => errors.problems.toList.sortBy(_.key)
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  private val sourcePrefix: String =
    """kui:
      |  metrics:
      |    sources:
      |      local:
      |        url: https://prometheus:9090
      |        kind: prometheus-api
      |""".stripMargin

  test("an omitted TLS block uses JVM trust and no client certificate") {
    val source = loaded(sourcePrefix).metrics.sources(kui.kernel.ClusterId.unsafe("local"))

    assertEquals(source.tls, HttpTlsConfig.Default)
    assertEquals(source.tls.truststore, None)
    assertEquals(source.tls.keystore, None)
  }

  test("JKS trust and PKCS12 client stores load from location and inline secret material") {
    val source = loaded(
      sourcePrefix +
        """        tls:
          |          truststore:
          |            type: JKS
          |            location: /etc/kui/prometheus-truststore.jks
          |            password: env:TRUSTSTORE_PASSWORD
          |          keystore:
          |            type: PKCS12
          |            inline: env:KEYSTORE_BASE64
          |            password: env:KEYSTORE_PASSWORD
          |            keyPassword: env:KEY_PASSWORD
          |""".stripMargin,
      Map(
        "TRUSTSTORE_PASSWORD" -> "trust-password-canary",
        "KEYSTORE_BASE64" -> "a2V5c3RvcmUtY2FuYXJ5",
        "KEYSTORE_PASSWORD" -> "store-password-canary",
        "KEY_PASSWORD" -> "key-password-canary"
      )
    ).metrics.sources(kui.kernel.ClusterId.unsafe("local"))

    source.tls.truststore match {
      case Some(HttpTrustStore(HttpStoreMaterial.Location(path), password, HttpStoreFormat.Jks)) =>
        assertEquals(path, "/etc/kui/prometheus-truststore.jks")
        assertEquals(password.value, "trust-password-canary")
      case other => fail(s"the truststore did not decode: $other")
    }
    source.tls.keystore match {
      case Some(
            HttpKeyStore(HttpStoreMaterial.Inline(material), password, keyPassword, HttpStoreFormat.Pkcs12)
          ) =>
        assertEquals(material.value, "a2V5c3RvcmUtY2FuYXJ5")
        assertEquals(password.value, "store-password-canary")
        assertEquals(keyPassword.value, "key-password-canary")
      case other => fail(s"the keystore did not decode: $other")
    }

    val rendered = source.tls.toString
    List(
      "trust-password-canary",
      "a2V5c3RvcmUtY2FuYXJ5",
      "store-password-canary",
      "key-password-canary"
    ).foreach(canary => assert(!rendered.contains(canary), rendered))
  }

  test("a store requires exactly one of location and inline") {
    val neither = problems(
      sourcePrefix +
        """        tls:
        |          truststore:
        |            type: JKS
        |            password: secret-canary
        |""".stripMargin
    )
    assertEquals(neither.map(_.key), List("kui.metrics.sources.local.tls.truststore"))
    assert(!neither.exists(_.render.contains("secret-canary")), neither.map(_.render).mkString)

    val both = problems(
      sourcePrefix +
        """        tls:
        |          truststore:
        |            type: JKS
        |            location: /etc/kui/truststore.jks
        |            inline: dHJ1c3Q=
        |            password: secret-canary
        |""".stripMargin
    )
    assertEquals(both.map(_.key), List("kui.metrics.sources.local.tls.truststore"))
    assert(!both.exists(_.render.contains("secret-canary")), both.map(_.render).mkString)
  }

  test("store types and required passwords are explicit") {
    val cases = List(
      """          truststore:
        |            location: /etc/kui/truststore.jks
        |            password: secret-canary
        |""".stripMargin -> "kui.metrics.sources.local.tls.truststore.type",
      """          truststore:
        |            type: JKS
        |            location: /etc/kui/truststore.jks
        |""".stripMargin -> "kui.metrics.sources.local.tls.truststore.password",
      """          keystore:
        |            type: PKCS12
        |            location: /etc/kui/keystore.p12
        |            password: secret-canary
        |""".stripMargin -> "kui.metrics.sources.local.tls.keystore.keyPassword"
    )

    cases.foreach { (block, expectedKey) =>
      val found = problems(sourcePrefix + "        tls:\n" + block)
      assertEquals(found.map(_.key), List(expectedKey))
      assert(!found.exists(_.render.contains("secret-canary")), found.map(_.render).mkString)
    }
  }

  test("PEM and unknown store types are refused") {
    List("PEM", "BKS").foreach { storeType =>
      val found = problems(
        sourcePrefix +
          s"""        tls:
           |          truststore:
           |            type: $storeType
           |            location: /etc/kui/truststore
           |            password: secret-canary
           |""".stripMargin
      )

      assertEquals(found.map(_.key), List("kui.metrics.sources.local.tls.truststore.type"))
      assert(!found.exists(_.render.contains("secret-canary")), found.map(_.render).mkString)
    }
  }

  test("TLS cannot be configured for exposition or JMX sources") {
    List("prometheus", "jmx").foreach { kind =>
      val found = problems(
        s"""kui:
           |  metrics:
           |    sources:
           |      local:
           |        url: https://metrics:9404/metrics
           |        kind: $kind
           |        tls:
           |          truststore:
           |            type: JKS
           |            location: /etc/kui/truststore.jks
           |            password: secret-canary
           |""".stripMargin
      )

      assertEquals(found.map(_.key), List("kui.metrics.sources.local.tls"))
      assert(!found.exists(_.render.contains("secret-canary")), found.map(_.render).mkString)
    }
  }

  test("custom TLS material requires an HTTPS Prometheus source") {
    val found = problems(
      """kui:
        |  metrics:
        |    sources:
        |      local:
        |        url: http://prometheus:9090
        |        kind: prometheus-api
        |        tls:
        |          truststore:
        |            type: PKCS12
        |            inline: dHJ1c3Q=
        |            password: secret-canary
        |""".stripMargin
    )

    assertEquals(found.map(_.key), List("kui.metrics.sources.local.url"))
    assert(!found.exists(_.render.contains("secret-canary")), found.map(_.render).mkString)
  }

  test("hostname-verification bypass and unknown TLS keys are refused") {
    List("verifyHostname: false", "insecureSkipVerify: true", "certificate: ignored").foreach { leaf =>
      val found = problems(sourcePrefix + s"        tls:\n          $leaf\n")
      assertEquals(found.map(_.key), List(s"kui.metrics.sources.local.tls.${leaf.takeWhile(_ != ':')}"))
    }
  }

  test("unresolved TLS secret references fail without disclosing literal material") {
    val found = problems(
      sourcePrefix +
        """        tls:
        |          truststore:
        |            type: PKCS12
        |            inline: inline-material-canary
        |            password: env:MISSING_TRUSTSTORE_PASSWORD
        |""".stripMargin
    )

    assertEquals(found.map(_.key), List("kui.metrics.sources.local.tls.truststore.password"))
    assert(!found.exists(_.render.contains("inline-material-canary")), found.map(_.render).mkString)
  }
}
