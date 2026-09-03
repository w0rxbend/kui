package kui.cluster.api

import cats.data.{Validated, ValidatedNel}
import cats.syntax.all.*

import kui.cluster.contract.dto.{ClusterSecurityWrite, ClusterWriteRequest}
import kui.cluster.domain.{ClusterProfile, ProfileOrigin, ProfileVersion}
import kui.kernel.cluster.*
import kui.kernel.error.{ApplicationError, FieldError, KuiError}
import kui.kernel.{ClusterId, Secret}

/** A write request, turned into a domain profile or into every reason it cannot be one.
  *
  * Accumulating rather than failing fast, because the caller is a person or a form: someone who got three
  * fields wrong should be told about all three in one answer rather than made to discover them one request at
  * a time. That is ADR-013's rule for configuration, applied to a request body for the same reason.
  */
object ClusterWriteMapping {

  /** The security protocols a write may name, and the mechanisms each allows. */
  val Plaintext: String = "PLAINTEXT"
  val Ssl: String = "SSL"
  val SaslPlaintext: String = "SASL_PLAINTEXT"
  val SaslSsl: String = "SASL_SSL"

  /** Builds the profile, or reports every problem at once.
    *
    * @param id
    *   the id from the *path*. A body whose name slugs to a different id is refused rather than treated as a
    *   rename: ADR-031 derives the id from the name, so renaming produces a new id, which is a create plus a
    *   delete and not a `PUT`. Accepting it silently would leave a record whose key and name disagree.
    */
  def profileOf(
      id: ClusterId,
      version: ProfileVersion,
      request: ClusterWriteRequest
  ): Either[KuiError, ClusterProfile] = {
    val checked = (
      bootstrapOf(request.bootstrapServers),
      securityOf(request.security),
      tuningOf(request),
      idMatches(id, request.name)
    ).mapN((bootstrap, security, admin, _) => (bootstrap, security, admin))

    checked.toEither
      .leftMap(problems => ApplicationError.Invalid("the cluster is not valid", problems.toList): KuiError)
      .flatMap { (bootstrap, security, admin) =>
        ClusterProfile
          .from(
            id = id,
            displayName = request.name,
            bootstrap = bootstrap,
            security = security,
            properties = ClientProperties.fromRaw(request.properties),
            admin = admin,
            readOnly = request.readOnly,
            colour = None,
            version = version,
            // Stored, because a write is how a record gets into the store. The registry's precedence rule
            // is stated in terms of this field, and a profile that lied about where it came from would
            // make an operator's edit lose to the configuration file it was meant to override.
            origin = ProfileOrigin.Stored
          )
          .leftMap(error => ApplicationError.Invalid(error.message, error.details): KuiError)
      }
  }

  /** The version a caller stated in `If-Match`, or the reason it could not be read.
    *
    * Quotes are optional and a weak-tag prefix is tolerated, because a proxy between the caller and KUI may
    * add or remove either; anything that is not a number is refused, because guessing which version a caller
    * meant is how a lost update happens.
    */
  def versionOf(ifMatch: String): Either[KuiError, ProfileVersion] = {
    val cleaned = ifMatch.trim.stripPrefix("W/").replace("\"", "")

    cleaned.toLongOption
      .toRight(
        ApplicationError.Invalid(
          "the If-Match header is not a version",
          List(FieldError(Some("If-Match"), List("a quoted version number, or \"0\" to create")))
        ): KuiError
      )
      .flatMap(
        ProfileVersion
          .from(_)
          .leftMap(error => ApplicationError.Invalid(error.message, Nil): KuiError)
      )
  }

  private def idMatches(id: ClusterId, name: String): ValidatedNel[FieldError, Unit] =
    kui.config.ClusterConfig.slug(name) match {
      case Right(slugged) if slugged == id => Validated.validNel(())
      case Right(slugged) =>
        Validated.invalidNel(
          FieldError(
            Some("name"),
            List(
              s"'$name' identifies the cluster '${slugged.value}', not '${id.value}'; " +
                "renaming a cluster creates a new one and removes the old one"
            )
          )
        )
      case Left(problem) => Validated.invalidNel(FieldError(Some("name"), List(problem)))
    }

  private def bootstrapOf(raw: String): ValidatedNel[FieldError, BootstrapServers] =
    BootstrapServers.from(raw) match {
      case Right(value) => Validated.validNel(value)
      case Left(error) => Validated.invalidNel(FieldError(Some("bootstrapServers"), List(error.message)))
    }

