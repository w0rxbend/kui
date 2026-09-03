package kui.e2e

import scala.concurrent.duration.DurationInt

/** Slow, not dead: the harder failure, and the one HTTP-003's circuit breaker exists for.
  *
  * A stopped container refuses connections immediately, and "immediately" is easy to survive. A
  * *paused* one accepts the connection and then answers nothing at all, holding it open until the
  * caller gives up. That is what a wedged service, an exhausted thread pool or a stalled garbage
  * collection looks like from the gateway's side, and it is the failure that takes systems down: a
  * caller with no timeout and no bulkhead accumulates blocked requests until it has none left, and
  * one sick service becomes a dead product.
  *
  * So the assertion here is not only "the entry goes dim". It is that **the gateway keeps answering
  * its own endpoints quickly while an upstream is hanging** — which is the whole point of the
  * timeout, the bulkhead and the breaker, and something no unit test with a fake clock can
  * demonstrate against a real socket.
  */
final class CircuitBreakerSuite extends ComposeE2ESuite {

  /** The readiness interval `docker-compose.e2e.yml` configures. */
  private val readinessInterval = 3.seconds

  /** The upstream call budget from `kui.yaml` (`kui.gateway.services.cluster.timeout`). The gateway's
    * own endpoints must stay well inside it while an upstream hangs; if they did not, the timeout
    * would be protecting nothing.
    */
  private val upstreamTimeout = 10.seconds

  test("a hanging service dims the entry instead of hanging the UI") {
    val page = shell.open("/ui/")

    waitForCondition("the Clusters entry to be normal before the service is paused", readinessInterval * 3) {
      page.navigation.entry("clusters").exists(entry => !entry.dimmed)
    }

    stack().pauseService("kui-cluster")
    try {
      // A paused container answers nothing, so the gateway's readiness probe can only fail by timing
      // out — one whole upstream timeout per attempt. The deadline allows for several of those plus
      // the readiness interval between them, because the breaker only opens after repeated failures.
      waitForCondition(
        "the Clusters entry to be dimmed while the service is paused rather than the UI hanging",
        upstreamTimeout * 6
      ) {
        page.navigation.entry("clusters").exists(_.dimmed)
      }

      val capability = Capabilities
        .of(stack().baseUrl, "cluster")
        .getOrElse(failWithArtifacts("the capability document has no entry for the cluster service"))

      // Either reason is a correct diagnosis of a hanging upstream — the first call times out, and
      // once enough have, the breaker opens and stops calling at all. Which one is observed depends
      // on how many probes have been attempted by the time the assertion looks, so pinning exactly
      // one of them would make this test fail for a reason that is not a defect.
      assert(
        Set("UPSTREAM_TIMEOUT", "CIRCUIT_OPEN").contains(capability.reason.getOrElse("")),
        s"a hanging upstream was reported as '${capability.reason.getOrElse("nothing")}', which " +
          "tells an operator nothing about whether to wait or to go and look"
      )

      // The point of the whole mechanism: the gateway is still fast for everything that does not
      // depend on the sick service.
      val startedAt = System.nanoTime()
      val (status, _) = Http.get(s"${stack().baseUrl}/api/v1/info")
      val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

      assertEquals(status, 200, "the gateway stopped serving its own endpoints while an upstream hung")
      assert(
        elapsedMillis < upstreamTimeout.toMillis,
        s"the gateway took ${elapsedMillis}ms to answer its own endpoint while an upstream was " +
          s"hanging; the ${upstreamTimeout} upstream budget is not isolating anything"
      )
    } finally stack().unpauseService("kui-cluster")
  }

  test("unpausing the service brings the entry back") {
    val page = shell

    waitForCondition("the Clusters entry to recover after the service was unpaused", readinessInterval * 8) {
      page.navigation.entry("clusters").exists(entry => !entry.dimmed)
    }
  }
}
