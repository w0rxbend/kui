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

import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties}
import kui.kernel.{ClusterId, Host, Port, PositiveInt, Secret, ServiceId}

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
                  s"is not valid YAML: ${firstLineOf(failure.message)}",
                  ConfigSourceName.File(path.toString)
                )
              )
            case Right(json) => Right(Document(path.toString, json))
          }
      }

  /** The first line of a parser message, and nothing after it.
    *
    * The YAML parser renders a failure as a sentence naming the line and column, followed by the source line
    * it choked on. That echoed source line is the problem: KUI's promise is that a secret never reaches a log
    * line or an error message, and it keeps that promise with the `Secret` type -- but a parse failure
    * happens before anything is decoded, so nothing is a `Secret` yet and the redaction in `problemFor`
    * cannot apply. An unclosed quote on the line holding a signing key would print the key into `docker logs`
    * for anyone with log access. The first line still says exactly where to look.
    */
  private def firstLineOf(message: String): String =
    message.linesIterator.map(_.trim).find(_.nonEmpty).getOrElse("the document could not be parsed")

  /** One parsed YAML file. */
  final private case class Document(path: String, json: Json)

  /** The command line, split into the flags it sets and the files it names. */
  final private case class CommandLine(flags: Map[String, String], configFiles: List[Path])

  private object CommandLine {

    /** Accepts `--key=value`, `--key value`, `--config <path>` and `--config=<path>`; ignores anything else,
      * because a process may have arguments that are none of this loader's business.
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

        // Before the generic `--key=value` case below, which would otherwise turn `--config=/etc/kui.yaml`
        // into a flag named `kui.config` that nothing reads. Flags are not checked against the recognised
        // key names, so that flag was dropped without a word and the process started on its defaults with
        // the operator's file unread. Every other key accepts both spellings, so `--config=` is the
        // natural thing to type.
        case flag :: rest if flag.startsWith(ConfigFlag) =>
          fold(rest, flags, Path.of(flag.drop(ConfigFlag.length)) :: paths)

        case flag :: rest if flag.startsWith("--") && flag.contains('=') =>
          val (name, value) = flag.drop(2).span(_ != '=')
          fold(rest, flags.updated(canonical(name), value.drop(1)), paths)

        case flag :: value :: rest if flag.startsWith("--") && !value.startsWith("--") =>
          fold(rest, flags.updated(canonical(flag.drop(2)), value), paths)

        case _ :: rest => fold(rest, flags, paths)
      }

    private val ConfigFlag: String = "--config="

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

    /** Numeric member names directly under `prefix`, in ascending order, across all three layers.
      *
      * [[childrenOf]] cannot be used for this. Its environment branch takes everything up to the *last*
      * underscore, which turns `KUI_CLUSTERS_0_SECURITY_PROTOCOL` into the member `0-security` — right for
      * the flat `services.<id>.<leaf>` map it was written for, and wrong for a nested list. So a list gets
      * its own discovery: the first segment after the prefix, required to be a non-negative integer.
      */
    def indicesOf(prefix: String): List[Int] = {
      val dotted = s"$prefix."
      val fromCli = cli.keySet.filter(_.startsWith(dotted)).map(_.drop(dotted.length).takeWhile(_ != '.'))
      val envPrefix = s"${Layers.envName(prefix)}_"
      val fromEnv =
        env.keySet.filter(_.startsWith(envPrefix)).map(_.drop(envPrefix.length).takeWhile(_ != '_'))
      val fromFiles = files.flatMap(document => Layers.membersOf(document.json, prefix)).toSet
      (fromCli ++ fromEnv ++ fromFiles).toList.flatMap(_.toIntOption).filter(_ >= 0).distinct.sorted
    }

    /** Environment variable names under `prefix`, as the dotted keys they would have been.
      *
      * Only [[decodeClusters]] needs this, and only to refuse them: a raw Kafka property name contains dots
      * that the `KUI_*` mapping cannot round-trip, so the free `properties` map is file-only (D-4). Refusing
      * is what turns "my cipher suites setting did nothing" into a startup error.
      */
    def envNamesUnder(prefix: String): List[String] = {
      val envPrefix = s"${Layers.envName(prefix)}_"
      env.keySet.filter(_.startsWith(envPrefix)).toList.sorted
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

    /** Every scalar leaf under `prefix`, as `full.dotted.name -> text`.
      *
      * Used for the raw client-property map, whose key names KUI cannot know in advance and which therefore
      * cannot be read one `Field` at a time.
      */
    def leavesUnder(json: Json, prefix: String): List[(String, String)] =
      descend(json, prefix.split('.').toList).toList
        .flatMap(node => flatten(node, Nil))
        .map((path, value) => path.mkString(".") -> value)

    private def flatten(json: Json, path: List[String]): List[(List[String], String)] =
      json.asObject match {
        case Some(obj) if obj.nonEmpty => obj.toList.flatMap((name, child) => flatten(child, path :+ name))
        case _ => scalar(json).map(path -> _).toList
      }

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
      store <- decodeStore[F](layers)
      clusters <- decodeClusters[F](layers)
      auth <- read[F, String](
        field("kui.auth.type", "disabled", readAuthType, "disabled"),
        layers
      )
    } yield (unknown, server, gateway, telemetry, store, clusters, auth)
      .mapN((_, s, g, t, st, cs, _) => Draft(s, g, t, st, cs))

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

  /** The signing keys, which are a *list*: their children are the list indices.
    *
    * A child that is not a number means the section was written as a map — an indentation slip that turns
    * `- kid: k1` into `k1:` — and every such child used to be dropped by a `flatMap(_.toIntOption)` that said
    * nothing. The result was an empty key list from a file with keys plainly written in it: the cluster
    * service then refused to start with "no principal signing keys are configured" while the operator was
    * looking straight at one, and the gateway started from the same file and signed nothing. Naming the
    * offending child is the whole difference between a five-minute fix and an evening.
    */
  private def decodePrincipalKeys[F[_]: Async](layers: Layers): F[Problems[List[KeyDraft]]] = {
    val prefix = "kui.gateway.principalKeys"
    val (notIndices, indices) =
      layers.childrenOf(prefix).toList.partitionMap(name => name.toIntOption.toRight(name))
    val misshapen = notIndices.sorted.map(name =>
      ConfigProblem(
        s"$prefix.$name",
        s"is not a list entry; '$prefix' is a list of keys, so each entry is written as a '- ' item " +
          "rather than as a named child",
        sourceOfPrincipalKey(layers, name)
      )
    )
    indices.sorted
      .traverse(index => decodePrincipalKey[F](layers, index))
      .map(_.sequence)
      .map(decoded =>
        misshapen match {
          case Nil => decoded
          case first :: rest => decoded *> NonEmptyList(first, rest).invalid
        }
      )
  }

  /** Where the operator wrote the misshapen entry, so the message can point at the right file. */
  private def sourceOfPrincipalKey(layers: Layers, name: String): ConfigSourceName = {
    val prefix = s"kui.gateway.principalKeys.$name"
    List("kid", "key", "notBefore")
      .flatMap(leaf => layers.first(s"$prefix.$leaf").map(_._1))
      .headOption
      .getOrElse(ConfigSourceName.Default)
  }

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
  // The metadata store
  // ---------------------------------------------------------------------------------------------

  /** The `kui.store.*` slice (ADR-042).
    *
    * `kui.store.kafka.bootstrapServers` being present is the on/off switch for the Kafka store; absent means
    * the file adapter, and there is no third state. Everything else is a scalar with a default, plus four
    * cross-field rules that no single field can check on its own and which are therefore checked here, each
    * producing its own problem so that an operator sees all of them at once.
    */
  private def decodeStore[F[_]: Async](layers: Layers): F[Problems[StoreDraft]] =
    for {
      prefix <- read[F, String](
        field(
          "kui.store.topicPrefix",
          s"a prefix matching ${StoreConfig.TopicPrefixPattern}",
          readTopicPrefix,
          StoreConfig.DefaultTopicPrefix
        ),
        layers
      )
      replication <- read[F, Short](
        field(
          "kui.store.replicationFactor",
          "a whole number between 1 and 32767",
          readReplicationFactor,
          StoreConfig.DefaultReplicationFactor
        ),
        layers
      )
      minIsr <- read[F, PositiveInt](
        field(
          "kui.store.minInSyncReplicas",
          "a positive whole number",
          readPositiveInt,
          StoreConfig.DefaultMinInSyncReplicas
        ),
        layers
      )
      maxFile <- read[F, Long](
        field(
          "kui.store.maxFileBytes",
          s"a size between ${StoreConfig.MinFileBytes} and ${StoreConfig.MaxFileBytes} bytes",
          readBounded(StoreConfig.MinFileBytes, StoreConfig.MaxFileBytes),
          StoreConfig.DefaultMaxFileBytes
        ),
        layers
      )
      replay <- read[F, FiniteDuration](
        field(
          "kui.store.replayTimeout",
          s"a duration between ${StoreConfig.MinReplayTimeout} and ${StoreConfig.MaxReplayTimeout}",
          readBoundedDuration(StoreConfig.MinReplayTimeout, StoreConfig.MaxReplayTimeout),
          StoreConfig.DefaultReplayTimeout
        ),
        layers
      )
      write <- read[F, FiniteDuration](
        field(
          "kui.store.writeTimeout",
          s"a duration between ${StoreConfig.MinWriteTimeout} and ${StoreConfig.MaxWriteTimeout}",
          readBoundedDuration(StoreConfig.MinWriteTimeout, StoreConfig.MaxWriteTimeout),
          StoreConfig.DefaultWriteTimeout
        ),
        layers
      )
      dir <- readOptional[F, Path](
        field("kui.store.dir", "a directory path", raw => readNonEmpty(raw).map(Path.of(_))),
        layers
      )
      kafka <- decodeStoreKafka[F](layers)
      encryption = decodeStoreEncryption(layers)
      fields = (prefix, replication, minIsr, maxFile, replay, write, dir, kafka, encryption)
        .mapN(StoreDraft.apply)
    } yield (fields, checkStoreRules(layers)).mapN((draft, _) => draft)

  private def readTopicPrefix(raw: String): Either[String, String] =
    if raw.matches(StoreConfig.TopicPrefixPattern) then Right(raw)
    else Left(s"'$raw' does not match ${StoreConfig.TopicPrefixPattern}")

  private def readReplicationFactor(raw: String): Either[String, Short] =
    raw.toIntOption.toRight(s"'$raw' is not a whole number").flatMap { value =>
      if value >= 1 && value <= Short.MaxValue then Right(value.toShort)
      else Left(s"$value is outside 1..${Short.MaxValue}")
    }

  private def readBounded(min: Long, max: Long)(raw: String): Either[String, Long] =
    raw.toLongOption.toRight(s"'$raw' is not a whole number").flatMap { value =>
      if value >= min && value <= max then Right(value) else Left(s"$value is outside $min..$max")
    }

  private def readBoundedDuration(min: FiniteDuration, max: FiniteDuration)(
      raw: String
  ): Either[String, FiniteDuration] =
    Try(Duration(raw)).toEither.left
      .map(_ => s"'$raw' is not a duration such as 30s or 2m")
      .flatMap {
        case finite: FiniteDuration if finite >= min && finite <= max => Right(finite)
        case other => Left(s"$other is outside $min..$max")
      }

  /** The store cluster's connection, or `None` when no bootstrap servers are configured. */
  private def decodeStoreKafka[F[_]: Async](layers: Layers): F[Problems[Option[StoreKafkaDraft]]] = {
    val key = "kui.store.kafka.bootstrapServers"
    if layers.first(key).isEmpty then Async[F].pure(none[StoreKafkaDraft].validNel)
    else
      read[F, BootstrapServers](
        field(key, "a comma-separated list of host:port entries", readBootstrapServers),
        layers
      ).map { servers =>
        val security = ClusterSecurityConfig.decode("kui.store.kafka.security", layers.first)
        val properties = propertiesUnder(layers, "kui.store.kafka.properties")
        (servers, security).mapN((s, sec) => Some(StoreKafkaDraft(s, sec, properties)))
      }
  }

  private def readBootstrapServers(raw: String): Either[String, BootstrapServers] =
    BootstrapServers.from(raw).leftMap(_.message)

  /** Raw client properties, read out of the file layers by name. They are not settable from the environment:
    * a Kafka property name contains dots that the `KUI_*` mapping cannot round-trip.
    */
  private def propertiesUnder(layers: Layers, prefix: String): Map[String, String] =
    layers.documents.flatMap(document => Layers.leavesUnder(document.json, prefix)).toMap

  /** `encryptionKey` is one key with the id `k1`; `encryptionKeys` is `id:base64,id:base64`. Setting both is
    * an error rather than a merge: the shorthand exists for the common case, not as an alias.
    */
  private def decodeStoreEncryption(layers: Layers): Problems[Option[StoreEncryptionDraft]] = {
    val single = layers.first("kui.store.encryptionKey")
    val many = layers.first("kui.store.encryptionKeys")
    val activeId = layers.first("kui.store.encryptionKeyId").map(_._2.trim).filter(_.nonEmpty)
    (single, many) match {
      case (Some(_), Some((source, _))) =>
        ConfigProblem(
          "kui.store.encryptionKeys",
          "cannot be set together with kui.store.encryptionKey; use encryptionKey for one key and " +
            "encryptionKeys for a rotation",
          source
        ).invalidNel

      case (Some((_, raw)), None) =>
        val id = activeId.getOrElse(StoreConfig.DefaultKeyId)
        Some(StoreEncryptionDraft(Map(id -> SecretRef.parse(raw)), id)).validNel

      case (None, Some((source, raw))) =>
        parseKeyList(raw, source).andThen { keys =>
          activeId match {
            case None =>
              ConfigProblem(
                "kui.store.encryptionKeyId",
                s"is required with kui.store.encryptionKeys; it names which of ${keys.keys.toList.sorted
                    .mkString(", ")} new writes use",
                ConfigSourceName.Default
              ).invalidNel
            case Some(id) if keys.contains(id) => Some(StoreEncryptionDraft(keys, id)).validNel
            case Some(id) =>
              ConfigProblem(
                "kui.store.encryptionKeyId",
                s"'$id' is not among the configured key ids (${keys.keys.toList.sorted.mkString(", ")})",
                source
              ).invalidNel
          }
        }

      case (None, None) => none[StoreEncryptionDraft].validNel
    }
  }

  /** `id:base64,id:base64`. The material is never echoed, whatever went wrong. */
  private def parseKeyList(raw: String, source: ConfigSourceName): Problems[Map[String, SecretRef]] = {
    val entries = raw.split(',').toList.map(_.trim).filter(_.nonEmpty)
    val parsed = entries.map(entry => entry.span(_ != ':'))
    val malformed = parsed.exists((id, rest) => id.isEmpty || rest.length <= 1)
    if entries.isEmpty || malformed then
      ConfigProblem(
        "kui.store.encryptionKeys",
        "expected 'id:base64,id:base64'; one entry is missing its id or its material",
        source
      ).invalidNel
    else parsed.map((id, rest) => id -> SecretRef.parse(rest.drop(1))).toMap.validNel
  }

  /** The rules no single field can check.
    *
    * They read the layers directly rather than the decoded draft, and that is the point: a draft only exists
    * when *every* field decoded, so a cross-field rule expressed over the draft would go unreported whenever
    * some unrelated field was also wrong. An operator fixing one key per restart is precisely what the
    * accumulate-everything discipline exists to prevent. A field that is itself invalid already has its own
    * problem, so the rule that would have used it stays quiet rather than reporting the same mistake twice.
    */
  private def checkStoreRules(layers: Layers): Problems[Unit] = {
    val replication = layers.first("kui.store.replicationFactor").flatMap(v => v._2.toIntOption)
    val minIsr = layers.first("kui.store.minInSyncReplicas").flatMap(v => v._2.toIntOption)

    val isrRule = (replication.orElse(Some(StoreConfig.DefaultReplicationFactor.toInt)), minIsr) match {
      case (Some(factor), Some(isr)) if isr > factor =>
        ConfigProblem(
          "kui.store.minInSyncReplicas",
          s"expected <= kui.store.replicationFactor ($factor), got $isr",
          layers.first("kui.store.minInSyncReplicas").map(_._1).getOrElse(ConfigSourceName.Default)
        ).invalidNel
      case _ => ().validNel
    }

    val keyRule =
      if layers.first("kui.store.kafka.bootstrapServers").isDefined &&
        layers.first("kui.store.encryptionKey").isEmpty &&
        layers.first("kui.store.encryptionKeys").isEmpty
      then
        ConfigProblem(
          "kui.store.encryptionKey",
          "a Kafka metadata store requires an encryption key; generate one with `openssl rand -base64 32` " +
            "and set KUI_STORE_ENCRYPTION_KEY (see docs/operations/metadata-store.md §4.2)",
          ConfigSourceName.Default
        ).invalidNel
      else ().validNel

    (isrRule, keyRule).mapN((_, _) => ())
  }

  final private case class StoreDraft(
      topicPrefix: String,
      replicationFactor: Short,
      minInSyncReplicas: PositiveInt,
      maxFileBytes: Long,
      replayTimeout: FiniteDuration,
      writeTimeout: FiniteDuration,
      dir: Option[Path],
      kafka: Option[StoreKafkaDraft],
      encryption: Option[StoreEncryptionDraft]
  )

  final private case class StoreKafkaDraft(
      bootstrapServers: BootstrapServers,
      security: ClusterSecurityConfig.ClusterSecurityDraft,
      properties: Map[String, String]
  )

  final private case class StoreEncryptionDraft(keys: Map[String, SecretRef], activeKeyId: String)

  private def resolveStore[F[_]: Async](
      draft: StoreDraft,
      env: Map[String, String]
  ): F[Problems[StoreConfig]] =
    for {
      kafka <- draft.kafka.traverse(k =>
        ClusterSecurityConfig
          .resolve[F](k.security, env)
          .map(_.map(security => StoreKafkaConfig(k.bootstrapServers, security, k.properties)))
      )
      encryption <- draft.encryption.traverse(resolveEncryption[F](_, env))
    } yield (kafka.sequence, encryption.sequence).mapN((k, e) =>
      StoreConfig(
        draft.topicPrefix,
        draft.replicationFactor,
        draft.minInSyncReplicas,
        draft.maxFileBytes,
        draft.replayTimeout,
        draft.writeTimeout,
        draft.dir,
        k,
        e
      )
    )

  private def resolveEncryption[F[_]: Async](
      draft: StoreEncryptionDraft,
      env: Map[String, String]
  ): F[Problems[StoreEncryptionConfig]] =
    draft.keys.toList
      .sortBy(_._1)
      .traverse { (id, ref) =>
        SecretRef.resolve[F](ref, env).map {
          case Right(secret) => (id -> secret).validNel
          case Left(problem) =>
            ConfigProblem("kui.store.encryptionKey", problem, ConfigSourceName.Default).invalidNel
        }
      }
      .map(_.sequence.map(pairs => StoreEncryptionConfig(pairs.toMap, draft.activeKeyId)))

  // ---------------------------------------------------------------------------------------------
  // The managed clusters
  // ---------------------------------------------------------------------------------------------

  /** The `kui.clusters[]` slice: the static base of the cluster registry (ADR-022, ADR-031).
    *
    * Nothing here opens a socket. Validation is syntactic, because reachability is not knowable at load time
    * and because one dead broker must never stop KUI from starting (M1 DEVPLAN §10, D4).
    *
    * Everything that is wrong is reported: every bad field of every cluster, plus the three cross-cluster
    * rules — a gap in the index, two clusters that resolve to the same id, and a raw property set from the
    * environment — so that an operator fixes their file once rather than once per restart.
    */
  private def decodeClusters[F[_]: Async](layers: Layers): F[Problems[List[ClusterConfig]]] = {
    val indices = layers.indicesOf(ClustersPrefix)
    indices
      .traverse(index => decodeCluster[F](layers, index).map(_.map(index -> _)))
      .map(_.sequence)
      .map { drafts =>
        (
          drafts.andThen(rejectDuplicateIds),
          denseIndex(indices),
          rejectEnvironmentProperties(layers, indices)
        ).mapN((list, _, _) => list)
      }
  }

  private val ClustersPrefix: String = "kui.clusters"

  /** D-3: the index must be dense and start at zero.
    *
    * `kui.clusters.0` and `kui.clusters.2` with no `1` almost always means a deleted entry or a typo in an
    * environment variable name. Silently renumbering would hide both.
    */
  private def denseIndex(indices: List[Int]): Problems[Unit] =
    indices.zipWithIndex.collectFirst {
      case (configured, expected) if configured != expected =>
        val previous =
          if expected == 0 then "the list starts at 0" else s"$configured follows ${expected - 1}"
        ConfigProblem(
          s"$ClustersPrefix.$configured",
          s"expected clusters to be numbered from 0 with no gaps; $previous",
          ConfigSourceName.Default
        )
    } match {
      case None => ().validNel
      case Some(problem) => problem.invalidNel
    }

  /** D-4: `properties` is file-only, and an environment variable under it says so rather than being ignored.
    */
  private def rejectEnvironmentProperties(layers: Layers, indices: List[Int]): Problems[Unit] = {
    val offenders = indices.flatMap(index => layers.envNamesUnder(s"$ClustersPrefix.$index.properties"))
    offenders match {
      case Nil => ().validNel
      case names =>
        ConfigProblem(
          s"$ClustersPrefix.<n>.properties",
          s"cannot be set from the environment (${names.mkString(", ")}); a Kafka property name contains " +
            "dots that the KUI_* mapping cannot round-trip, so raw properties are read from a YAML file " +
            "only. A secret inside properties still uses env:NAME, which travels through the environment " +
            "as a value rather than as a key",
          ConfigSourceName.Env
        ).invalidNel
    }
  }

  /** Two clusters that resolve to the same id, named by both of their configured names.
    *
    * The id is what every URL, cache key and RBAC rule is written against, so a collision would silently make
    * one of the two clusters unreachable. Naming both is what tells the operator which one to give an
    * explicit `id`.
    */
  private def rejectDuplicateIds(clusters: List[(Int, ClusterConfig)]): Problems[List[ClusterConfig]] =
    clusters
      .groupBy((_, cluster) => cluster.id)
      .toList
      .sortBy((id, _) => id.value)
      .collect {
        case (id, clashing) if clashing.sizeIs > 1 =>
          ConfigProblem(
            s"$ClustersPrefix.${clashing.map((index, _) => index).min}.id",
            s"'${id.value}' is used by more than one cluster " +
              s"(${clashing.map((_, cluster) => s"'${cluster.name}'").mkString(", ")}); " +
              "set kui.clusters.<n>.id explicitly on all but one of them",
            ConfigSourceName.Default
          )
      } match {
      case Nil => clusters.map((_, cluster) => cluster).validNel
      case first :: rest => cats.data.Validated.Invalid(NonEmptyList(first, rest))
    }

  /** One cluster, with its `env:` and `file:` secret references already followed.
    *
    * Resolving here rather than in [[resolveSecrets]] is deliberate. That second phase runs only when the
    * *whole* decode succeeded, so a missing `KUI_PROD_PASSWORD` on cluster 1 would be hidden by an unrelated
    * typo on cluster 0 and only appear on the next restart — which is exactly the one-problem-per-restart
    * loop the accumulate-everything discipline exists to prevent. Reading a secret is already an effect here,
    * so nothing is lost by doing it in place.
    */
  private def decodeCluster[F[_]: Async](layers: Layers, index: Int): F[Problems[ClusterConfig]] = {
    val prefix = s"$ClustersPrefix.$index"
    for {
      name <- read[F, String](
        field(
          s"$prefix.name",
          s"a display name of 1 to ${ClusterConfig.MaxNameLength} characters",
          readClusterName
        ),
        layers
      )
      servers <- read[F, BootstrapServers](
        field(
          s"$prefix.bootstrapServers",
          "a comma-separated list of host:port entries",
          readBootstrapServers
        ),
        layers
      )
      readOnly <- read[F, Boolean](field(s"$prefix.readOnly", "true or false", readBoolean, false), layers)
      security <- ClusterSecurityConfig
        .decode(s"$prefix.security", layers.first)
        .traverse(draft => ClusterSecurityConfig.resolve[F](draft, layers.env))
        .map(_.andThen(identity))
      properties = ClientProperties.fromRaw(propertiesUnder(layers, s"$prefix.properties"))
    } yield (name, servers, readOnly, security).tupled.andThen { (clusterName, bootstrap, readonly, sec) =>
      // The id is derived last, and only from a name that decoded: deriving it from a name that did not
      // would report the same bad name twice, once under `.name` and once under `.id`.
      clusterId(layers, prefix, clusterName).map { id =>
        ClusterConfig(
          id = id,
          name = clusterName,
          bootstrapServers = bootstrap,
          security = sec,
          properties = properties,
          readOnly = readonly,
          // CFGOP-002 decodes `kui.clusters.<n>.admin` and replaces this default.
          admin = AdminTuning.default
        )
      }
    }
  }

  /** D-1: an explicit `id` wins, and the default is the slug of the name.
    *
    * ADR-031 derives the id from the name, and that is right as a default and wrong as an absolute: an
    * operator who fixes a typo in a display name would otherwise silently break every bookmark and every RBAC
    * entry that named the old slug.
    */
  private def clusterId(layers: Layers, prefix: String, name: String): Problems[ClusterId] =
    layers.first(s"$prefix.id") match {
      case Some((source, raw)) =>
        ClusterId.from(raw.trim) match {
          case Right(id) => id.validNel
          case Left(error) =>
            ConfigProblem(s"$prefix.id", s"expected ${error.message} (found '$raw')", source).invalidNel
        }
      case None =>
        ClusterConfig.slug(name) match {
          case Right(id) => id.validNel
          case Left(problem) => ConfigProblem(s"$prefix.id", problem, ConfigSourceName.Default).invalidNel
        }
    }

  private def readClusterName(raw: String): Either[String, String] = {
    val trimmed = raw.trim
    if trimmed.isEmpty then Left("must not be empty")
    else if trimmed.length > ClusterConfig.MaxNameLength then
      Left(s"is ${trimmed.length} characters long, and the limit is ${ClusterConfig.MaxNameLength}")
    else Right(trimmed)
  }

  // ---------------------------------------------------------------------------------------------
  // Secrets
  // ---------------------------------------------------------------------------------------------

  /** The configuration as it is after decoding but before `env:` and `file:` secret references have been
    * followed. Keeping the two phases apart is what lets the decode stay pure.
    */
  final private case class Draft(
      server: ServerConfig,
      gateway: GatewayDraft,
      telemetry: TelemetryConfig,
      store: StoreDraft,
      clusters: List[ClusterConfig]
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
        for {
          keys <- value.gateway.principalKeys.zipWithIndex
            .traverse { case (key, index) => resolveKey[F](key, index, env) }
            .map(_.sequence)
          store <- resolveStore[F](value.store, env)
        } yield (keys, store).mapN((principalKeys, storeConfig) =>
          KuiConfig(
            value.server,
            GatewayConfig(
              value.gateway.services,
              value.gateway.readinessInterval,
              principalKeys,
              value.gateway.cors,
              value.gateway.devInsecureCookies
            ),
            value.telemetry,
            storeConfig,
            value.clusters
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
      List("kui", "server", "devInsecureCookies"),
      List("kui", "gateway", "services", "*", "url"),
      List("kui", "gateway", "services", "*", "timeout"),
      List("kui", "gateway", "services", "*", "maxConcurrent"),
      List("kui", "gateway", "readinessIntervalMs"),
      List("kui", "gateway", "principalKeys", "*", "kid"),
      List("kui", "gateway", "principalKeys", "*", "key"),
      List("kui", "gateway", "principalKeys", "*", "notBefore"),
      List("kui", "gateway", "cors", "enabled"),
      List("kui", "gateway", "cors", "origins"),
      List("kui", "gateway", "cors", "origins", "*"),
      List("kui", "auth", "type"),
      List("kui", "telemetry", "otlpEndpoint"),
      List("kui", "telemetry", "prometheusPort"),
      List("kui", "telemetry", "logFormat"),
      List("kui", "telemetry", "hashUserIds"),
      // Declared so the shape exists and a file that already carries them still loads. Nothing is
      // read out of either: `kui.clusters[]` arrives with the cluster registry in M1 and
      // `kui.rbac` with the authorization model in M6.
      List("kui", "store", "topicPrefix"),
      List("kui", "store", "replicationFactor"),
      List("kui", "store", "minInSyncReplicas"),
      List("kui", "store", "maxFileBytes"),
      List("kui", "store", "replayTimeout"),
      List("kui", "store", "writeTimeout"),
      List("kui", "store", "dir"),
      List("kui", "store", "encryptionKey"),
      List("kui", "store", "encryptionKeys"),
      List("kui", "store", "encryptionKeyId"),
      List("kui", "store", "kafka", "bootstrapServers"),
      List("kui", "store", "kafka", "bootstrapServers", "*"),
      // `properties` is deliberately open: its whole purpose is to carry Kafka client properties KUI
      // does not model, so it cannot have a list of legal names.
      List("kui", "store", "kafka", "properties", "**"),
      List("kui", "clusters", "*", "name"),
      List("kui", "clusters", "*", "id"),
      List("kui", "clusters", "*", "bootstrapServers"),
      List("kui", "clusters", "*", "bootstrapServers", "*"),
      List("kui", "clusters", "*", "readOnly"),
      // Open for the same reason as the store's: this map's whole purpose is to carry Kafka client
      // properties KUI does not model, so it cannot have a list of legal names.
      List("kui", "clusters", "*", "properties", "**"),
      List("kui", "rbac", "**")
    ) ++ ClusterSecurityConfig
      .keysUnder("kui.store.kafka.security")
      .map(_.split('.').toList)
      ++ ClusterSecurityConfig
        .keysUnder("kui.clusters.*.security")
        .flatMap { key =>
          val path = key.split('.').toList
          // The list-valued security keys (`enabledProtocols`, `cipherSuites`) are legal as a YAML list,
          // whose leaves are indexed, so each key is known both as a scalar and as a sequence.
          List(path, path :+ "*")
        }

    def check(documents: List[Document]): ValidatedNel[ConfigProblem, Unit] =
      documents.flatMap(document => unknownIn(document)) match {
        case Nil => ().validNel
        case first :: rest => cats.data.Validated.Invalid(NonEmptyList(first, rest))
      }

    private def unknownIn(document: Document): List[ConfigProblem] =
      leaves(document.json, Nil)
        .filterNot(leaf => isKnown(leaf))
        .map(leaf =>
          ConfigProblem(
            leaf.path.mkString("."),
            "is not a KUI configuration key",
            ConfigSourceName.File(document.path)
          )
        )

    /** A leaf of the document: the path it sits at, and whether what sits there is an empty container.
      *
      * The distinction is the whole point. A scalar has to name a key that exists. An empty container
      * (`services: {}`, `principalKeys: []`, `telemetry:` with everything under it commented out) names no
      * key at all — it supplies nothing — so it is legal wherever a known key could appear below it, and
      * refusing it would refuse a configuration that says exactly what it means.
      */
    final private case class Leaf(path: List[String], isEmptyContainer: Boolean)

    private def isKnown(leaf: Leaf): Boolean =
      if leaf.isEmptyContainer then
        Known.exists(pattern => matches(pattern, leaf.path) || isPrefixOf(pattern, leaf.path))
      else Known.exists(matches(_, leaf.path))

    /** Every path from the document root down to a scalar or to an empty container. */
    private def leaves(json: Json, path: List[String]): List[Leaf] =
      json.asObject match {
        case Some(obj) if obj.nonEmpty =>
          obj.toList.flatMap { case (name, child) => leaves(child, path :+ name) }
        case _ =>
          json.asArray match {
            case Some(items) if items.nonEmpty =>
              items.toList.zipWithIndex.flatMap { case (child, index) =>
                leaves(child, path :+ index.toString)
              }
            case _ =>
              val isContainer = json.isObject || json.isArray
              List(Leaf(path, isContainer))
          }
      }

    /** Whether `path` names a section that `pattern` has keys underneath, e.g. `kui.gateway.services` against
      * `kui.gateway.services.*.url`.
      */
    private def isPrefixOf(pattern: List[String], path: List[String]): Boolean =
      (pattern, path) match {
        case ("**" :: _, _) => true
        case (Nil, _) => false
        case (_, Nil) => true
        case (patternHead :: patternTail, pathHead :: pathTail) =>
          (patternHead == "*" || patternHead == pathHead) && isPrefixOf(patternTail, pathTail)
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
