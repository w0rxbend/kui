package kui.testkit.kafka

import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.file.{Files, Path}
import java.security.cert.X509Certificate
import java.security.{KeyPair, KeyPairGenerator, KeyStore, PrivateKey, SecureRandom}
import java.time.{Duration, Instant}
import java.util.Date

import scala.util.Using

import cats.effect.Async
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{
  BasicConstraints,
  ExtendedKeyUsage,
  Extension,
  GeneralName,
  GeneralNames,
  KeyPurposeId,
  KeyUsage
}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** A throwaway certificate authority, generated fresh for every fixture run.
  *
  * Nothing here is committed to the repository, and that is the point. A checked-in keystore has an expiry
  * date, and the build that starts failing on that date fails with an error nobody will connect to a file
  * added years earlier. Generating the material costs a fraction of a second next to the twenty seconds the
  * container takes anyway.
  *
  * What it produces is a small PKI: one CA, a broker certificate whose subject-alternative names cover
  * `localhost` and `127.0.0.1`, and a client certificate. Hostname verification stays **on** in the TLS
  * fixture, so the SAN list is load-bearing rather than decorative — it is the one TLS setting operators most
  * often get wrong, and a fixture that turned verification off would delete the only test of it.
  */
object CertificateAuthority {

  /** The password on every generated store. They live in a temporary directory for the length of one test run
    * and protect nothing; a constant is honest about that, and it keeps failure messages readable.
    */
  val StorePassword: String = "kui-testkit"

  private val KeySize: Int = 2048
  private val Validity: Duration = Duration.ofDays(2)
  private val SignatureAlgorithm: String = "SHA256withRSA"

  /** Writes four PKCS12 stores under `into` and returns what the client and the broker each need.
    *
    * The client's truststore holds the CA certificate only, so a broker presenting anything the CA did not
    * sign is refused; the broker's truststore holds the same, so a client presenting nothing — or something
    * else — is refused too. That symmetry is what makes `ssl.client.auth=required` mean something.
    */
  def materialize[F[_]: Async](into: Path): F[(TlsMaterials, BrokerTlsMaterials)] =
    Async[F].blocking {
      val _ = Files.createDirectories(into)

      val caKeys = generateKeyPair()
      val caCertificate = selfSignedAuthority(caKeys)

      val brokerKeys = generateKeyPair()
      val brokerCertificate =
        signed(caKeys.getPrivate, caCertificate, brokerKeys, "CN=localhost", serverNames)

      val clientKeys = generateKeyPair()
      val clientCertificate =
        signed(caKeys.getPrivate, caCertificate, clientKeys, "CN=kui-client", clientNames)

      val trust = into.resolve("truststore.p12")
      val brokerStore = into.resolve("broker.keystore.p12")
      val clientStore = into.resolve("client.keystore.p12")

      writeTrustStore(trust, caCertificate)
      writeKeyStore(brokerStore, brokerKeys.getPrivate, Array(brokerCertificate, caCertificate), "broker")
      writeKeyStore(clientStore, clientKeys.getPrivate, Array(clientCertificate, caCertificate), "client")

      val client = TlsMaterials(
        truststore = trust,
        truststorePassword = StorePassword,
        keystore = clientStore,
        keystorePassword = StorePassword,
        keyPassword = StorePassword
      )
      val broker = BrokerTlsMaterials(
        keystore = brokerStore,
        truststore = trust,
        storePassword = StorePassword,
        keyPassword = StorePassword
      )
      (client, broker)
    }

  /** The subject and SAN list of the broker certificate, for a failure message.
    *
    * A TLS handshake that fails because the certificate does not name the host the client dialled produces an
    * exception that says almost nothing, and this is the fact that turns it into an answer.
    */
  val brokerSubject: String = "CN=localhost, SAN=[localhost, 127.0.0.1]"

  private def serverNames: GeneralNames =
    GeneralNames(
      Array(
        GeneralName(GeneralName.dNSName, "localhost"),
        GeneralName(GeneralName.iPAddress, "127.0.0.1")
      )
    )

  private def clientNames: GeneralNames =
    GeneralNames(Array(GeneralName(GeneralName.dNSName, "kui-client")))

  private def generateKeyPair(): KeyPair = {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(KeySize, SecureRandom())
    generator.generateKeyPair()
  }

  private def selfSignedAuthority(keys: KeyPair): X509Certificate = {
    val name = X500Name("CN=kui-testkit-ca")
    val builder = JcaX509v3CertificateBuilder(
      name,
      serialNumber(),
      Date.from(notBefore),
      Date.from(notAfter),
      name,
      keys.getPublic
    )
    val _ = builder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
    val _ = builder.addExtension(
      Extension.keyUsage,
      true,
      KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature)
    )
    convert(builder, keys.getPrivate)
  }

  private def signed(
      issuerKey: PrivateKey,
      issuer: X509Certificate,
      subjectKeys: KeyPair,
      subject: String,
      names: GeneralNames
  ): X509Certificate = {
    val builder = JcaX509v3CertificateBuilder(
      X500Name(issuer.getSubjectX500Principal.getName),
      serialNumber(),
      Date.from(notBefore),
      Date.from(notAfter),
      X500Name(subject),
      subjectKeys.getPublic
    )
    val _ = builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
    val _ = builder.addExtension(Extension.subjectAlternativeName, false, names)
    // Both purposes on both certificates: the broker is a server to KUI and a client to itself, and one
    // certificate that works in both directions is one fewer thing for the fixture to get wrong.
    val _ = builder.addExtension(
      Extension.extendedKeyUsage,
      false,
      ExtendedKeyUsage(Array(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth))
    )
    convert(builder, issuerKey)
  }

  private def convert(builder: JcaX509v3CertificateBuilder, signingKey: PrivateKey): X509Certificate = {
    val signer = JcaContentSignerBuilder(SignatureAlgorithm).build(signingKey)
    JcaX509CertificateConverter().getCertificate(builder.build(signer))
  }

  private def notBefore: Instant = Instant.now().minus(Duration.ofMinutes(5))
  private def notAfter: Instant = Instant.now().plus(Validity)

  private def serialNumber(): BigInteger = BigInteger(64, SecureRandom())

  /** `KeyStore.load` wants a `null` stream to mean "start empty", and a bare `null` is a syntax the build
    * refuses. This is the one place in KUI that needs one, and it is named so it stays that way.
    */
  private def noStream: java.io.InputStream = Option.empty[java.io.InputStream].orNull

  private def writeTrustStore(path: Path, authority: X509Certificate): Unit = {
    val store = KeyStore.getInstance("PKCS12")
    store.load(noStream, StorePassword.toCharArray)
    store.setCertificateEntry("ca", authority)
    save(store, path)
  }

  private def writeKeyStore(
      path: Path,
      key: PrivateKey,
      chain: Array[X509Certificate],
      alias: String
  ): Unit = {
    val store = KeyStore.getInstance("PKCS12")
    store.load(noStream, StorePassword.toCharArray)
    store.setKeyEntry(alias, key, StorePassword.toCharArray, chain.map(identity))
    save(store, path)
  }

  private def save(store: KeyStore, path: Path): Unit = {
    val _ = Using.resource(FileOutputStream(path.toFile))(out => store.store(out, StorePassword.toCharArray))
    // The container runs as uid 1000 and reads these through a bind mount, so they have to be
    // world-readable. They are throwaway credentials in a temporary directory.
    val _ = path.toFile.setReadable(true, false)
  }

}
