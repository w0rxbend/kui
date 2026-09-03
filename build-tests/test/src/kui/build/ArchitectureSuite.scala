package kui.build

import munit.FunSuite

/** The rule table of ADR-041, one synthetic module graph per rule.
  *
  * These are unit tests over `ArchitectureRules.check`, not over the real build. That split is on
  * purpose: `./mill checkArchitecture` feeds the real module graph into the same function, so this
  * suite can prove each rule fires — and, just as importantly, that it does not fire on the cases
  * that are legal — without anyone having to add a forbidden dependency to the actual project.
  */
final class ArchitectureSuite extends FunSuite {

  private def module(id: String, moduleDeps: String*): ModuleFacts =
    ModuleFacts(id, moduleDeps.toSet, Set.empty)

  private def moduleWithLibs(id: String, mvnDeps: String*): ModuleFacts =
    ModuleFacts(id, Set.empty, mvnDeps.toSet)

  /** Asserts that one edge breaks exactly the named rules, and that nothing else in the graph breaks
    * anything. Two rules on one edge is right when they say genuinely different things — A8 says why the
    * gateway in particular holds no Kafka client, A10 says where one may live at all.
    */
  private def expectViolations(from: String, to: String)(rules: String*)(modules: ModuleFacts*): Unit = {
    val violations = ArchitectureRules.check(modules.toList)

    assertEquals(violations.map(v => (v.rule, v.module, v.offendingDep)).sorted, rules.sorted.toList.map(rule => (rule, from, to)))
  }

  /** Asserts that a graph produces exactly one violation, of the expected rule and edge. */
  private def expectOneViolation(
      rule: String,
      from: String,
      to: String
  )(modules: ModuleFacts*): Unit = {
    val violations = ArchitectureRules.check(modules.toList)
    assertEquals(
      violations.map(v => (v.rule, v.module, v.offendingDep)),
      List((rule, from, to)),
      clue = violations.map(_.message).mkString("\n")
    )
    assert(violations.forall(_.why.nonEmpty), "every violation must explain why the rule exists")
  }

  private def expectNoViolations(modules: ModuleFacts*): Unit = {
    val violations = ArchitectureRules.check(modules.toList)
    assertEquals(violations.map(_.message), Nil)
  }

  test("a clean graph produces no violations") {
    expectNoViolations(
      ModuleFacts("libs.kernel.jvm", Set.empty, Set("org.typelevel:cats-core")),
      ModuleFacts("services.cluster.domain", Set("libs.kernel.jvm"), Set("org.typelevel:cats-core")),
      module("services.cluster.application", "services.cluster.domain"),
      module("services.cluster.contract", "libs.contractsCore.jvm"),
      module("services.cluster.api", "services.cluster.application", "services.cluster.contract"),
      module("services.gateway.application", "libs.contractsCore.jvm", "libs.http"),
      module("services.gateway.api", "services.cluster.contract")
    )
  }

  test("A1: a domain module may not depend on anything but libs.kernel and cats-core") {
    expectOneViolation("A1", "services.cluster.domain", "libs.http")(
      module("services.cluster.domain", "libs.kernel.jvm", "libs.http")
    )
  }

  test("A1: a domain module may not pull in a library other than cats-core") {
    expectOneViolation("A1", "services.topic.domain", "io.circe:circe-core")(
      moduleWithLibs("services.topic.domain", "org.typelevel:cats-core", "io.circe:circe-core")
    )
  }

  test("A2: a contract module may not depend on a domain module") {
    expectOneViolation("A2", "services.cluster.contract", "services.cluster.domain")(
      module("services.cluster.contract", "services.cluster.domain")
    )
  }

  test("A2: a contract module may not depend on an application module") {
    expectOneViolation("A2", "services.topic.contract", "services.topic.application")(
      module("services.topic.contract", "services.topic.application")
    )
  }

  test("A3: a domain-owning service's application may not depend on the wire") {
    expectOneViolation("A3", "services.cluster.application", "libs.contractsCore.jvm")(
      module("services.cluster.domain"),
      module("services.cluster.application", "services.cluster.domain", "libs.contractsCore.jvm")
    )
  }