  private def tuningOf(request: ClusterWriteRequest): ValidatedNel[FieldError, AdminTuning] = {
    val apiTimeout = scala.concurrent.duration.Duration.fromNanos(request.admin.timeoutMs * 1000000L)

    val tuning = AdminTuning.default.copy(
      apiTimeout = apiTimeout,
      // A caller sets one number: how long a whole admin call may take. The per-round-trip timeout is
      // capped to it rather than left at its default, because a per-request bound larger than the whole
      // call's bound can never be reached - the domain refuses that combination, and it would be refusing
      // a number the caller never wrote.
      requestTimeout = AdminTuning.default.requestTimeout.min(apiTimeout),
      topicChunkSize = request.admin.batchSize,
      parallelism = request.admin.parallelism
    )

    tuning.validate match {
      case Right(valid) => Validated.validNel(valid)
      case Left(problems) =>
        Validated.invalid(problems.map(error => FieldError(Some("admin"), List(error.message))))
    }
  }

  /** The security ADT, from the four strings and two blobs a caller sends.
    *
    * The vocabulary is the one an operator already writes in a configuration file, so a cluster registered
    * through this endpoint and one written into `kui.clusters[]` are described the same way.
    */
  private def securityOf(request: ClusterSecurityWrite): ValidatedNel[FieldError, ClusterSecurity] = {
    val tls = TlsConfig(
      truststore = request.truststore.map(material =>
        TrustStoreRef(StoreSource.Inline(material.base64), material.password, StoreType.Jks)
      ),
      keystore = request.keystore.map(material =>
        KeyStoreRef(StoreSource.Inline(material.base64), material.password, None, StoreType.Jks)
      ),
      verifyHostname = request.verifyHostname,
      enabledProtocols = None,
      cipherSuites = None
    )

    request.protocol.toUpperCase(java.util.Locale.ROOT) match {
      case Plaintext => Validated.validNel(ClusterSecurity.Plaintext)
      case Ssl => Validated.validNel(ClusterSecurity.Ssl(tls))
      case SaslPlaintext =>
        mechanismOf(request).map(ClusterSecurity.Sasl(SaslProtocol.SaslPlaintext, _, None))
      case SaslSsl =>
        mechanismOf(request).map(ClusterSecurity.Sasl(SaslProtocol.SaslSsl, _, Some(tls)))
      case other =>
        Validated.invalidNel(
          FieldError(
            Some("security.protocol"),
            List(s"'$other' is not one of $Plaintext, $Ssl, $SaslPlaintext, $SaslSsl")
          )
        )
    }
  }

  /** The mechanisms a write may set. Deliberately the three KUI integration-tests against a real broker: the
    * vendor mechanisms are configuration-file only until something can exercise them end to end.
    */
  private def mechanismOf(request: ClusterSecurityWrite): ValidatedNel[FieldError, SaslMechanism] = {
    val credentials = (request.username, request.password) match {
      case (Some(user), Some(password)) => Validated.validNel((user, password))
      case _ =>
        Validated.invalidNel[FieldError, (String, Secret[String])](
          FieldError(Some("security.username"), List("a username and a password are required for SASL"))
        )
    }

    (credentials, mechanismName(request)).mapN { case ((user, password), name) =>
      name match {
        case "PLAIN" => SaslMechanism.Plain(user, password)
        case "SCRAM-SHA-256" => SaslMechanism.ScramSha256(user, password)
        case _ => SaslMechanism.ScramSha512(user, password)
      }
    }
  }

  private def mechanismName(request: ClusterSecurityWrite): ValidatedNel[FieldError, String] =
    request.mechanism.map(_.toUpperCase(java.util.Locale.ROOT)) match {
      case Some(name) if name == "PLAIN" || name == "SCRAM-SHA-256" || name == "SCRAM-SHA-512" =>
        Validated.validNel(name)
      case Some(other) =>
        Validated.invalidNel(
          FieldError(
            Some("security.mechanism"),
            List(s"'$other' cannot be set through this endpoint; PLAIN, SCRAM-SHA-256 and SCRAM-SHA-512 can")
          )
        )
      case None =>
        Validated.invalidNel(
          FieldError(Some("security.mechanism"), List("a SASL protocol needs a mechanism"))
        )
    }
}
