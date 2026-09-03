package kui.http

import cats.effect.IO
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.model.Method
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

import kui.config.{CorsConfig, KuiConfigSource, UrlPolicy}
import kui.contracts.ErrorEnvelope
import kui.contracts.ErrorEnvelope.given

/** That cross-origin access is off unless someone turned it on, and that turning it on still means
  * an explicit list.
  */
final class CorsSuite extends CatsEffectSuite {

  private val AllowOrigin = "Access-Control-Allow-Origin"
  private val AllowCredentials = "Access-Control-Allow-Credentials"
  private val AllowMethods = "Access-Control-Allow-Methods"
  private val Vary = "Vary"

  private val ping: ServerEndpoint[Fs2Streams[IO], IO] =
    endpoint.get
      .in("ping")
      .out(stringBody)
      .errorOut(jsonBody[ErrorEnvelope])
      .serverLogicSuccess[IO](_ => IO.pure("pong"))

  private val allowed = CorsConfig(enabled = true, List("https://console.example.com"))

  test("disabled by default: no CORS headers at all, not even ones that deny") {
    TestServer.resource(List(ping)).use { server =>
      server.get("/ping", Map("Origin" -> "https://console.example.com")).map { response =>
        assertEquals(response.header(AllowOrigin), None)
        assertEquals(response.header(AllowCredentials), None)
        assertEquals(response.body, "pong")
      }
    }
  }

  test("Cors.interceptor is None when disabled, and None when enabled with an empty list") {
    assertEquals(Cors.interceptor[IO](CorsConfig.Default), None)
    assertEquals(Cors.interceptor[IO](CorsConfig(enabled = true, Nil)), None)
    assert(Cors.interceptor[IO](allowed).isDefined)
  }

  test("an allowed origin is echoed back, with Vary: Origin so a cache cannot mix them up") {
    TestServer.resource(List(ping), cors = allowed).use { server =>
      server.get("/ping", Map("Origin" -> "https://console.example.com")).map { response =>
        assertEquals(response.header(AllowOrigin), Some("https://console.example.com"))
        assertEquals(response.header(AllowCredentials), Some("true"))
        assert(response.header(Vary).exists(_.contains("Origin")), response.headers.toString)
      }
    }
  }

  test("an origin that is not on the list gets no allow header") {
    TestServer.resource(List(ping), cors = allowed).use { server =>
      server.get("/ping", Map("Origin" -> "https://evil.example.com")).map { response =>
        assertEquals(response.header(AllowOrigin), None)
      }
    }
  }

  test("a preflight names the methods KUI actually serves") {
    TestServer.resource(List(ping), cors = allowed).use { server =>
      server
        .request(
          basicRequest
            .method(Method.OPTIONS, server.at("/ping"))
            .header("Origin", "https://console.example.com")
            .header("Access-Control-Request-Method", "GET")
        )
        .map { response =>
          assertEquals(response.header(AllowOrigin), Some("https://console.example.com"))
          val methods = response.header(AllowMethods).getOrElse("")
          Cors.AllowedMethods.foreach(method => assert(methods.contains(method.method), methods))
          assert(!methods.contains("TRACE"), methods)
        }
    }
  }

  /** A YAML file on disk, because the loader takes paths and this suite needs exactly one file. */
  private def writeYaml(contents: String): java.nio.file.Path = {
    val directory = java.nio.file.Files.createTempDirectory("kui-cors")
    val file = directory.resolve("kui.yaml")
    val _ = java.nio.file.Files.write(file, contents.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    file.toFile.deleteOnExit()
    directory.toFile.deleteOnExit()
    file
  }

  test("'*' in the configuration is refused at load time, so it can never reach this module") {
    val file = writeYaml(
      """kui:
        |  gateway:
        |    cors:
        |      enabled: true
        |      origins: ["*"]
        |""".stripMargin
    )

    KuiConfigSource.loadFrom[IO](Nil, List(file), Map.empty, UrlPolicy.Dev).map {
      case Right(_) => fail("a wildcard origin was accepted")
      case Left(errors) => assertEquals(errors.keys, List("kui.gateway.cors.origins"))
    }
  }
}
