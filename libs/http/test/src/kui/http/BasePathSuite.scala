package kui.http

import cats.effect.IO
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

import kui.contracts.ErrorEnvelope
import kui.contracts.ErrorEnvelope.given

/** That a deployment behind a reverse proxy on a sub-path works, and that nothing in a contract has
  * to know about it.
  */
final class BasePathSuite extends CatsEffectSuite {

  test("normalize: the four spellings an operator actually writes") {
    val table = List(
      "" -> "",
      "/" -> "",
      "//" -> "",
      "kui" -> "/kui",
      "/kui" -> "/kui",
      "/kui/" -> "/kui",
      "/kui//" -> "/kui",
      "/kui/admin" -> "/kui/admin",
      "/kui/admin/" -> "/kui/admin"
    )

    table.foreach { (raw, expected) =>
      assertEquals(BasePath.normalize(raw), expected, clue = s"normalize('$raw')")
    }
  }

  test("normalize is idempotent, so applying it twice cannot double a prefix") {
    List("", "/", "/kui", "/kui/", "kui/admin").foreach { raw =>
      assertEquals(BasePath.normalize(BasePath.normalize(raw)), BasePath.normalize(raw))
    }
  }

  private val live: ServerEndpoint[Fs2Streams[IO], IO] =
    endpoint.get
      .in("health" / "live")
      .out(stringBody)
      .errorOut(jsonBody[ErrorEnvelope])
      .serverLogicSuccess[IO](_ => IO.pure("alive"))

  test("with basePath = /kui, GET /kui/health/live works and GET /health/live is a 404") {
    TestServer.resource(List(live), basePath = "/kui").use { server =>
      for {
        prefixed <- server.get("/kui/health/live")
        bare <- server.get("/health/live")
      } yield {
        assertEquals(prefixed.code.code, 200)
        assertEquals(prefixed.body, "alive")
        assertEquals(bare.code.code, 404)
      }
    }
  }

  test("with no basePath, the bare path is the one that works") {
    TestServer.resource(List(live), basePath = "/").use { server =>
      for {
        bare <- server.get("/health/live")
        prefixed <- server.get("/kui/health/live")
      } yield {
        assertEquals(bare.code.code, 200)
        assertEquals(prefixed.code.code, 404)
      }
    }
  }

  test("the binding reports the base path it is serving under") {
    TestServer.resource(List(live), basePath = "/kui/").use { server =>
      IO(assertEquals(server.binding.basePath, "/kui"))
    }
  }

  test("a multi-segment base path is prefixed segment by segment, not as one literal") {
    TestServer.resource(List(live), basePath = "/kui/admin").use { server =>
      server.get("/kui/admin/health/live").map(response => assertEquals(response.code.code, 200))
    }
  }
}
