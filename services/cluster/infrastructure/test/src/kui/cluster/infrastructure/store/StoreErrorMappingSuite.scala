package kui.cluster.infrastructure.store

import java.time.Instant

import kui.cluster.domain.StoreHealth as DomainHealth
import kui.config.store.StoreHealth as ConfigHealth
import kui.config.store.StoreKey
import kui.kernel.error.ErrorCode
import kui.testkit.KuiSuite

final class StoreErrorMappingSuite extends KuiSuite {

  private val at = Instant.parse("2026-09-04T09:00:00Z")

  test("healthyIsOnline") {
    assertEquals(StoreErrorMapping.health(ConfigHealth.Healthy(42L, at, Nil)), DomainHealth.Online)
  }

  test("degradedKeepsItsReasonAndItsStickySince") {
    // The `since` is the first failure of the current outage, not the most recent retry. A timestamp that
    // reset on every retry would make "degraded for forty minutes" unreadable, which is the number an
    // operator actually acts on.
    val degraded = ConfigHealth.Degraded("the store cluster is unreachable", at, 42L, Nil)

    assertEquals(
      StoreErrorMapping.health(degraded),
      DomainHealth.Degraded("the store cluster is unreachable", at)
    )
  }

  test("readOnlyIsNotConfiguredAndNeverDegraded") {
    // Running from files is a deployment choice, not a fault. Rendering it as broken would send an operator
    // looking for a problem that does not exist.
    assertEquals(
      StoreErrorMapping.health(ConfigHealth.ReadOnly("no metadata store is configured", Nil)),
      DomainHealth.NotConfigured
    )
  }

  test("unreadableKeysDoNotMakeTheStoreDegraded") {
    // One hand-edited record is bad data, not a broken store. If it degraded the health, a typo would dim
    // the cluster feature for every cluster, which is exactly what the application/infrastructure split
    // exists to prevent.
    val withBadRow = ConfigHealth.Healthy(42L, at, List(StoreKey.parse("cluster/broken").toOption.get))

    assertEquals(StoreErrorMapping.health(withBadRow), DomainHealth.Online)
  }

  test("anUndecodableRecordIsAValidationFailureAndNotAnInfrastructureOne") {
    // ADR-039 §6 dims a capability only for an infrastructure failure. Classifying a decode failure as one
    // would let a single bad record grey out the sidebar for everybody.
    val error = StoreErrorMapping.undecodable("cluster/prod", "the field 'bootstrapServers' is missing")

    assertEquals(error.code, ErrorCode.Validation)
    assert(error.message.contains("cluster/prod"), s"the message names the record: ${error.message}")
    assert(error.details.nonEmpty, "the failing key is reported as a field detail")
  }
}
