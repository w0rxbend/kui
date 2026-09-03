package kui.build

/** What one module declares about itself, reduced to the only two things the layering rules care about: which
  * other modules it depends on, and which third-party libraries it pulls in.
  *
  * @param id
  *   the Mill module id, dot-separated, e.g. `services.cluster.domain` or `libs.kernel.jvm`
  * @param moduleDeps
  *   ids of other modules in this build that this module depends on
  * @param mvnDeps
  *   third-party coordinates as `group:artifact`, with the version and any Scala suffix stripped, e.g.
  *   `org.typelevel:cats-core`
  */
final case class ModuleFacts(id: String, moduleDeps: Set[String], mvnDeps: Set[String])

/** One broken rule.
  *
  * `why` is not decoration. Someone trips these rules while doing something that seemed reasonable, and a
  * bare "forbidden" tells them nothing about what to do instead.
  */
final case class Violation(rule: String, module: String, offendingDep: String, why: String) {
  def message: String = s"[$rule] $module -> $offendingDep: $why"
}

/** The layering rules of ADR-041, as a function from the module graph to the edges that break them.
  *
  * The rules are checked against declared dependencies rather than against compiled bytecode. That is
  * deliberate (ADR-041 §3): it is fast enough to run on every build, it cannot produce a false positive, and
  * it fails at the moment someone adds the dependency, which is the moment undoing it is cheapest.
  */
object ArchitectureRules {

  /** Libraries that exist only on the JVM. A module whose sources are shared with Scala.js must not depend on
    * any of them, because the shared half would then be uncompilable for the browser.
    *
    * The list is deliberately short and explicit: it names the JVM-only libraries KUI actually uses (ADR-041
    * rule A6). Add to it when a new JVM-only dependency enters `DEPENDENCY_MATRIX.md`.
    */
  private val JvmOnlyArtifacts: Set[String] = Set(
    "org.apache.kafka:kafka-clients",
    "org.typelevel:fs2-kafka",
    "com.github.fd4s:fs2-kafka",
    "ch.qos.logback:logback-classic",
    "net.logstash.logback:logstash-logback-encoder",
    "org.slf4j:slf4j-api",
    "com.softwaremill.sttp.tapir:tapir-netty-server-cats",
    "io.opentelemetry:opentelemetry-sdk",
    "io.opentelemetry:opentelemetry-sdk-extension-autoconfigure",
    "io.opentelemetry:opentelemetry-exporter-otlp",
    "org.typelevel:otel4s-oteljava",
    "com.dimafeng:testcontainers-scala-munit",
    "io.confluent:kafka-schema-registry-client",
    "com.nimbusds:nimbus-jose-jwt",
    "com.unboundid:unboundid-ldapsdk"
  )

  /** Kafka client libraries. The gateway may hold none of them (ADR-004 §3, rule A8). */
  private val KafkaArtifacts: Set[String] = Set(
    "org.typelevel:fs2-kafka",
    "com.github.fd4s:fs2-kafka",
    "org.apache.kafka:kafka-clients"
  )

  private val KafkaModules: Set[String] = Set("libs.kafka", "libs.kafkaAuth")

  /** The wire: transport, serialisation and the modules built on them. A domain-owning service's
    * `application` may touch none of it (rule A3).
    */
  private val WireModules: Set[String] = Set("libs.http", "libs.contractsCore")

  private val WireGroups: Set[String] = Set("com.softwaremill.sttp.tapir", "io.circe")

  /** Layers that belong to a service and are private to it: the gateway may not reach past a service's
    * published `contract` into any of these (rule A4).
    */
  private val PrivateServiceLayers: Set[String] =
    Set("domain", "application", "infrastructure", "api", "app")

  private val SharedCoreModules: Set[String] =
    Set("libs.kernel", "libs.contractsCore", "libs.securityCore")

  /** `services.cluster.domain` and `services.cluster.domain.jvm` both belong to service `cluster`, layer
    * `domain`. Anything that is not a service module gives `None`.
    */
  private def serviceLayer(id: String): Option[(String, String)] =
    id.split('.').toList match {
      case "services" :: service :: layer :: _ => Some((service, layer))
      case _ => None
    }

  /** `libs.kernel.jvm` and `libs.kernel.js` are both the `libs.kernel` module. */
  private def coreModuleOf(id: String): String = {
    val parts = id.split('.').toList
    if parts.length > 2 && (parts.last == "jvm" || parts.last == "js") then {
      parts.init.mkString(".")
    } else {
      id
    }
  }

  private def isUnder(id: String, root: String): Boolean = id == root || id.startsWith(s"$root.")

  private def group(coordinate: String): String = coordinate.takeWhile(_ != ':')

  /** Every violation in the graph, in rule order. An empty result means the graph is legal. */
  def check(modules: List[ModuleFacts]): List[Violation] = {
    val domainOwningServices: Set[String] =
      modules.flatMap(m => serviceLayer(m.id).collect { case (service, "domain") => service }).toSet

    modules.flatMap { module =>
      a1(module) ++
        a2(module) ++
        a3(module, domainOwningServices) ++
        a4(module) ++
        a5(module) ++
        a6(module) ++
        a8(module)
    }
  }

