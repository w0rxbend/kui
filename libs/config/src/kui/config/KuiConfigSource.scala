package kui.config

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

import cats.data.{NonEmptyList, ValidatedNel}
import cats.effect.kernel.Async
import cats.syntax.all.*
import ciris.{ConfigError, ConfigKey, ConfigValue}
import io.circe.Json

import kui.kernel.{Host, Port, PositiveInt, Secret, ServiceId}

/** Loads [[KuiConfig]] from the command line, the environment and YAML files.
  *
  * ==Precedence==
  *
  * Command-line flags beat environment variables, which beat YAML files, which beat the defaults baked into
  * this file. The chain is expressed once, in [[Layers.candidates]], so no field can accidentally get a
  * different order from its neighbour.
  *
  * ==Reporting every problem==
  *
  * A load either produces a `KuiConfig` or produces *every* [[ConfigProblem]] it found. It never stops at the
  * first one and it never returns a half-valid configuration: a gateway that boots with three of its four
  * upstreams configured is harder to diagnose than one that refused to start and said which key was wrong.
  *
  * ==Key naming==
  *
  * Every key has one dotted name (`kui.server.port`) and one derived environment name (`KUI_SERVER_PORT`):
  * uppercase, with `.` and `-` both becoming `_`. Command-line flags use the dotted name, with the `kui.`
  * prefix optional: `--kui.server.port=9999` and `--server.port=9999` are the same flag. `--config <path>`
  * adds a YAML file to the ones passed in by the caller.
  *
  * Repeated sections use a numeric or named segment in the same dotted namespace, so there is only ever one
  * way to spell a key:
  *
  * {{{
  * kui.gateway.services.cluster.url        KUI_GATEWAY_SERVICES_CLUSTER_URL
  * kui.gateway.principalKeys.0.kid         KUI_GATEWAY_PRINCIPALKEYS_0_KID
  * }}}
  */
object KuiConfigSource {

  /** Loads the configuration with the documented precedence.
    *
    * @param args
    *   the process's command-line arguments, unfiltered
    * @param files
    *   YAML files in increasing order of precedence, so a later file overrides an earlier one. Files named by
    *   `--config` flags are appended to this list and therefore win over it.
    * @param policy
    *   the URL policy applied to every URL-shaped key. Production leaves it at [[UrlPolicy.Strict]]; Docker
    *   Compose and the test suites pass [[UrlPolicy.Dev]] because every upstream there is on loopback or a
    *   private network.
    */
  def load[F[_]: Async](
      args: List[String],
      files: List[Path],
      policy: UrlPolicy = UrlPolicy.Strict
  ): F[Either[ConfigErrors, KuiConfig]] =
    Async[F].delay(sys.env).flatMap(environment => loadFrom[F](args, files, environment, policy))

  /** The same load, with the environment supplied rather than read from the process.
    *
    * This is the seam the suites use. Reading `sys.env` inside the loader would make the
    * environment-precedence tests depend on variables being set outside the build, which is both unreliable
    * and impossible to arrange for more than one case per run.
    */
  def loadFrom[F[_]: Async](
      args: List[String],
      files: List[Path],
      env: Map[String, String],
      policy: UrlPolicy = UrlPolicy.Strict
  ): F[Either[ConfigErrors, KuiConfig]] = {
    val arguments = CommandLine.parse(args)
    for {
      documents <- readAll(files ++ arguments.configFiles)
      result <- documents match {
        case Left(problems) => Async[F].pure(problems.invalid[KuiConfig])
        case Right(loaded) =>
          val layers = Layers(arguments.flags, env, loaded)
          decode(layers, policy).flatMap(resolveSecrets(_, env))
      }
    } yield result.toEither.leftMap(ConfigErrors.apply)
  }

  // ---------------------------------------------------------------------------------------------
  // Reading the layers
  // ---------------------------------------------------------------------------------------------

  private type Problems[A] = ValidatedNel[ConfigProblem, A]

