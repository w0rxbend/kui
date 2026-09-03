package kui.gateway.api.openapi

import java.nio.file.{Files, Path}

import kui.kernel.ServiceId

/** Writes `docs/api/openapi.json`, and fails the build when the committed copy has gone stale.
  *
  * A generated document that nobody regenerates is worse than none: an integrator reads it, believes it, and
  * builds against an API that changed three releases ago. `--check` is what makes the committed file
  * trustworthy, and it runs in CI on every change (PLAN §46).
  *
  * The document is generated for the **full** product -- every service KUI has a contract for -- and not for
  * whatever a particular deployment happens to have configured. The committed file describes the API, which
  * is a property of the release; a deployment that has not enabled a service still ships the same
  * documentation.
  */
object OpenApiDocument {

  /** The deployment-independent server entry. A committed document cannot know a deployment's hostname, so it
    * says what every KUI installation has in common and leaves the host to the reader.
    */
  val DefaultServer: String = "/"

  def documentedServices: List[ServiceId] =
    kui.gateway.api.routing.ServiceContracts.byService.keys.toList.sortBy(_.value)

  def render: String =
    DocsRoutes
      .document[cats.effect.IO](documentedServices, List(DefaultServer))
      .fold(problem => sys.error(problem), DocsRoutes.render)

  def main(args: Array[String]): Unit = {
    val target = args.headOption.getOrElse {
      Console.err.println("usage: OpenApiDocument <path to openapi.json> [--check]")
      sys.exit(2)
    }
    val check = args.contains("--check")
    val file = Path.of(target)
    val expected = render

    if !check then {
      Option(file.getParent).foreach(Files.createDirectories(_))
      Files.writeString(file, expected)
      println(s"wrote $target")
    } else if !Files.exists(file) then {
      Console.err.println(s"$target does not exist; run ./mill services.gateway.api.openApi")
      sys.exit(1)
    } else if Files.readString(file) != expected then {
      Console.err.println(
        s"$target is out of date with the endpoints it is generated from; " +
          "run ./mill services.gateway.api.openApi and commit the result"
      )
      sys.exit(1)
    }
  }
}
