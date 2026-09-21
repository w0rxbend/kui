package kui.http.upstream

import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.nio.file.{Files, Path}
import java.security.{KeyStore, PrivateKey, SecureRandom}
import java.util.{Arrays, Base64}
import javax.net.ssl.{
  KeyManager,
  KeyManagerFactory,
  SSLContext,
  TrustManager,
  TrustManagerFactory,
  X509TrustManager
}

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.{NoStackTrace, NonFatal}

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import kui.config.{HttpKeyStore, HttpStoreFormat, HttpStoreMaterial, HttpTlsConfig, HttpTrustStore}
import kui.kernel.Secret

/** Builds one source-owned HTTP transport from validated TLS configuration.
  *
  * A custom truststore is handed to `TrustManagerFactory` by itself: JVM roots are not merged into it. When
  * no truststore is configured the SSL context delegates trust to the JVM defaults. Hostname verification is
  * left to `HttpClient` and cannot be disabled through this API.
  */
object HttpTls {

  /** The only public construction path. The resource owns both sttp's dispatcher and the Java HTTP client. */
  def resource[F[_]: Async](
      config: HttpTlsConfig
  ): Resource[F, WebSocketStreamBackend[F, Fs2Streams[F]]] =
    clientResource[F](config).flatMap(HttpClientFs2Backend.resourceUsingClient[F](_))

  /** A prepared context plus the managers explicitly installed into it.
    *
    * The manager lists are package-visible so unit tests can prove replacement rather than merely prove that
    * `SSLContext.init` accepted a configuration. Empty trust managers mean the JVM default, not no trust.
    */
  final private[upstream] case class Prepared(
      context: SSLContext,
      usesSystemTrust: Boolean,
      trustManagers: List[TrustManager],
      keyManagers: List[KeyManager]
  )

  /** A startup failure whose text contains only a fixed component name.
    *
    * The original exception is deliberately not retained as a cause: file exceptions include configured
    * paths, and provider exceptions are free to include passwords, aliases or store implementation detail.
    */
  final case class InitializationFailure private[HttpTls] (component: String)
      extends RuntimeException(s"HTTP TLS $component could not be initialized")
      with NoStackTrace

  private[upstream] def prepared[F[_]: Async](config: HttpTlsConfig): F[Prepared] =
    if config == HttpTlsConfig.Default then
      guarded[F, Prepared]("default context")(
        Prepared(SSLContext.getDefault, usesSystemTrust = true, Nil, Nil)
      )
    else
      for {
        trustManagers <- config.truststore.traverse(loadTrustManagers[F]).map(_.toList.flatten)
        keyManagers <- config.keystore.traverse(loadKeyManagers[F]).map(_.toList.flatten)
        context <- guarded[F, SSLContext]("context") {
          val value = SSLContext.getInstance("TLS")
          value.init(arrayOrNull(keyManagers), arrayOrNull(trustManagers), SecureRandom())
          value
        }
      } yield Prepared(context, config.truststore.isEmpty, trustManagers, keyManagers)

  /** Kept package-visible so closure is observable without a live TLS server; PQ-10 adds handshakes. */
  private[upstream] def clientResource[F[_]: Async](config: HttpTlsConfig): Resource[F, HttpClient] =
    Resource.eval(prepared[F](config)).flatMap { tls =>
      Resource.make(
        guarded[F, HttpClient]("transport")(
          HttpClient
            .newBuilder()
            .sslContext(tls.context)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        )
      )(client => guarded[F, Unit]("transport shutdown")(client.close()))
    }

  private def loadTrustManagers[F[_]: Async](config: HttpTrustStore): F[List[TrustManager]] =
    loadStore[F](config.material, config.password, config.format, "truststore").flatMap { store =>
      guarded[F, List[TrustManager]]("truststore") {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
        factory.init(store)
        factory.getTrustManagers.toList
      }.flatMap { managers =>
        val hasTrustAnchor = managers
          .collect { case manager: X509TrustManager => manager }
          .exists(_.getAcceptedIssuers.nonEmpty)
        if hasTrustAnchor then Async[F].pure(managers)
        else Async[F].raiseError(InitializationFailure("truststore"))
      }
    }

  private def loadKeyManagers[F[_]: Async](config: HttpKeyStore): F[List[KeyManager]] =
    loadStore[F](config.material, config.password, config.format, "keystore").flatMap { store =>
      guarded[F, Option[List[KeyManager]]]("keystore") {
        val keyPassword = config.keyPassword.value.toCharArray
        try
          Option.when(hasUsablePrivateKey(store, keyPassword)) {
            val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
            factory.init(store, keyPassword)
            factory.getKeyManagers.toList
          }
        finally Arrays.fill(keyPassword, '\u0000')
      }.flatMap {
        case Some(managers) => Async[F].pure(managers)
        case None => Async[F].raiseError(InitializationFailure("keystore"))
      }
    }

  private def loadStore[F[_]: Async](
      material: HttpStoreMaterial,
      password: Secret[String],
      format: HttpStoreFormat,
      component: String
  ): F[KeyStore] =
    guarded[F, KeyStore](component) {
      val chars = password.value.toCharArray
      try {
        val store = KeyStore.getInstance(format.wireName)
        material match {
          case HttpStoreMaterial.Location(path) =>
            Using.resource(Files.newInputStream(Path.of(path)))(store.load(_, chars))
          case HttpStoreMaterial.Inline(base64) =>
            val bytes = Base64.getDecoder.decode(base64.value.filterNot(_.isWhitespace))
            try Using.resource(ByteArrayInputStream(bytes))(store.load(_, chars))
            finally Arrays.fill(bytes, 0.toByte)
        }
        store
      } finally Arrays.fill(chars, '\u0000')
    }

  private def hasUsablePrivateKey(store: KeyStore, password: Array[Char]): Boolean =
    store.aliases().asScala.exists { alias =>
      store.isKeyEntry(alias) &&
      store.getKey(alias, password).isInstanceOf[PrivateKey] &&
      Option(store.getCertificateChain(alias)).exists(_.nonEmpty)
    }

  private def arrayOrNull[A: reflect.ClassTag](values: List[A]): Array[A] =
    Option.when(values.nonEmpty)(values.toArray).orNull

  private def guarded[F[_]: Async, A](component: String)(value: => A): F[A] =
    Async[F].blocking(value).handleErrorWith {
      case failure: InitializationFailure => Async[F].raiseError(failure)
      case NonFatal(_) => Async[F].raiseError(InitializationFailure(component))
      case fatal => Async[F].raiseError(fatal)
    }
}
