package kui.cluster.api

import cats.effect.IO
import munit.FunSuite
import sttp.model.MediaType
import sttp.tapir.{EndpointIO, EndpointInput, EndpointOutput}

import kui.contracts.KuiEndpoint

/** That the change stream is at the address ADR-036 promised and carries an event-stream body.
  *
  * A consumer of this stream is another KUI service, written in a later milestone against this address. It
  * cannot be tested against a live subscriber yet, so what is asserted is the part a later milestone will
  * assume: the path, the media type and the signed principal.
  */
final class ClusterStreamEndpointSuite extends FunSuite {

  private val streamed = ClusterStreamEndpoint.endpoint[IO]

  private def leaves(input: EndpointInput[?]): List[EndpointInput[?]] =
    input match {
      case EndpointInput.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
      case EndpointIO.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
      case EndpointInput.MappedPair(wrapped, _) => leaves(wrapped)
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

  test("the stream is served at exactly StreamPath") {
    assertEquals(streamed.showPathTemplate().takeWhile(_ != '?'), ClusterStreamEndpoint.StreamPath)
    assertEquals(ClusterStreamEndpoint.StreamPath, "/internal/v1/clusters/stream")
  }

  test("the stream carries the signed principal, like every other internal endpoint") {
    val headers = leaves(streamed.securityInput).collect { case EndpointIO.Header(name, _, _) => name }

    assertEquals(headers, List(KuiEndpoint.PrincipalHeader))
  }

  test("the body is an event stream, not JSON") {
    // A client opens this with an EventSource, which refuses anything but text/event-stream.
    val mediaTypes = outputLeaves(streamed.output).collect {
      case body: EndpointIO.StreamBodyWrapper[?, ?] => body.wrapped.codec.format.mediaType
    }

    assertEquals(mediaTypes, List(MediaType.TextEventStream))
  }

  test("the stream is documented, so CLAPI-010's merge can publish it") {
    // The cross-compiled contract cannot describe an fs2 body, so this list is the only way the stream
    // reaches the OpenAPI document at all.
    assertEquals(ClusterStreamEndpoint.endpoints[IO].flatMap(_.info.name), List("cluster.stream"))
    assert(streamed.info.summary.isDefined)
    assertEquals(streamed.info.tags.toList, List("cluster"))
  }
}