  /** A1 — a `domain` module holds business rules and nothing else. It may see the shared kernel and cats, so
    * that it can be understood, tested and moved without dragging a runtime behind it.
    */
  private def a1(module: ModuleFacts): List[Violation] =
    serviceLayer(module.id) match {
      case Some((_, "domain")) =>
        val why =
          "a domain module may depend only on libs.kernel and cats-core, so that business rules " +
            "stay independent of transport, persistence and framework choices (ADR-041 A1)"
        val badModules = module.moduleDeps.filterNot(dep => coreModuleOf(dep) == "libs.kernel")
        val badLibs = module.mvnDeps.filterNot(_ == "org.typelevel:cats-core")
        (badModules ++ badLibs).toList.sorted.map(dep => Violation("A1", module.id, dep, why))
      case _ => Nil
    }

  /** A2 — a `contract` module is the service's published wire shape, cross-compiled to the browser. If it
    * could see `domain` or `application`, the browser would have to compile them too.
    */
  private def a2(module: ModuleFacts): List[Violation] =
    serviceLayer(module.id) match {
      case Some((_, "contract")) =>
        val why =
          "a contract module is published to other services and to the browser, so it must not " +
            "reach into any service's domain or application layer (ADR-041 A2)"
        module.moduleDeps.toList.sorted
          .filter(dep => serviceLayer(dep).exists((_, layer) => layer == "domain" || layer == "application"))
          .map(dep => Violation("A2", module.id, dep, why))
      case _ => Nil
    }

  /** A3 — the use cases of a service that owns a `domain` must not know how anything is serialised or
    * transported. Scoped to domain-owning services on purpose: the gateway owns no domain, and the wire is
    * its subject matter, so the rule it was designed to enforce does not apply to it (ADR-041 §1a, Amendment
    * 1).
    */
  private def a3(module: ModuleFacts, domainOwningServices: Set[String]): List[Violation] =
    serviceLayer(module.id) match {
      case Some((service, "application")) if domainOwningServices.contains(service) =>
        val why =
          s"$service owns a domain module, so its application layer must own the types it returns " +
            "and let the api layer map them to the wire; it may not depend on libs.http, " +
            "libs.contracts-core, tapir, circe or an infrastructure module (ADR-041 A3)"
        val badModules = module.moduleDeps.filter { dep =>
          WireModules.contains(coreModuleOf(dep)) ||
          serviceLayer(dep).exists((_, layer) => layer == "infrastructure")
        }
        val badLibs = module.mvnDeps.filter(dep => WireGroups.contains(group(dep)))
        (badModules ++ badLibs).toList.sorted.map(dep => Violation("A3", module.id, dep, why))
      case _ => Nil
    }

  /** A4 — the gateway composes other services through their published contracts only. This is ADR-004's
    * central constraint: it is what keeps a service's internals replaceable.
    */
  private def a4(module: ModuleFacts): List[Violation] =
    if isUnder(module.id, "services.gateway") then {
      val why =
        "the gateway sees every other service only through that service's published contract " +
          "module; reaching into its internals couples the two and defeats the service split " +
          "(ADR-004 §3, ADR-041 A4)"
      module.moduleDeps.toList.sorted
        .filter { dep =>
          serviceLayer(dep).exists { (service, layer) =>
            service != "gateway" && PrivateServiceLayers.contains(layer)
          }
        }
        .map(dep => Violation("A4", module.id, dep, why))
    } else {
      Nil
    }

  /** A5 — a library is reusable precisely because it does not know who uses it. */
  private def a5(module: ModuleFacts): List[Violation] =
    if isUnder(module.id, "libs") then {
      val why =
        "a shared library must not depend on a service or on the frontend; dependencies point " +
          "towards libs, never out of them (ADR-041 A5)"
      module.moduleDeps.toList.sorted
        .filter(dep => isUnder(dep, "services") || isUnder(dep, "frontend"))
        .map(dep => Violation("A5", module.id, dep, why))
    } else {
      Nil
    }

  /** A6 — the kernel, the shared contracts and the security vocabulary are compiled for the browser as well
    * as for the JVM. A JVM-only library in their shared source set makes the browser half uncompilable, and
    * the failure surfaces far from the line that caused it.
    */
  private def a6(module: ModuleFacts): List[Violation] =
    if SharedCoreModules.contains(coreModuleOf(module.id)) then {
      val why =
        "this module is cross-compiled to the browser, so its shared source set must not depend " +
          "on a library that exists only on the JVM (ADR-041 A6)"
      module.mvnDeps.toList.sorted
        .filter(JvmOnlyArtifacts.contains)
        .map(dep => Violation("A6", module.id, dep, why))
    } else {
      Nil
    }

  /** A8 — the gateway holds no Kafka client. It is a composition layer over other services' HTTP contracts;
    * the moment it can talk to a broker directly, the service split stops meaning anything. This is the
    * constraint that matters now that A3 no longer covers the gateway.
    */
  private def a8(module: ModuleFacts): List[Violation] =
    if isUnder(module.id, "services.gateway") then {
      val why =
        "the gateway is application code over other services' contracts and holds no Kafka " +
          "client of its own (ADR-004 §3, ADR-041 A8)"
      val badModules = module.moduleDeps.filter(dep => KafkaModules.contains(coreModuleOf(dep)))
      val badLibs = module.mvnDeps.filter(KafkaArtifacts.contains)
      (badModules ++ badLibs).toList.sorted.map(dep => Violation("A8", module.id, dep, why))
    } else {
      Nil
    }
}
