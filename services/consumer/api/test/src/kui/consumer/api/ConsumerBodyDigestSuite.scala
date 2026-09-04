package kui.consumer.api

import java.nio.charset.StandardCharsets

import cats.effect.IO
import io.circe.Printer
import io.circe.syntax.*
import sttp.client4.*
import sttp.model.StatusCode

import kui.consumer.contract.dto.*
import kui.consumer.domain.ResetSpec
import kui.kernel.TopicName
import kui.kernel.group.ResetTarget
import kui.testkit.KuiIOSuite

/** ADR-020's body binding, on the two endpoints in KUI that carry a request body.
  *
  * ==What this pins==
  *
  * A signed principal is bound to exactly one call by a digest over the method, the path **and the body**.
  * The gateway signs the bytes it is about to send; a service has to hash the same bytes or the call is
  * refused as `request_mismatch`. Tapir runs security logic before it decodes a body and a `ServerRequest`
  * does not expose the raw bytes, so this service cannot compute that hash where an unbodied route verifies:
  * `SecuredRoutes.withBody` verifies inside the endpoint's own logic instead, reconstructing the bytes by
  * re-encoding the decoded value through the same contract codec the gateway encoded it with.
  *
  * That reconstruction is the fragile part, and it fails silently in both directions. Print the JSON with a
  * different printer — pretty, or dropping nulls — and every reset is a 401 that names nothing. Stop hashing
  * the body at all and the binding disappears, which nothing notices because every other endpoint in the
  * product has an empty body. The first of those is what actually happened, the first time the reset wizard
  * was called end to end against a real cluster.
  *
  * So there are three cases, and the third is the one with teeth: a token minted for one plan must not apply
  * a different one.
  */
final class ConsumerBodyDigestSuite extends KuiIOSuite {

  import ConsumerTestServer.*

  private val Csrf: String = "a-csrf-token"

  /** The bytes the gateway would put on the wire for this request: the contract's own encoder, `noSpaces`. */
  private def bytesOf[A: io.circe.Encoder](value: A): Array[Byte] =
    Printer.noSpaces.print(value.asJson).getBytes(StandardCharsets.UTF_8)

  private def planRequest(topic: TopicName): ResetPlanRequest =
    ResetPlanRequest(
      topic = topic,
      partitions = Nil,
      target = ResetTarget.Earliest,
      timestamp = None,
      offsets = None,
      shiftBy = None,
      durationMs = None
    )

  private def planPath: String = path(s"/consumer-groups/${Group.value}/offsets/plan")
  private def applyPath: String = path(s"/consumer-groups/${Group.value}/offsets")

  private def post[A: io.circe.Encoder](
      server: ConsumerTestServer,
      requestPath: String,
      body: A,
      signedBody: Array[Byte]
  ): IO[Response[Either[String, String]]] =
    token(requestPath, method = "POST", body = signedBody).flatMap(principal =>
      basicRequest
        .post(uri"${uri(requestPath)}")
        .header(kui.contracts.KuiEndpoint.PrincipalHeader, principal.value)
        .header(kui.contracts.HttpHeaders.Csrf, Csrf)
        .header("Content-Type", "application/json")
        .body(new String(bytesOf(body), StandardCharsets.UTF_8))
        .send(server.backend)
    )

  test("aPlanTokenSignedOverTheBodyItSendsIsAccepted") {
    val request = planRequest(Topic)

    resource().use { (server, stubs) =>
      for {
        response <- post(server, planPath, request, bytesOf(request))
        planned <- stubs.reset.planned.get
      } yield {
        assertEquals(response.code, StatusCode.Ok, clue = response.body.swap.getOrElse(""))
        assertEquals(planned, List(Group -> ResetSpec.ToEarliest))
      }
    }
  }

  test("aTokenSignedOverTheRequestLineAloneIsRefusedAndNothingIsPlanned") {
    // The tempting one-line "fix" for the mismatch: sign the method and path only. It would pass every
    // existing suite in the product, because every other endpoint has an empty body — and it would drop
    // the body binding for every service at once.
    val request = planRequest(Topic)

    resource().use { (server, stubs) =>
      for {
        response <- post(server, planPath, request, Array.emptyByteArray)
        planned <- stubs.reset.planned.get
      } yield {
        assertEquals(response.code, StatusCode.Unauthorized)
        assertEquals(planned, List.empty, clue = "the use case ran despite a token bound to other bytes")
      }
    }
  }

  test("aTokenMintedForOnePlanCannotBeReplayedWithAnother") {
    // The property the binding buys. Both requests are well-formed, both name a topic the operator may
    // reset, and only the one the token was minted for is allowed through.
    val signed = planRequest(Topic)
    val substituted = planRequest(TopicName.unsafe("payments.transactions"))

    resource().use { (server, stubs) =>
      for {
        response <- post(server, planPath, substituted, bytesOf(signed))
        planned <- stubs.reset.planned.get
      } yield {
        assertEquals(response.code, StatusCode.Unauthorized)
        assertEquals(planned, List.empty)
      }
    }
  }

  test("theApplyEndpointBindsItsBodyTheSameWay") {
    // `apply` is the destructive half, so its binding matters more than `plan`'s and is asserted
    // separately rather than assumed to follow.
    val request = ResetApplyRequest(token = "a-plan-token")

    resource().use { (server, stubs) =>
      for {
        accepted <- post(server, applyPath, request, bytesOf(request))
        applied <- stubs.reset.applied.get
        replayed <- post(server, applyPath, ResetApplyRequest("a-different-token"), bytesOf(request))
        appliedAfter <- stubs.reset.applied.get
      } yield {
        assertEquals(accepted.code, StatusCode.Ok, clue = accepted.body.swap.getOrElse(""))
        assertEquals(applied, List(Group -> "a-plan-token"))

        assertEquals(replayed.code, StatusCode.Unauthorized)
        assertEquals(
          appliedAfter,
          applied,
          clue = "a substituted plan token was applied; the body binding is gone"
        )
      }
    }
  }

  test("aBodiedRequestWithNoPrincipalIsRefused") {
    val request = planRequest(Topic)

    resource().use { (server, stubs) =>
      for {
        response <- basicRequest
          .post(uri"${uri(planPath)}")
          .header(kui.contracts.HttpHeaders.Csrf, Csrf)
          .header("Content-Type", "application/json")
          .body(new String(bytesOf(request), StandardCharsets.UTF_8))
          .send(server.backend)
        planned <- stubs.reset.planned.get
      } yield {
        assertEquals(response.code, StatusCode.Unauthorized)
        assertEquals(planned, List.empty)
      }
    }
  }
}
