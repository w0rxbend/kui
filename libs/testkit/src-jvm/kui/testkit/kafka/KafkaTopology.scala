package kui.testkit.kafka

/** Which of the three security configurations a broker is started in.
  *
  * These are the three modes M1's headline exit criterion names: the same broker list, the same configs and
  * the same log directories have to come back through KUI's contract client whichever one a cluster uses.
  *
  * There is deliberately no `SaslSsl`. It is the union of [[SaslScram]] and [[MutualTls]] and it renders no
  * client property that neither of those two renders, so a fourth container would cost every suite another
  * twenty seconds and assert nothing new. Adding it later is one case here and one broker configuration in
  * [[KafkaFixture]].
  */
enum KafkaTopology {

  /** No authentication and no encryption. The baseline, and what every suite that is not about security uses.
    */
  case Plaintext

  /** `SASL_PLAINTEXT` with `SCRAM-SHA-512`: authentication without encryption, which is what most secured
    * on-premise clusters run.
    */
  case SaslScram

  /** `SSL` in both directions — the broker presents a certificate and demands one back — with hostname
    * verification left on.
    */
  case MutualTls
}

object KafkaTopology {

  /** All three, for a suite that wants to assert the same thing about each. */
  val all: List[KafkaTopology] = List(Plaintext, SaslScram, MutualTls)

  given CanEqual[KafkaTopology, KafkaTopology] = CanEqual.derived

  extension (topology: KafkaTopology) {

    /** The name a failure message uses. */
    def label: String = topology match {
      case Plaintext => "PLAINTEXT"
      case SaslScram => "SASL_PLAINTEXT/SCRAM-SHA-512"
      case MutualTls => "SSL (mutual)"
    }

    /** The `security.protocol` a client reaching this broker has to use. */
    def securityProtocol: String = topology match {
      case Plaintext => "PLAINTEXT"
      case SaslScram => "SASL_PLAINTEXT"
      case MutualTls => "SSL"
    }
  }
}
