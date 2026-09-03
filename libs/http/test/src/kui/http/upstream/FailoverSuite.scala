package kui.http.upstream

import scala.concurrent.duration.DurationInt

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite
import sttp.client4.*

import kui.config.{SafeUrl, UrlPolicy}

/** That several addresses for one upstream behave the way an operator expects: a machine that
  * refuses a connection is stepped over, and it is given another chance once the wobble has passed.
  */
final class FailoverSuite extends CatsEffectSuite {

  private def url(raw: String): SafeUrl =
    SafeUrl.from(raw, UrlPolicy.Dev).fold(error => fail(error.message), identity)

  private val a = url("http://registry-a:8081")
  private val b = url("http://registry-b:8081")
  private val c = url("http://registry-c:8081")

  private val grace = 5.seconds

  private def failover: IO[Failover[IO]] =
    Failover.make[IO](NonEmptyList.of(a, b, c), grace)

  test("with nothing failed, the addresses are offered in the configured order") {
    failover.flatMap(_.candidates).map(candidates => assertEquals(candidates.toList, List(a, b, c)))
  }

  test("rotates past an address that refused a connection") {
    val program = for {
      f <- failover
      _ <- f.markFailed(a)
      candidates <- f.candidates
    } yield candidates

    TestControl.executeEmbed(program).map(candidates => assertEquals(candidates.toList, List(b, c)))
  }

  test("respects the grace period, and uses a recovered address again once it has passed") {
    val program = for {
      f <- failover
      _ <- f.markFailed(a)
      _ <- IO.sleep(grace - 1.second)
      during <- f.candidates
      _ <- IO.sleep(2.seconds)
      after <- f.candidates
    } yield (during.toList, after.toList)

    TestControl.executeEmbed(program).map { (during, after) =>
      assertEquals(during, List(b, c))
      assertEquals(after, List(a, b, c))
    }
  }

  test("an address that answers has its grace period cleared at once") {
    val program = for {
      f <- failover
      _ <- f.markFailed(a)
      _ <- f.markHealthy(a)
      candidates <- f.candidates
      failed <- f.failed
    } yield (candidates.toList, failed)

    TestControl.executeEmbed(program).map { (candidates, failed) =>
      assertEquals(candidates, List(a, b, c))
      assertEquals(failed, Set.empty[SafeUrl])
    }
  }

  test("when every address is inside its grace period, they are all offered anyway") {
    // Refusing to try at all would turn a transient wobble into a hard outage that only the clock
    // could clear. Better to try, fail, and report `Unreachable` honestly.
    val program = for {
      f <- failover
      _ <- List(a, b, c).traverse_(f.markFailed)
      candidates <- f.candidates
    } yield candidates.toList

    TestControl.executeEmbed(program).map(candidates => assertEquals(candidates, List(a, b, c)))
  }

  test("rebase points a request at the chosen address, keeping the request's own path") {
    val request = uri"http://ignored:1234/subjects/orders-value/versions?deleted=true"

    assertEquals(
      Failover.rebase(request, url("https://registry-b:8081")).toString,
      "https://registry-b:8081/subjects/orders-value/versions?deleted=true"
    )
  }

  test("rebase keeps a base address's own path prefix in front of the request's") {
    // A registry served under `/api` on a shared host: the prefix belongs to the address, not to
    // the endpoint, so no contract should have to know about it.
    assertEquals(
      Failover.rebase(uri"http://ignored/subjects", url("https://host/api")).toString,
      "https://host/api/subjects"
    )
  }

  test("isConnectionFailure is about reaching it, not about what it said") {
    assert(Failover.isConnectionFailure(new java.net.ConnectException("refused")))
    assert(Failover.isConnectionFailure(new java.net.UnknownHostException("registry-a")))
    // Wrapped, which is how a client library usually hands one over.
    assert(Failover.isConnectionFailure(new RuntimeException("wrapped", new java.net.ConnectException())))
    // A read timeout is not a connection failure: the machine is there, it is just slow, and the
    // next address would most likely be just as slow.
    assert(!Failover.isConnectionFailure(new java.util.concurrent.TimeoutException("too slow")))
    assert(!Failover.isConnectionFailure(new RuntimeException("500 from upstream")))
  }
}