  test("A3: a domain-owning service's application may not depend on its own infrastructure") {
    // Since M1 this edge also breaks A9, which forbids it for every service rather than only for one
    // that owns a domain. Both fire, and both are reported, because removing the clause from A3 would
    // mean rewriting a rule whose tests are the record that it behaves as written.
    expectViolations("services.topic.application", "services.topic.infrastructure")("A3", "A9")(
      module("services.topic.domain"),
      module("services.topic.application", "services.topic.infrastructure")
    )
  }

  test("A3: a domain-owning service's application may not pull in tapir or circe") {
    expectOneViolation("A3", "services.topic.application", "com.softwaremill.sttp.tapir:tapir-core")(
      module("services.topic.domain"),
      moduleWithLibs("services.topic.application", "com.softwaremill.sttp.tapir:tapir-core")
    )
  }

  // ADR-041 Amendment 1. The pair below is deliberately one test: the scoping of A3 cannot be
  // silently widened or narrowed later without one half of it failing.
  test("A3 is scoped to services that own a domain: the gateway is in, a domain-owning service is out") {
    val gatewayEdge =
      module("services.gateway.application", "libs.contractsCore.jvm", "libs.http")
    val domainOwningEdge =
      module("services.cluster.application", "libs.contractsCore.jvm", "libs.http")

    // The gateway owns no domain module, and the wire is its subject matter, so these are legal.
    expectNoViolations(gatewayEdge)

    // The very same edges, from a service that does own a domain, are not.
    val violations = ArchitectureRules.check(List(module("services.cluster.domain"), domainOwningEdge))
    assertEquals(violations.map(v => (v.rule, v.offendingDep)).sorted, List("A3" -> "libs.contractsCore.jvm", "A3" -> "libs.http").sorted)
  }

  test("A3 starts applying to the gateway the day the gateway grows a domain module") {
    val violations = ArchitectureRules.check(
      List(
        module("services.gateway.domain"),
        module("services.gateway.application", "libs.http")
      )
    )
    assertEquals(violations.map(_.rule), List("A3"))
  }

  test("A4: the gateway may not reach into another service's application layer") {
    expectOneViolation("A4", "services.gateway.application", "services.cluster.application")(
      module("services.gateway.application", "services.cluster.application")
    )
  }

  test("A4: the gateway may depend on another service's contract module") {
    expectNoViolations(
      module("services.gateway.api", "services.cluster.contract", "services.topic.contract")
    )
  }

  test("A5: a library may not depend on a service") {
    expectOneViolation("A5", "libs.http", "services.cluster.contract")(
      module("libs.http", "services.cluster.contract")
    )
  }

  test("A5: a library may not depend on the frontend") {
    expectOneViolation("A5", "libs.kernel.jvm", "frontend.uiKernel")(
      module("libs.kernel.jvm", "frontend.uiKernel")
    )
  }

  test("A6: a cross-compiled core module may not pull in a JVM-only library") {
    expectOneViolation("A6", "libs.contractsCore.js", "ch.qos.logback:logback-classic")(
      moduleWithLibs("libs.contractsCore.js", "io.circe:circe-core", "ch.qos.logback:logback-classic")
    )
  }

  test("A6: the JVM half of a cross-compiled core module may pull in a JVM-only library") {
    // libs/security-core keeps its nimbus JWS adapter in src-jvm, which only the .jvm module compiles.
    expectNoViolations(
      moduleWithLibs("libs.securityCore.jvm", "io.circe:circe-core", "com.nimbusds:nimbus-jose-jwt"),
      moduleWithLibs("libs.securityCore.js", "io.circe:circe-core")
    )
  }

  test("A6: the browser half of that same module may not") {
    expectOneViolation("A6", "libs.securityCore.js", "com.nimbusds:nimbus-jose-jwt")(
      moduleWithLibs("libs.securityCore.js", "com.nimbusds:nimbus-jose-jwt")
    )
  }

  test("A6: a JVM-only module may pull in a JVM-only library") {
    expectNoViolations(moduleWithLibs("libs.observability", "ch.qos.logback:logback-classic"))
  }

  test("A8: the gateway may not depend on a Kafka library") {
    expectViolations("services.gateway.application", "org.typelevel:fs2-kafka")("A10", "A8")(
      moduleWithLibs("services.gateway.application", "org.typelevel:fs2-kafka")
    )
  }

