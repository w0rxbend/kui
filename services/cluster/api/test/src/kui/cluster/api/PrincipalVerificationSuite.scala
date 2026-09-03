package kui.cluster.api

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

import cats.effect.IO
import munit.CatsEffectSuite
import sttp.client4.*

import kui.contracts.KuiEndpoint
import kui.kernel.{ServiceId, UserName}
import kui.observability.MetricNames
import kui.security.{PrincipalKind, RequestDigest}

/** That a caller learns nothing from a refusal, and that this service learns everything.
  *
  * Those two sentences are the whole of ADR-020's response rule, and they pull in opposite directions, which
  * is why both halves are asserted here: the body must be identical for every kind of failure, and the log
  * and the counter must still say which one it was.
  */
final class PrincipalVerificationSuite extends CatsEffectSuite {

  private def ping(server: ClusterTestServer, header: Option[String]) = {
    val base = basicRequest.get(uri"${ClusterTestServer.PingUri}?message=hello").response(asStringAlways)
    header.fold(base)(value => base.header(KuiEndpoint.PrincipalHeader, value)).send(server.backend)
  }

  // -----------------------------------------------------------------------------------------------
  // Every refusal looks the same
  // -----------------------------------------------------------------------------------------------

  test("everyKindOfRefusalIsTheSameFourOhOne") {
    ClusterTestServer.resource().use { server =>
      for {
        // No header at all. This one does not even reach the security logic: the header codec
        // refuses a blank value, so it arrives as a decode failure and `PrincipalInterceptor` is
        // what stops the shared handler calling it a 400.
        missing <- ping(server, None)

        // A token minted for another service. A stolen `cluster` header must not work against
        // `topic`, and the check that makes that true is the one being exercised here.
        wrongAudience <- ClusterTestServer
          .token(audience = ServiceId.unsafe("topic"))
          .flatMap(t => ping(server, Some(t.value)))

        // A token that was good a minute ago. Sixty-second lifetimes are what limit the damage of
        // an intercepted header, and they only limit it if expiry is actually enforced.
        expired <- ClusterTestServer
          .token(validFor = (-120).seconds)
          .flatMap(t => ping(server, Some(t.value)))

        // A signature that no longer matches its payload.
        tampered <- ClusterTestServer
          .token()
          .flatMap(t => ping(server, Some(tamper(t.value))))

        // A perfectly valid token for a *different* call. This is ADR-020's request binding: the
        // same principal, inside the same minute, on an operation the gateway never authorised.
        otherCall <- ClusterTestServer
          .token(digest = RequestDigest.ofRequestLine("DELETE", "/internal/v1/topics/orders"))
          .flatMap(t => ping(server, Some(t.value)))

        // An unsigned string that is not a token at all.
        malformed <- ping(server, Some("not-a-token"))
      } yield {
        val responses = List(missing, wrongAudience, expired, tampered, otherCall, malformed)

        responses.foreach(response => assertEquals(response.code.code, 401, response.body))

        // Byte-identical apart from the two fields that are different on every response by design.
        // An endpoint that answered "wrong audience" for one and "bad signature" for another is an
        // oracle: an attacker with one can fix a forged token a field at a time.
        val bodies = responses.map(response => ClusterTestServer.withoutVaryingFields(response.body))
        assertEquals(bodies.distinct.size, 1, bodies.map(_.noSpaces).distinct.mkString("\n"))
        assertEquals(
          bodies.head.hcursor.get[String]("code"),
          Right("KUI-UNAUTHENTICATED")
        )
        assertEquals(bodies.head.hcursor.get[String]("message"), Right("Unauthenticated"))
      }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // The service still knows what happened
  // -----------------------------------------------------------------------------------------------

  test("theSpecificPrincipalErrorIsLoggedAndCounted") {
    ClusterTestServer.resource().use { server =>
      for {
        _ <- ClusterTestServer
          .token(audience = ServiceId.unsafe("topic"))
          .flatMap(t => ping(server, Some(t.value)))
        entries <- server.logger.entries
        metrics <- server.telemetry.collectMetrics
      } yield {
        // The reason reaches the log, where an operator looking at a wave of 401s can see whether a
        // key rotation went wrong or a clock drifted.
        assert(
          entries.exists(entry =>
            entry.level == "warn" &&
              entry.context.get(MetricNames.Attr.Reason).contains("wrong_audience")
          ),
          entries.toString
        )

        val counter = metrics.find(_.getName == MetricNames.PrincipalRejected)
        assert(counter.isDefined, metrics.map(_.getName).toString)

        val labels = counter.toList
          .flatMap(_.getData.getPoints.asScala.toList)
          .map(point =>
            point.getAttributes.asMap.asScala.map((key, value) => key.getKey -> value.toString).toMap
          )
        assert(
          labels.exists(_.get(MetricNames.Attr.Reason).contains("wrong_audience")),
          labels.toString
        )
      }
    }
  }

  test("aMissingHeaderIsCountedAsMissingRatherThanAsAValidationFailure") {
    ClusterTestServer.resource().use { server =>
      for {
        _ <- ping(server, None)
        metrics <- server.telemetry.collectMetrics
      } yield {
        val labels = metrics
          .filter(_.getName == MetricNames.PrincipalRejected)
          .flatMap(_.getData.getPoints.asScala.toList)
          .map(point =>
            point.getAttributes.asMap.asScala.map((key, value) => key.getKey -> value.toString).toMap
          )
        assert(labels.exists(_.get(MetricNames.Attr.Reason).contains("missing")), labels.toString)
      }
    }
  }

  test("aValidPrincipalReachesTheUseCaseWithTheRightIdentity") {
    ClusterTestServer.resource().use { server =>
      for {
        token <- ClusterTestServer.token(subject = "alice")
        response <- ping(server, Some(token.value))
        entries <- server.logger.entries
      } yield {
        assertEquals(response.code.code, 200, response.body)

        val verified = entries.find(_.message.startsWith("verified a "))
        assert(verified.isDefined, entries.toString)

        // The identity is on the line as a hash and never as the login name: a log file is read by
        // more people, kept for longer and exported more often than any other store in the system.
        val expected = PrincipalVerification.hashedUserId(
          kui.security.Principal(UserName.unsafe("alice"), Set.empty, PrincipalKind.Session)
        )
        assertEquals(verified.flatMap(_.context.get("user.id")), Some(expected))
        assert(!entries.exists(_.context.values.exists(_ == "alice")), entries.toString)
      }
    }
  }

  /** Flips one character of the signature, leaving a structurally valid token that no key signed. */
  private def tamper(token: String): String = {
    val parts = token.split('.')
    val signature = parts(2)
    val flipped = (if signature.head == 'A' then 'B' else 'A').toString + signature.tail
    s"${parts(0)}.${parts(1)}.$flipped"
  }
}
