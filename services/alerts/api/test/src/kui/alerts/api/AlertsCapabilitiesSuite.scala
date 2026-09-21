package kui.alerts.api

import cats.effect.IO
import munit.CatsEffectSuite
import sttp.model.MediaType
import sttp.tapir.{EndpointIO, EndpointOutput}

import kui.alerts.contract.{AlertsEndpoints, AlertsStreamEndpoint}
import kui.contracts.KuiEndpoint
import kui.contracts.capability.CapabilityState

/** What this service says about itself, and that its stream is a stream. */
final class AlertsCapabilitiesSuite extends CatsEffectSuite {

  import AlertsTestServer.*

  test("every configured cluster is available, because alerting has no on-switch") {
    // `kui.alerts` has no `enabled` key, and `AlertsConfig`'s own scaladoc says why: every rule reads a
    // fact this product already measures. A `not_configured` row here would be a feature that is off in a
    // deployment where nothing can turn it off.
    AlertsCapabilities.make[IO](new Profiles).report.map { report =>
      assertEquals(report.keySet, Set(cluster, readOnly))
      assert(report.values.forall(_.configured))
      assert(report.values.forall(_.status == CapabilityState.Available.status))
      assert(report.values.forall(_.reason.isEmpty))
    }
  }

  test("a read-only cluster does not advertise the acknowledgement") {
    // The one place the read-only decision reaches the browser before a request is made. A browser told
    // the feature was there would draw an enabled button whose only possible outcome is a refusal.
    AlertsCapabilities.make[IO](new Profiles).report.map { report =>
      assertEquals(report(cluster).features, List("alerts.events", "alerts.acknowledge"))
      assertEquals(report(readOnly).features, List("alerts.events"))
    }
  }

  test("the feature names are the endpoints' own, so one string is read on both sides") {
    // Literals, not the expression `Features` is defined as. `assertEquals(Features,
    // AlertsEndpoints.all.flatMap(_.info.name))` restates the definition and cannot fail for any change
    // to either side; these are the two strings the browser's capability check compares against, and a
    // name retyped in an endpoint's `.name(...)` is exactly the drift a mirror is supposed to catch.
    assertEquals(AlertsCapabilities.Features, List("alerts.events", "alerts.acknowledge"))
    assertEquals(AlertsCapabilities.AcknowledgeFeature, "alerts.acknowledge")

    // And every published endpoint carries a name. A nameless one drops silently out of the roster the
    // browser reads, rather than failing anything.
    assertEquals(AlertsCapabilities.Features.size, AlertsEndpoints.all.size)
  }

  test("the capability document names this service by the id the gateway is configured with") {
    AlertsApi
      .capabilityDocument[IO](AlertsCapabilities.make[IO](new Profiles), null)
      .attempt
      .map {
        case Right(document) => assertEquals(document.service.value, "alerts")
        case Left(failure) => fail(s"the capability document raised: $failure")
      }
  }

  test("the composition root's route list serves the change stream, not just the two JSON routes") {
    // `AlertsRoutesSuite` drives the JSON half through Tapir's stub backend, which carries no streaming
    // capability. This is the case that stops the stream being dropped from `AlertsApi.routes` with every
    // other suite still green — the shape of orphan this project has shipped once per wave for three waves.
    AlertsTestServer.compositionRoutes.map { routes =>
      assert(
        routes.exists(_.showPathTemplate().contains("/alerts/stream")),
        clue = routes.map(_.showPathTemplate())
      )
      assertEquals(routes.map(_.showPathTemplate()).distinct.size, routes.size)
    }
  }

  test("the stream is served at exactly the address the browser will be given") {
    assertEquals(
      AlertsStreamEndpoint.endpoint[IO].showPathTemplate().takeWhile(_ != '?'),
      "/internal/v1/clusters/{clusterId}/alerts/stream"
    )
  }

  test("the stream carries the signed principal, like every other internal endpoint") {
    val headers = leaves(AlertsStreamEndpoint.endpoint[IO].securityInput).collect {
      case EndpointIO.Header(name, _, _) => name
    }

    assertEquals(headers, List(KuiEndpoint.PrincipalHeader))
  }

  test("the stream's body is an event stream, not JSON") {
    // A client opens this with an EventSource, which refuses anything but text/event-stream.
    val mediaTypes = outputLeaves(AlertsStreamEndpoint.endpoint[IO].output).collect {
      case body: EndpointIO.StreamBodyWrapper[?, ?] => body.wrapped.codec.format.mediaType
    }

    assertEquals(mediaTypes, List(MediaType.TextEventStream))
  }

  test("the stream's event name is the DTO's, so the encoder and the listener cannot drift") {
    assertEquals(AlertsStreamEndpoint.EventName, "alerts")
    assertEquals(AlertsStreamEndpoint.EventName, kui.alerts.contract.dto.AlertChangeDto.EventName)
  }

  private def leaves(input: sttp.tapir.EndpointInput[?]): List[sttp.tapir.EndpointInput[?]] =
    input match {
      case sttp.tapir.EndpointInput.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
      case EndpointIO.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
      case sttp.tapir.EndpointInput.MappedPair(wrapped, _) => leaves(wrapped)
      case EndpointIO.MappedPair(wrapped, _) => leaves(wrapped)
      case leaf => List(leaf)
    }

  private def outputLeaves(output: EndpointOutput[?]): List[EndpointOutput[?]] =
    output match {
      case EndpointOutput.Pair(left, right, _, _) => outputLeaves(left) ++ outputLeaves(right)
      case EndpointIO.Pair(left, right, _, _) => outputLeaves(left) ++ outputLeaves(right)
      case EndpointOutput.MappedPair(wrapped, _) => outputLeaves(wrapped)
      case EndpointIO.MappedPair(wrapped, _) => outputLeaves(wrapped)
      case leaf => List(leaf)
    }
}
