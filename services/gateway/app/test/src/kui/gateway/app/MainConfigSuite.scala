package kui.gateway.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import cats.effect.unsafe.implicits.global

import kui.config.UrlPolicy
import kui.testkit.KuiSuite

/** That the gateway process can be told, deliberately, to accept a private-network upstream.
  *
  * The strict URL policy is what stops a configured URL from turning the gateway into a way of reading a
  * private network — the address `http://169.254.169.254/` is how a cloud instance hands out its own
  * credentials. It used to be hard-coded, with no switch anywhere, which meant three entirely legitimate
  * deployments could not start: the gateway and a service run as two local processes on `localhost`, an OTLP
  * collector on `http://localhost:4317`, and a Kubernetes ClusterIP such as `http://10.96.4.7:8080`. Both the
  * operations guide and the design note described a relaxation that did not exist.
  */
final class MainConfigSuite extends KuiSuite {

  private def fileNaming(url: String): String = {
    val directory = Files.createTempDirectory("kui-gateway-main")
    val file = directory.resolve("kui.yaml")
    val contents =
      s"""kui:
         |  gateway:
         |    services:
         |      cluster:
         |        url: "$url"
         |""".stripMargin
    val _ = Files.write(file, contents.getBytes(StandardCharsets.UTF_8))
    directory.toFile.deleteOnExit()
    file.toFile.deleteOnExit()
    file.toString
  }

  private def load(url: String, env: Map[String, String]) =
    Main.loadConfig(List(s"--config=${fileNaming(url)}"), env).unsafeRunSync()

  /** The whole path the environment variable travels: loader, narrowing, and the value the composition root
    * will hand to every service client.
    */
  private def configure(url: String, env: Map[String, String]) =
    Main.serviceConfig(List(s"--config=${fileNaming(url)}"), env).unsafeRunSync()

  test("a loopback upstream is refused when nothing relaxes the policy") {
    load("http://localhost:8081", Map.empty) match {
      case Right(_) => fail("a loopback upstream was accepted with no relaxation in the environment")
      case Left(errors) => assertEquals(errors.keys, List("kui.gateway.services.cluster.url"))
    }
  }

  test("KUI_ALLOW_PRIVATE_UPSTREAMS=true lets the gateway use a loopback upstream") {
    val loaded = load("http://localhost:8081", Map(UrlPolicy.AllowPrivateUpstreams -> "true"))
      .fold(errors => fail(errors.render), identity)

    assertEquals(loaded.gateway.services.keySet.map(_.value), Set("cluster"))
  }

  test("KUI_ALLOW_PRIVATE_UPSTREAMS=true also lets it use a private-network address") {
    val loaded = load("http://10.96.4.7:8080", Map(UrlPolicy.AllowPrivateUpstreams -> "true"))
      .fold(errors => fail(errors.render), identity)

    assertEquals(loaded.gateway.services.keySet.map(_.value), Set("cluster"))
  }

  test("a public upstream is accepted either way") {
    assert(load("https://cluster.example.com", Map.empty).isRight)
  }

  test("thePolicyTheLoaderUsedIsThePolicyTheProcessRunsOn") {
    // The half that was missing, and it is why the three deployments above still did not work after the
    // loader was relaxed. `ResilientBackend` re-applies the policy to every request and to every redirect,
    // so an operator's `KUI_ALLOW_PRIVATE_UPSTREAMS=true` has to reach the client and not just the loader.
    // It did not: the client fell back to `UpstreamConfig`'s strict default, and a gateway pointed at
    // `http://localhost:8081` started cleanly and then answered `KUI-UPSTREAM-UNAVAILABLE` for every call
    // with no connection ever attempted — measured in wave 6 by watching an accepting socket count zero.
    val relaxed = configure("http://localhost:8081", Map(UrlPolicy.AllowPrivateUpstreams -> "true"))
      .fold(errors => fail(errors.render), identity)

    assertEquals(relaxed.urlPolicy, UrlPolicy.Dev)
  }

  test("aDeploymentThatAsksForNothingRunsOnTheStrictPolicy") {
    // The default, asserted rather than assumed, because it is a fail-safe: the parameter carrying it has a
    // default value, and a default that silently became `Dev` would open every KUI deployment to the
    // link-local metadata address the strict policy exists to refuse.
    val strict = configure("https://cluster.example.com", Map.empty)
      .fold(errors => fail(errors.render), identity)

    assertEquals(strict.urlPolicy, UrlPolicy.Strict)
    assertEquals(GatewayServiceConfig.Default.urlPolicy, UrlPolicy.Strict)
  }
}
