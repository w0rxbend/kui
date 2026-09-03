package kui.build

import munit.FunSuite

/** Rule A10's allow-list, one entry at a time.
  *
  * The list is the rule: `org.apache.kafka` may be imported in five named places, plus any service's
  * `infrastructure` and `app` modules. Asserting the whole set in one test would still pass if somebody
  * replaced it wholesale, and the risk this exists to manage (M1 DEVPLAN R-7) is precisely that the list gets
  * widened quietly — one edge at a time, each reasonable on its own. A sixth entry has to be argued in the
  * commit that adds it, and deleting one has to break a test with a name that says what was deleted.
  */
final class KafkaAllowListSuite extends FunSuite {

  private def isLegal(id: String): Unit = {
    val withModule = ArchitectureRules.check(List(ModuleFacts(id, Set("libs.kafka"), Set.empty)))
    val withLibrary =
      ArchitectureRules.check(List(ModuleFacts(id, Set.empty, Set("org.apache.kafka:kafka-clients"))))

    assertEquals(withModule.filter(_.rule == "A10"), Nil, s"$id was refused libs.kafka")
    assertEquals(withLibrary.filter(_.rule == "A10"), Nil, s"$id was refused kafka-clients")
  }

  test("libs.kafka may hold a Kafka client") { isLegal("libs.kafka") }

  test("libs.kafkaAuth may hold a Kafka client") { isLegal("libs.kafkaAuth") }

  test("libs.config may hold a Kafka client") { isLegal("libs.config") }

  test("libs.testkit may hold a Kafka client") { isLegal("libs.testkit") }

  test("apps.allinone may hold a Kafka client") { isLegal("apps.allinone") }

  test("any service's infrastructure module may hold a Kafka client") {
    isLegal("services.cluster.infrastructure")
    // Matched structurally rather than listed, so a service that does not exist yet is covered.
    isLegal("services.schema.infrastructure")
  }

  test("any service's app module may hold a Kafka client") {
    isLegal("services.cluster.app")
    isLegal("services.connect.app")
  }

  test("a test module inherits its parent's permission") {
    isLegal("libs.kafka.test")
    isLegal("services.cluster.infrastructure.test")
    isLegal("libs.testkit.test")
  }

  test("the end-to-end suite is not on the list") {
    // D-1: `e2e` drives Compose through the command line and asserts over HTTP. If a later milestone
    // needs a broker client there, adding the sixth entry is the argument this rule is built to force.
    val violations = ArchitectureRules.check(List(ModuleFacts("e2e", Set("libs.kafka"), Set.empty)))

    assertEquals(violations.map(_.rule), List("A10"))
  }
}
