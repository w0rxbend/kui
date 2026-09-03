package kui.testkit.kafka

import kui.config.ClusterConfig
import kui.kernel.cluster.*
import kui.kernel.{ClusterId, Secret}

/** The `kui.clusters[]` entry that reaches a running fixture broker.
  *
  * This is the seam that makes the three-security-mode parity suite mean something. The fixture produces a
  * *configured cluster* rather than a hand-built profile, so a suite driving it exercises the real
  * configuration decoder, the real property renderer and the real adapter. A hand-built profile that happened
  * to work would prove only that the fixture and the test agreed with each other.
  */
object ClusterConfigs {

  def forBroker(broker: RunningBroker, id: ClusterId): ClusterConfig =
    ClusterConfig(
      id = id,
      name = id.value,
      bootstrapServers = BootstrapServers.unsafe(broker.bootstrapServers),
      security = securityFor(broker),
      properties = ClientProperties.empty,
      readOnly = false,
      admin = AdminTuning.default
    )

  /** The same cluster as YAML, for a suite that wants to drive the whole loader from a file rather than
    * building the value.
    */
  def yamlFor(broker: RunningBroker, id: ClusterId): String = {
    val security = broker.topology match {
      case KafkaTopology.Plaintext => ""

      case KafkaTopology.SaslScram =>
        val user = broker.credentials.getOrElse(KafkaFixture.scramUser)
        s"""      security:
           |        protocol: SASL_PLAINTEXT
           |        mechanism: SCRAM-SHA-512
           |        username: ${user.username}
           |        password: ${user.password}
           |""".stripMargin

      case KafkaTopology.MutualTls =>
        broker.materials.fold("") { tls =>
          s"""      security:
             |        protocol: SSL
             |        ssl:
             |          verifyHostname: true
             |          truststore:
             |            location: ${tls.truststore.toAbsolutePath}
             |            password: ${tls.truststorePassword}
             |            type: PKCS12
             |          keystore:
             |            location: ${tls.keystore.toAbsolutePath}
             |            password: ${tls.keystorePassword}
             |            type: PKCS12
             |          keyPassword: ${tls.keyPassword}
             |""".stripMargin
        }
    }

    s"""kui:
       |  clusters:
       |    - name: ${id.value}
       |      id: ${id.value}
       |      bootstrapServers: ${broker.bootstrapServers}
       |$security""".stripMargin
  }

  /** ADR-022's typed model for whichever mode the broker is running.
    *
    * Hostname verification stays on for the TLS mode. The broker certificate names `localhost` and
    * `127.0.0.1`, so leaving it on costs nothing and keeps the one setting operators most often disable under
    * test.
    */
  private def securityFor(broker: RunningBroker): ClusterSecurity =
    broker.topology match {
      case KafkaTopology.Plaintext => ClusterSecurity.Plaintext

      case KafkaTopology.SaslScram =>
        val user = broker.credentials.getOrElse(KafkaFixture.scramUser)
        ClusterSecurity.Sasl(
          SaslProtocol.SaslPlaintext,
          SaslMechanism.ScramSha512(user.username, Secret(user.password)),
          None
        )

      case KafkaTopology.MutualTls =>
        ClusterSecurity.Ssl(
          broker.materials.fold(TlsConfig(None, None, verifyHostname = true, None, None)) { tls =>
            TlsConfig(
              truststore = Some(
                TrustStoreRef(
                  StoreSource.FromPath(tls.truststore.toAbsolutePath.toString),
                  Some(Secret(tls.truststorePassword)),
                  StoreType.Pkcs12
                )
              ),
              keystore = Some(
                KeyStoreRef(
                  StoreSource.FromPath(tls.keystore.toAbsolutePath.toString),
                  Some(Secret(tls.keystorePassword)),
                  Some(Secret(tls.keyPassword)),
                  StoreType.Pkcs12
                )
              ),
              verifyHostname = true,
              enabledProtocols = None,
              cipherSuites = None
            )
          }
        )
    }
}