  private def readAll[F[_]: Async](
      files: List[Path]
  ): F[Either[NonEmptyList[ConfigProblem], List[Document]]] =
    files.traverse(readOne[F]).map { attempts =>
      attempts.separate match {
        case (Nil, documents) => Right(documents)
        case (first :: rest, _) => Left(NonEmptyList(first, rest))
      }
    }

  private def readOne[F[_]: Async](path: Path): F[Either[ConfigProblem, Document]] =
    Async[F]
      .blocking(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      .attempt
      .map {
        case Left(_) =>
          Left(ConfigProblem("kui", "could not be read", ConfigSourceName.File(path.toString)))
        case Right(text) =>
          io.circe.yaml.parser.parse(text) match {
            case Left(failure) =>
              Left(
                ConfigProblem(
                  "kui",
                  s"is not valid YAML: ${failure.message}",
                  ConfigSourceName.File(path.toString)
                )
              )
            case Right(json) => Right(Document(path.toString, json))
          }
      }

  /** One parsed YAML file. */
  final private case class Document(path: String, json: Json)

  /** The command line, split into the flags it sets and the files it names. */
  final private case class CommandLine(flags: Map[String, String], configFiles: List[Path])

  private object CommandLine {

    /** Accepts `--key=value`, `--key value` and `--config <path>`; ignores anything else, because a process
      * may have arguments that are none of this loader's business.
      */
    def parse(args: List[String]): CommandLine = {
      val (flags, paths) = fold(args, Map.empty, Nil)
      CommandLine(flags, paths.reverse)
    }

    @annotation.tailrec
    private def fold(
        remaining: List[String],
        flags: Map[String, String],
        paths: List[Path]
    ): (Map[String, String], List[Path]) =
      remaining match {
        case Nil => (flags, paths)

        case "--config" :: path :: rest =>
          fold(rest, flags, Path.of(path) :: paths)

        case flag :: rest if flag.startsWith("--") && flag.contains('=') =>
          val (name, value) = flag.drop(2).span(_ != '=')
          fold(rest, flags.updated(canonical(name), value.drop(1)), paths)

        case flag :: value :: rest if flag.startsWith("--") && !value.startsWith("--") =>
          fold(rest, flags.updated(canonical(flag.drop(2)), value), paths)

        case _ :: rest => fold(rest, flags, paths)
      }

    /** `--server.port` and `--kui.server.port` name the same key. */
    private def canonical(name: String): String =
      if name.startsWith("kui.") then name else s"kui.$name"
  }

  /** The three configured layers plus the derived environment names, with the precedence order in one place.
    */
  final private case class Layers(
      cli: Map[String, String],
      env: Map[String, String],
      files: List[Document]
  ) {

    /** Every value supplied for `key`, highest precedence first. */
    def candidates(key: String): List[(ConfigSourceName, String)] = {
      val fromCli = cli.get(key).map(ConfigSourceName.Cli -> _)
      val fromEnv = env.get(Layers.envName(key)).map(ConfigSourceName.Env -> _)
      val fromFiles = files.reverse.flatMap(document =>
        Layers.at(document.json, key).map(ConfigSourceName.File(document.path) -> _)
      )
      fromCli.toList ++ fromEnv.toList ++ fromFiles
    }

    def first(key: String): Option[(ConfigSourceName, String)] = candidates(key).headOption

    /** Keys that exist under `prefix` in any layer, used to discover map and list members whose names the
      * model cannot know in advance (the configured services, the signing keys).
      */
    def childrenOf(prefix: String): Set[String] = {
      val dotted = s"$prefix."
      val fromCli = cli.keySet.filter(_.startsWith(dotted)).map(_.drop(dotted.length).takeWhile(_ != '.'))
      val envPrefix = s"${Layers.envName(prefix)}_"
      val fromEnv = env.keySet
        .filter(_.startsWith(envPrefix))
        .map(_.drop(envPrefix.length))
        // The suffix after the member name is one of the fixed leaf names, so the member name is
        // everything up to the last underscore. That is what lets `SCHEMA_REGISTRY_URL` mean the
        // service `schema-registry` rather than the service `schema` with a key `registry_url`.
        .flatMap(rest => Option.when(rest.contains('_'))(rest.substring(0, rest.lastIndexOf('_'))))
        .map(_.toLowerCase.replace('_', '-'))
      val fromFiles = files.flatMap(document => Layers.membersOf(document.json, prefix)).toSet
      fromCli ++ fromEnv ++ fromFiles
    }

    def documents: List[Document] = files
  }

  private object Layers {

    /** `kui.server.basePath` becomes `KUI_SERVER_BASEPATH`. Dashes become underscores too, so a service id
      * like `schema-registry` has a spellable environment name.
      */
    def envName(key: String): String = key.toUpperCase.replace('.', '_').replace('-', '_')

    /** Reads one dotted path out of a parsed document, rendering the leaf as text.
      *
      * A numeric segment indexes into an array, so `kui.gateway.principalKeys.0.kid` reads the first
      * element's `kid`. Everything is rendered to a string here and decoded later, so that `port: 8080` in
      * YAML and `KUI_SERVER_PORT=8080` in the environment travel the same path.
      */
    def at(json: Json, key: String): Option[String] =
      descend(json, key.split('.').toList).flatMap(scalar)

    def membersOf(json: Json, prefix: String): List[String] =
      descend(json, prefix.split('.').toList).toList.flatMap { node =>
        node.asObject
          .map(_.keys.toList)
          .orElse(node.asArray.map(_.indices.map(_.toString).toList))
          .toList
          .flatten
      }

    private def descend(json: Json, path: List[String]): Option[Json] =
      path.foldLeft(Option(json)) { (current, segment) =>
        current.flatMap { node =>
          segment.toIntOption match {
            case Some(index) => node.asArray.flatMap(_.lift(index))
            case None => node.asObject.flatMap(_(segment))
          }
        }
      }

    /** A YAML leaf as the text a decoder sees. A list of scalars becomes a comma-separated string, which is
      * also how a list is spelled in an environment variable.
      */
    private def scalar(json: Json): Option[String] =
      json.asString
        .orElse(json.asNumber.map(_.toString))
        .orElse(json.asBoolean.map(_.toString))
        .orElse(json.asArray.flatMap(items => items.toList.traverse(scalar).map(_.mkString(","))))
  }

  // ---------------------------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------------------------

  /** One configurable value: its key, what it expects, how to read it and what it is when absent.
    *
    * `secret` is not decoration. It is what makes the redaction guarantee mechanical: a problem reported for
    * a secret field prints `***` in place of the offending value, so no code path can echo a signing key into
    * a startup error.
    */
  final private case class Field[A](
      key: String,
      expectation: String,
      read: String => Either[String, A],
      fallback: Option[A],
      secret: Boolean = false
  )

  private def field[A](
      key: String,
      expectation: String,
      read: String => Either[String, A]
  ): Field[A] = Field(key, expectation, read, None)

  private def field[A](
      key: String,
      expectation: String,
      read: String => Either[String, A],
      default: A
  ): Field[A] = Field(key, expectation, read, Some(default))

  private def readHost(raw: String): Either[String, Host] = Host.from(raw).leftMap(_.message)
  private def readPort(raw: String): Either[String, Port] =
    raw.toIntOption.toRight(s"'$raw' is not a whole number").flatMap(Port.from(_).leftMap(_.message))

  private def readBoolean(raw: String): Either[String, Boolean] =
    raw.toLowerCase match {
      case "true" | "yes" | "on" => Right(true)
      case "false" | "no" | "off" => Right(false)
      case other => Left(s"'$other' is not a boolean")
    }

  private def readPositiveInt(raw: String): Either[String, PositiveInt] =
    raw.toIntOption
      .toRight(s"'$raw' is not a whole number")
      .flatMap(PositiveInt.from(_).leftMap(_.message))

  private def readMillis(raw: String): Either[String, FiniteDuration] =
    raw.toLongOption
      .toRight(s"'$raw' is not a whole number of milliseconds")
      .flatMap(millis =>
        if millis > 0 then Right(FiniteDuration(millis, java.util.concurrent.TimeUnit.MILLISECONDS))
        else Left(s"'$raw' is not a positive number of milliseconds")
      )

  private def readDuration(raw: String): Either[String, FiniteDuration] =
    Try(Duration(raw)).toEither.left
      .map(_ => s"'$raw' is not a duration such as 10s or 500ms")
      .flatMap {
        case finite: FiniteDuration if finite.length > 0 => Right(finite)
        case _ => Left(s"'$raw' is not a positive, finite duration")
      }

  private def readInstant(raw: String): Either[String, Instant] =
    Try(Instant.parse(raw)).toEither.left.map(_ => s"'$raw' is not an RFC 3339 instant")

  private def readNonEmpty(raw: String): Either[String, String] =
    if raw.trim.nonEmpty then Right(raw) else Left("must not be empty")

  private def readLogFormat(raw: String): Either[String, LogFormat] =
    LogFormat.fromWire(raw).toRight(s"'$raw' is neither json nor text")

  private def readUrl(policy: UrlPolicy)(raw: String): Either[String, SafeUrl] =
    SafeUrl.from(raw, policy).leftMap(_.message)

  /** Origins are a list, and `*` is refused: combined with credentials it would let any website read a
    * signed-in user's Kafka data. ADR-019 makes the allow-list explicit for that reason.
    */
  private def readOrigins(raw: String): Either[String, List[String]] = {
    val origins = raw.split(',').toList.map(_.trim).filter(_.nonEmpty)
    if origins.contains("*") then
      Left(
        "must be an explicit list of origins; '*' is refused because it cannot be combined safely with credentials"
      )
    else Right(origins)
  }

  private def readAuthType(raw: String): Either[String, String] =
    if raw.equalsIgnoreCase("disabled") then Right("disabled")
    else
      Left(
        s"'$raw' is not supported yet; M0 ships with authentication disabled and the other " +
          "types arrive with the identity service in M6"
      )

  // ---------------------------------------------------------------------------------------------
  // Decoding
  // ---------------------------------------------------------------------------------------------

  /** Reads one field through its precedence chain and turns a failure into a [[ConfigProblem]].
    *
    * Ciris owns the chain (`or` falls back only when a layer supplies nothing, so a *bad* value in a
    * high-precedence layer is an error rather than something the next layer silently papers over). The
    * problem itself is built here, from the field and the layers, because ciris's `ConfigError` carries only
    * free text and this loader promises a structured key and source.
    */
  private def read[F[_]: Async, A](field: Field[A], layers: Layers): F[Problems[A]] =
    chain(field, layers).attempt[F].map {
      case Right(value) => value.validNel
      case Left(_) => problemFor(field, layers).invalidNel
    }

  private def chain[A](field: Field[A], layers: Layers): ConfigValue[ciris.Effect, A] = {
    val key = ConfigKey(field.key)
    val start: ConfigValue[ciris.Effect, A] =
      field.fallback.fold(ConfigValue.failed[A](ConfigError(s"${field.key} is required")))(
        ConfigValue.default(_)
      )

    layers.candidates(field.key).foldRight(start) { case ((_, raw), next) =>
      val leaf = field.read(raw) match {
        case Right(value) => ConfigValue.loaded(key, value)
        case Left(problem) => ConfigValue.failed[A](ConfigError(problem))
      }
      leaf.or(next)
    }
  }

  private def problemFor[A](field: Field[A], layers: Layers): ConfigProblem =
    layers.first(field.key) match {
      case Some((source, raw)) =>
        val shown = if field.secret then Secret.Redacted else s"'$raw'"
        val detail = field.read(raw).fold(identity, _ => "could not be read")
        ConfigProblem(field.key, s"expected ${field.expectation}; $detail (found $shown)", source)
      case None =>
        ConfigProblem(
          field.key,
          s"is required and was not set anywhere; expected ${field.expectation}",
          ConfigSourceName.Default
        )
    }

  private def readOptional[F[_]: Async, A](field: Field[A], layers: Layers): F[Problems[Option[A]]] =
    if layers.first(field.key).isEmpty then Async[F].pure(none[A].validNel)
    else read[F, A](field, layers).map(_.map(_.some))

  private def decode[F[_]: Async](layers: Layers, policy: UrlPolicy): F[Problems[Draft]] =
    for {
      unknown <- Async[F].pure(UnknownKeys.check(layers.documents))
      server <- decodeServer[F](layers)
      gateway <- decodeGateway[F](layers, policy)
      telemetry <- decodeTelemetry[F](layers, policy)
      auth <- read[F, String](
        field("kui.auth.type", "disabled", readAuthType, "disabled"),
        layers
      )
    } yield (unknown, server, gateway, telemetry, auth).mapN((_, s, g, t, _) => Draft(s, g, t))

  private def decodeServer[F[_]: Async](layers: Layers): F[Problems[ServerConfig]] =
    for {
      host <- read[F, Host](
        field("kui.server.host", "a host name or IP address", readHost, ServerConfig.Default.host),
        layers
      )
      port <- read[F, Port](
        field("kui.server.port", "a port between 1 and 65535", readPort, ServerConfig.Default.port),
        layers
      )
      basePath <- read[F, String](
        field("kui.server.basePath", "a path such as / or /kui", readNonEmpty, ServerConfig.Default.basePath),
        layers
      )
    } yield (host, port, basePath).mapN(ServerConfig.apply)

  private def decodeGateway[F[_]: Async](
      layers: Layers,
      policy: UrlPolicy
  ): F[Problems[GatewayDraft]] =
    for {
      services <- decodeServices[F](layers, policy)
      readiness <- read[F, FiniteDuration](
        field(
          "kui.gateway.readinessIntervalMs",
          "a positive number of milliseconds",
          readMillis,
          GatewayConfig.DefaultReadinessInterval
        ),
        layers
      )
      keys <- decodePrincipalKeys[F](layers)
      cors <- decodeCors[F](layers)
      devInsecureCookies <- read[F, Boolean](
        field(
          "kui.server.devInsecureCookies",
          "true or false",
          readBoolean,
          GatewayConfig.Default.devInsecureCookies
        ),
        layers
      )
    } yield (services, readiness, keys, cors, devInsecureCookies).mapN(GatewayDraft.apply)

  private def decodeServices[F[_]: Async](
      layers: Layers,
      policy: UrlPolicy
  ): F[Problems[Map[ServiceId, UpstreamServiceConfig]]] =
    layers
      .childrenOf("kui.gateway.services")
      .toList
      .sorted
      .traverse(name => decodeService[F](layers, policy, name))
      .map(_.sequence.map(_.toMap))

  private def decodeService[F[_]: Async](
      layers: Layers,
      policy: UrlPolicy,
      name: String
  ): F[Problems[(ServiceId, UpstreamServiceConfig)]] = {
    val prefix = s"kui.gateway.services.$name"
    for {
      url <- read[F, SafeUrl](
        field(s"$prefix.url", "an http or https URL this deployment is allowed to call", readUrl(policy)),
        layers
      )
      timeout <- read[F, FiniteDuration](
        field(
          s"$prefix.timeout",
          "a duration such as 10s",
          readDuration,
          UpstreamServiceConfig.DefaultTimeout
        ),
        layers
      )
      concurrency <- read[F, PositiveInt](
        field(
          s"$prefix.maxConcurrent",
          "a positive whole number",
          readPositiveInt,
          UpstreamServiceConfig.DefaultMaxConcurrent
        ),
        layers
      )
      id = ServiceId
        .from(name)
        .leftMap(error =>
          NonEmptyList.one(
            ConfigProblem(
              prefix,
              error.message,
              layers.first(s"$prefix.url").map(_._1).getOrElse(ConfigSourceName.Default)
            )
          )
        )
        .toValidated
    } yield (id, url, timeout, concurrency).mapN((serviceId, u, t, c) =>
      serviceId -> UpstreamServiceConfig(u, t, c)
    )
  }

  private def decodePrincipalKeys[F[_]: Async](layers: Layers): F[Problems[List[KeyDraft]]] =
    layers
      .childrenOf("kui.gateway.principalKeys")
      .toList
      .flatMap(_.toIntOption)
      .sorted
      .traverse(index => decodePrincipalKey[F](layers, index))
      .map(_.sequence)

  private def decodePrincipalKey[F[_]: Async](layers: Layers, index: Int): F[Problems[KeyDraft]] = {
    val prefix = s"kui.gateway.principalKeys.$index"
    for {
      kid <- read[F, String](field(s"$prefix.kid", "a non-empty key id", readNonEmpty), layers)
      key <- read[F, SecretRef](
        Field(
          s"$prefix.key",
          "a literal secret, env:NAME or file:/path",
          raw => readNonEmpty(raw).map(SecretRef.parse),
          None,
          secret = true
        ),
        layers
      )
      notBefore <- read[F, Instant](
        field(s"$prefix.notBefore", "an RFC 3339 instant", readInstant, Instant.EPOCH),
        layers
      )
    } yield (kid, key, notBefore).mapN(KeyDraft.apply)
  }

  private def decodeCors[F[_]: Async](layers: Layers): F[Problems[CorsConfig]] =
    for {
      enabled <- read[F, Boolean](
        field("kui.gateway.cors.enabled", "true or false", readBoolean, CorsConfig.Default.enabled),
        layers
      )
      origins <- read[F, List[String]](
        field(
          "kui.gateway.cors.origins",
          "a comma-separated list of origins",
          readOrigins,
          CorsConfig.Default.origins
        ),
        layers
      )
    } yield (enabled, origins).mapN(CorsConfig.apply)

  private def decodeTelemetry[F[_]: Async](
      layers: Layers,
      policy: UrlPolicy
  ): F[Problems[TelemetryConfig]] =
    for {
      otlp <- readOptional[F, SafeUrl](
        field("kui.telemetry.otlpEndpoint", "an http or https URL", readUrl(policy)),
        layers
      )
      prometheus <- readOptional[F, Port](
        field("kui.telemetry.prometheusPort", "a port between 1 and 65535", readPort),
        layers
      )
      format <- read[F, LogFormat](
        field("kui.telemetry.logFormat", "json or text", readLogFormat, TelemetryConfig.Default.logFormat),
        layers
      )
      hash <- read[F, Boolean](
        field(
          "kui.telemetry.hashUserIds",
          "true or false",
          readBoolean,
          TelemetryConfig.Default.hashUserIds
        ),
        layers
      )
    } yield (otlp, prometheus, format, hash).mapN(TelemetryConfig.apply)

  // ---------------------------------------------------------------------------------------------
  // Secrets
  // ---------------------------------------------------------------------------------------------

  /** The configuration as it is after decoding but before `env:` and `file:` secret references have been
    * followed. Keeping the two phases apart is what lets the decode stay pure.
    */
  final private case class Draft(
      server: ServerConfig,
      gateway: GatewayDraft,
      telemetry: TelemetryConfig
  )

  final private case class GatewayDraft(
      services: Map[ServiceId, UpstreamServiceConfig],
      readinessInterval: FiniteDuration,
      principalKeys: List[KeyDraft],
      cors: CorsConfig,
      devInsecureCookies: Boolean
  )

  final private case class KeyDraft(kid: String, key: SecretRef, notBefore: Instant)

  private def resolveSecrets[F[_]: Async](
      draft: Problems[Draft],
      env: Map[String, String]
  ): F[Problems[KuiConfig]] =
    draft match {
      case cats.data.Validated.Invalid(problems) => Async[F].pure(cats.data.Validated.Invalid(problems))
      case cats.data.Validated.Valid(value) =>
        value.gateway.principalKeys.zipWithIndex
          .traverse { case (key, index) => resolveKey[F](key, index, env) }
          .map(_.sequence)
          .map(
            _.map(keys =>
              KuiConfig(
                value.server,
                GatewayConfig(
                  value.gateway.services,
                  value.gateway.readinessInterval,
                  keys,
                  value.gateway.cors,
                  value.gateway.devInsecureCookies
                ),
                value.telemetry
              )
            )
          )
    }

  private def resolveKey[F[_]: Async](
      draft: KeyDraft,
      index: Int,
      env: Map[String, String]
  ): F[Problems[PrincipalKeyConfig]] =
    SecretRef.resolve[F](draft.key, env).map {
      case Right(secret) => PrincipalKeyConfig(draft.kid, secret, draft.notBefore).validNel
      case Left(problem) =>
        ConfigProblem(
          s"kui.gateway.principalKeys.$index.key",
          problem,
          ConfigSourceName.Default
        ).invalidNel
    }

  // ---------------------------------------------------------------------------------------------
  // Unknown keys
  // ---------------------------------------------------------------------------------------------

  /** Refuses a YAML key that maps to nothing.
    *
    * Silently ignoring `kui.server.prot: 8080` is the failure mode this exists to prevent: the process
    * starts, listens on the default port, and the operator spends an evening wondering why their setting did
    * nothing. Suggesting a correction for the typo is deliberately out of scope; naming the key is enough to
    * find it.
    */
  private object UnknownKeys {

    /** Path patterns that a key may match. `*` matches any single segment. A pattern ending in `*` as its own
      * trailing segment additionally allows anything below it, which is how the not-yet-modelled sections are
      * tolerated without being read.
      */
    private val Known: List[List[String]] = List(
      List("kui", "server", "host"),
      List("kui", "server", "port"),
      List("kui", "server", "basePath"),
      List("kui", "gateway", "services", "*", "url"),
      List("kui", "gateway", "services", "*", "timeout"),
      List("kui", "gateway", "services", "*", "maxConcurrent"),
      List("kui", "gateway", "readinessIntervalMs"),
      List("kui", "gateway", "principalKeys", "*", "kid"),
      List("kui", "gateway", "principalKeys", "*", "key"),
      List("kui", "gateway", "principalKeys", "*", "notBefore"),
      List("kui", "gateway", "cors", "enabled"),
      List("kui", "gateway", "cors", "origins", "*"),
      List("kui", "auth", "type"),
      List("kui", "telemetry", "otlpEndpoint"),
      List("kui", "telemetry", "prometheusPort"),
      List("kui", "telemetry", "logFormat"),
      List("kui", "telemetry", "hashUserIds"),
      // Declared so the shape exists and a file that already carries them still loads. Nothing is
      // read out of either: `kui.clusters[]` arrives with the cluster registry in M1 and
      // `kui.rbac` with the authorization model in M6.
      List("kui", "clusters", "**"),
      List("kui", "rbac", "**")
    )

    def check(documents: List[Document]): ValidatedNel[ConfigProblem, Unit] =
      documents.flatMap(document => unknownIn(document)) match {
        case Nil => ().validNel
        case first :: rest => cats.data.Validated.Invalid(NonEmptyList(first, rest))
      }

    private def unknownIn(document: Document): List[ConfigProblem] =
      leaves(document.json, Nil)
        .filterNot(path => Known.exists(matches(_, path)))
        .map(path =>
          ConfigProblem(
            path.mkString("."),
            "is not a KUI configuration key",
            ConfigSourceName.File(document.path)
          )
        )

    /** Every path from the document root down to a scalar, an empty object or an empty list.
      *
      * Empty containers stop the walk so that `clusters: []` and `rbac: {}` — the two placeholder sections —
      * are checked as themselves rather than producing no path at all.
      */
    private def leaves(json: Json, path: List[String]): List[List[String]] =
      json.asObject match {
        case Some(obj) if obj.nonEmpty =>
          obj.toList.flatMap { case (name, child) => leaves(child, path :+ name) }
        case _ =>
          json.asArray match {
            case Some(items) if items.nonEmpty =>
              items.toList.zipWithIndex.flatMap { case (child, index) =>
                leaves(child, path :+ index.toString)
              }
            case _ => List(path)
          }
      }

    private def matches(pattern: List[String], path: List[String]): Boolean =
      (pattern, path) match {
        case ("**" :: _, _) => true
        case (Nil, Nil) => true
        case (Nil, _) | (_, Nil) => false
        case (patternHead :: patternTail, pathHead :: pathTail) =>
          val segmentMatches = patternHead == "*" || patternHead == pathHead
          segmentMatches && matches(patternTail, pathTail)
      }
  }
}
