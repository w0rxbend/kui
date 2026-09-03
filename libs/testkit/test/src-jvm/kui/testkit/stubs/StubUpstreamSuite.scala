package kui.testkit.stubs

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import io.circe.Json
import sttp.client4.*

import kui.testkit.KuiIOSuite

/** That the stub upstream really does what a resilience test will ask of it. */
final class StubUpstreamSuite extends KuiIOSuite {

  private val request = basicRequest.get(uri"http://upstream.internal/health")

  test("it answers with the configured body") {
    for {
      stub     <- StubUpstream(UpstreamBehaviour.Ok(Json.obj("status" -> Json.fromString("up"))))
      response <- request.send(stub.backend)
    } yield {
      assertEquals(response.code.code, 200)
      assertEquals(response.body, Right("""{"status":"up"}"""))
    }
  }

  test("it can be switched to failing and back while a client is running") {
    for {
      stub  <- StubUpstream(UpstreamBehaviour.Ok(Json.obj()))
      first <- request.send(stub.backend)
      _     <- stub.set(UpstreamBehaviour.Status(503, Json.obj()))
      down  <- request.send(stub.backend)
      _     <- stub.set(UpstreamBehaviour.Ok(Json.obj()))
      back  <- request.send(stub.backend)
    } yield {
      assertEquals(first.code.code, 200)
      assertEquals(down.code.code, 503)
      assertEquals(back.code.code, 200)
    }
  }

  test("a refused connection raises, the way a real one does") {
    for {
      stub   <- StubUpstream(UpstreamBehaviour.ConnectionRefused)
      result <- request.send(stub.backend).attempt
    } yield assert(
      result.left.exists(_.isInstanceOf[StubUpstream.ConnectionRefusedException]),
      s"expected a refused connection, got $result"
    )
  }

  test("a slow upstream takes at least as long as it was told to") {
    for {
      stub  <- StubUpstream(UpstreamBehaviour.Slow(50.millis, UpstreamBehaviour.Ok(Json.obj())))
      start <- IO.monotonic
      _     <- request.send(stub.backend)
      end   <- IO.monotonic
    } yield assert((end - start) >= 50.millis, s"the call took ${end - start}")
  }

  test("every call is recorded, which is how a retry count is asserted") {
    for {
      stub <- StubUpstream(UpstreamBehaviour.Ok(Json.obj()))
      _    <- request.send(stub.backend)
      _    <- request.post(uri"http://upstream.internal/things").body("{}").send(stub.backend)
      seen <- stub.requests
      _    <- stub.reset
      none <- stub.requests
    } yield {
      assertEquals(seen.map(_.method), List("GET", "POST"))
      assertEquals(
        seen.map(_.uri),
        List("http://upstream.internal/health", "http://upstream.internal/things")
      )
      assertEquals(none, Nil)
    }
  }
}
