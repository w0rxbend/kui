package kui.http.upstream

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import sttp.client4.*
import sttp.model.StatusCode

import kui.config.{SafeUrl, UrlPolicy}
import kui.kernel.error.InfrastructureError

/** That the outbound URL policy is enforced where it actually matters — on the address a request is
  * about to be sent to, not only on the one an operator typed.
  *
  * A configuration-time check alone is not enough. A redirect is chosen by the upstream, not by
  * KUI, so an upstream that has been compromised (or merely misconfigured) could otherwise redirect
  * KUI to `http://169.254.169.254/` and have it fetch the cloud instance's own credentials from a
  * network position no outsider has. `libs/config`'s `UrlPolicySuite` covers the rules themselves;
  * this covers them being applied per call.
  */
final class UrlPolicySuite extends CatsEffectSuite {

  private def url(raw: String): SafeUrl =
    SafeUrl.from(raw, UrlPolicy.Dev).fold(error => fail(error.message), identity)

  private def clientFor(base: String, policy: UrlPolicy) =
    UpstreamFixture.config("registry", NonEmptyList.one(url(base))).copy(urlPolicy = policy)

  test("a request to an address the policy refuses never leaves the process") {
    UpstreamFixture.recording(ResponseKind.Ok).flatMap { stub =>
      UpstreamFixture
        .client(clientFor("http://169.254.169.254", UrlPolicy.Strict), stub.backend)
        .use { client =>
          for {
            outcome <- basicRequest.get(uri"http://ignored/latest/meta-data").send(client.backend).attempt
            reached <- stub.calls
          } yield {
            assertEquals(reached, 0, "a refused address was still contacted")
            assertEquals(
              outcome.left.toOption.collect { case UpstreamFailure(e: InfrastructureError.Unreachable) =>
                e.upstream
              },
              Some("registry")
            )
          }
        }
    }
  }

  test("the metadata address, loopback and the private ranges are all refused under Strict") {
    List("http://169.254.169.254", "http://127.0.0.1:8081", "http://10.0.0.1", "http://[::1]:8081")
      .traverse { address =>
        UpstreamFixture.recording(ResponseKind.Ok).flatMap { stub =>
          UpstreamFixture.client(clientFor(address, UrlPolicy.Strict), stub.backend).use { client =>
            basicRequest.get(uri"http://ignored/x").send(client.backend).attempt.map(_.isLeft -> address)
          }
        }
      }
      .map(results => results.foreach((refused, address) => assert(refused, s"$address was allowed")))
  }

  test("the same addresses are allowed under the development policy") {
    UpstreamFixture.recording(ResponseKind.Ok).flatMap { stub =>
      UpstreamFixture.client(clientFor("http://127.0.0.1:8081", UrlPolicy.Dev), stub.backend).use {
        client =>
          basicRequest.get(uri"http://ignored/x").send(client.backend).map { response =>
            assertEquals(response.code, StatusCode.Ok)
          }
      }
    }
  }

  test("a policy refusal is not retried and does not fail over to the next address") {
    // The next address would be refused for the same reason, and repeating a refusal only delays
    // the answer. It is a policy decision, not a transport wobble.
    val config = UpstreamFixture
      .config("registry", NonEmptyList.of(url("http://10.0.0.1"), url("http://10.0.0.2")))
      .copy(urlPolicy = UrlPolicy.Strict)

    UpstreamFixture.recording(ResponseKind.Ok).flatMap { stub =>
      UpstreamFixture.client(config, stub.backend).use { client =>
        for {
          _ <- basicRequest.get(uri"http://ignored/x").send(client.backend).attempt
          reached <- stub.calls
        } yield assertEquals(reached, 0)
      }
    }
  }

  test("a scheme other than http or https is refused, in every environment") {
    // ARCHITECTURE.md §14 allows two schemes and no development exception, so a `file://` upstream
    // cannot be reached even on a laptop.
    List(UrlPolicy.Strict, UrlPolicy.Dev).foreach { policy =>
      assert(SafeUrl.from("file:///etc/passwd", policy).isLeft, policy.toString)
      assert(SafeUrl.from("ftp://registry", policy).isLeft, policy.toString)
    }
  }
}
