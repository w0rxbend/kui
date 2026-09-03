package kui.gateway.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given

/** Which build of KUI is running.
  *
  * Every support conversation starts here, and the honest answer is not a container tag: a tag can be moved,
  * rebuilt, or typed by hand. The commit is what the code was actually compiled from, and `gitDirty` is what
  * explains the otherwise impossible situation where "it works on the commit you gave me" and "it does not
  * work for you" are both true.
  *
  * Every field is a `String` or a `Boolean` and none is optional. A build made from a release tarball, with
  * no git checkout to ask, reports `"unknown"` rather than omitting the field — a UI footer that renders a
  * gap tells a user nothing, while one that renders "unknown" tells them exactly what happened.
  *
  * @param version
  *   the product version, e.g. `0.1.0-SNAPSHOT`
  * @param gitCommit
  *   the full commit hash, for tooling that wants to check it out
  * @param gitCommitShort
  *   the same hash abbreviated, which is what a person pastes into a message
  * @param gitDirty
  *   whether the working tree had uncommitted changes when the build ran
  * @param builtAt
  *   when the build ran
  * @param scalaVersion
  *   the compiler that produced the bytecode
  * @param jdkVersion
  *   the JDK the build ran on
  */
final case class BuildInfoDto(
    version: String,
    gitCommit: String,
    gitCommitShort: String,
    gitDirty: Boolean,
    builtAt: Instant,
    scalaVersion: String,
    jdkVersion: String
)

object BuildInfoDto {

  /** Written out rather than derived (ADR-007), so the wire format appears in a diff when it changes. The
    * `Instant` codec comes from `ErrorEnvelope`, which is where KUI fixes timestamps to RFC 3339 in UTC with
    * exactly three fractional digits — without it the same instant would serialise differently depending on
    * its precision, and this document's golden file would pass or fail depending on the machine.
    */
  given Codec[BuildInfoDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        version <- cursor.get[String]("version")
        gitCommit <- cursor.get[String]("gitCommit")
        gitCommitShort <- cursor.get[String]("gitCommitShort")
        gitDirty <- cursor.get[Boolean]("gitDirty")
        builtAt <- cursor.get[Instant]("builtAt")
        scalaVersion <- cursor.get[String]("scalaVersion")
        jdkVersion <- cursor.get[String]("jdkVersion")
      } yield BuildInfoDto(
        version,
        gitCommit,
        gitCommitShort,
        gitDirty,
        builtAt,
        scalaVersion,
        jdkVersion
      ),
    (build: BuildInfoDto) =>
      Json.obj(
        "version" -> build.version.asJson,
        "gitCommit" -> build.gitCommit.asJson,
        "gitCommitShort" -> build.gitCommitShort.asJson,
        "gitDirty" -> build.gitDirty.asJson,
        "builtAt" -> build.builtAt.asJson,
        "scalaVersion" -> build.scalaVersion.asJson,
        "jdkVersion" -> build.jdkVersion.asJson
      )
  )

  given Schema[BuildInfoDto] =
    Schema.derived[BuildInfoDto].description("The build this process was compiled from")

  given CanEqual[BuildInfoDto, BuildInfoDto] = CanEqual.derived
}

/** What `GET /api/v1/info` answers with: the build, the deployment's shape, and what is switched on.
  *
  * ==This document is public, and it must stay that way==
  *
  * `/api/v1/info` is the endpoint an unauthenticated health dashboard reads, so everything in it is visible
  * to anyone who can reach the gateway. That constrains the contents absolutely: **no URL, no hostname, no
  * port, no key id, no configuration value that is not already public.** `services` is a list of service
  * *ids* — `cluster`, `topic` — and never their addresses, which is why the field is a `List[String]` of
  * identifiers rather than the configuration map it is derived from. `InfoRoutesSuite` asserts that the
  * serialised document contains no `http` substring, which is a crude check that catches exactly the mistake
  * it is meant to: someone adding a URL because it seemed useful for debugging.
  *
  * @param build
  *   which build this is
  * @param authType
  *   how users are authenticated. `"disabled"` is the only M0 value (CFG-001 rejects the others); M6 adds the
  *   real ones. The shell reads it to decide whether to show a sign-in control at all.
  * @param basePath
  *   the path prefix KUI is mounted at, so the shell can build links without hard-coding `/api/v1`
  * @param services
  *   the configured service ids, sorted. Ids only — see the note above.
  * @param features
  *   named switches, so the shell can hide what a deployment turned off without a version check. Extended per
  *   milestone; `cors` is the first one.
  */
final case class AppInfo(
    build: BuildInfoDto,
    authType: String,
    basePath: String,
    services: List[String],
    features: Map[String, Boolean]
)

object AppInfo {

  /** The only `auth.type` M0 accepts. Anything else is refused at configuration load with a message pointing
    * at M6, rather than being silently accepted and then ignored.
    */
  val AuthDisabled: String = "disabled"

  given Codec[AppInfo] = Codec.from(
    (cursor: HCursor) =>
      for {
        build <- cursor.get[BuildInfoDto]("build")
        authType <- cursor.get[String]("authType")
        basePath <- cursor.get[String]("basePath")
        services <- cursor.get[List[String]]("services")
        features <- cursor.get[Map[String, Boolean]]("features")
      } yield AppInfo(build, authType, basePath, services, features),
    (info: AppInfo) =>
      Json.obj(
        "build" -> info.build.asJson,
        "authType" -> info.authType.asJson,
        "basePath" -> info.basePath.asJson,
        "services" -> info.services.asJson,
        "features" -> info.features.asJson
      )
  )

  given Schema[AppInfo] =
    Schema.derived[AppInfo].description("Which build of KUI is running, and what this deployment enables")

  given CanEqual[AppInfo, AppInfo] = CanEqual.derived
}