  test("A8: the gateway may not depend on the Kafka module") {
    expectViolations("services.gateway.api", "libs.kafka")("A10", "A8")(
      module("services.gateway.api", "libs.kafka")
    )
  }

  test("A8: a service that is not the gateway may hold a Kafka client") {
    expectNoViolations(moduleWithLibs("services.topic.infrastructure", "org.typelevel:fs2-kafka"))
  }

  test("the layer rules do not apply to a test module, which needs its own subject and MUnit") {
    expectNoViolations(
      ModuleFacts(
        "services.cluster.domain.test",
        Set("services.cluster.domain", "libs.testkit.jvm"),
        Set("org.scalameta:munit")
      )
    )
  }

  test("a test module is still held to the rules that are not about layers") {
    expectOneViolation("A4", "services.gateway.api.test", "services.cluster.domain")(
      module("services.gateway.api.test", "services.cluster.domain")
    )
  }

  test("a violation message names the rule, both modules and the reason") {
    val violation = ArchitectureRules
      .check(List(module("services.gateway.application", "services.cluster.application")))
      .head

    assert(violation.message.contains("A4"), violation.message)
    assert(violation.message.contains("services.gateway.application"), violation.message)
    assert(violation.message.contains("services.cluster.application"), violation.message)
    assert(violation.message.contains("published contract"), violation.message)
  }

  // -------------------------------------------------------------------------------------------
  // A9 — nothing inside a service points at that service's own adapters
  // -------------------------------------------------------------------------------------------

  test("A9: an application module may not depend on its own service's infrastructure") {
    expectOneViolation("A9", "services.topic.application", "services.topic.infrastructure")(
      module("services.topic.application", "services.topic.infrastructure")
    )
  }

  test("A9: a contract module may not depend on its own service's infrastructure") {
    expectOneViolation("A9", "services.topic.contract", "services.topic.infrastructure")(
      module("services.topic.contract", "services.topic.infrastructure")
    )
  }

  test("A9: an api module may not depend on its own service's infrastructure") {
    expectOneViolation("A9", "services.topic.api", "services.topic.infrastructure")(
      module("services.topic.api", "services.topic.infrastructure")
    )
  }

  test("A9: an app module may depend on its own service's infrastructure") {
    assertEquals(
      ArchitectureRules.check(List(module("services.topic.app", "services.topic.infrastructure"))),
      Nil
    )
  }

  test("A9: a module may not be reported twice for one edge that A1 also forbids") {
    // A1 already says a domain module may depend on nothing but libs.kernel. If A9 listed `domain`
    // among the layers it constrains, this one edge would produce two violations saying the same
    // thing, and whoever tripped it would fix it twice to find out it was one problem.
    val violations =
      ArchitectureRules.check(List(module("services.cluster.domain", "services.cluster.infrastructure")))

    assertEquals(violations.map(_.rule), List("A1"))
  }

  // -------------------------------------------------------------------------------------------
  // A10 — a Kafka client is importable only where it is adapted
  // -------------------------------------------------------------------------------------------

  test("A10: a module outside the allow-list may not depend on libs.kafka") {
    expectOneViolation("A10", "services.topic.api", "libs.kafka")(
      module("services.topic.api", "libs.kafka")
    )
  }

  test("A10: a module outside the allow-list may not pull in kafka-clients or fs2-kafka") {
    expectOneViolation("A10", "libs.http", "org.apache.kafka:kafka-clients")(
      moduleWithLibs("libs.http", "org.apache.kafka:kafka-clients")
    )
    expectOneViolation("A10", "libs.http", "org.typelevel:fs2-kafka")(
      moduleWithLibs("libs.http", "org.typelevel:fs2-kafka")
    )
  }

  test("A10: a gateway module with a Kafka dependency fails both A8 and A10") {
    // Two violations for one edge, and here that is right rather than noisy: A8 says why the
    // *gateway* in particular holds no client (ADR-004's central argument) and A10 says where a
    // Kafka client may live at all. They are different answers to different questions.
    val violations =
      ArchitectureRules.check(List(module("services.gateway.api", "libs.kafka")))

    assertEquals(violations.map(_.rule).sorted, List("A10", "A8"))
  }

  test("A10: a module with no Kafka dependency at all is legal") {
    assertEquals(ArchitectureRules.check(List(module("services.topic.api", "libs.http"))), Nil)
  }
}
