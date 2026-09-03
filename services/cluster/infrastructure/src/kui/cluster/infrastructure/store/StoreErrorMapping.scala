package kui.cluster.infrastructure.store

import kui.cluster.domain.StoreHealth as DomainHealth
import kui.config.store.StoreHealth as ConfigHealth
import kui.kernel.error.{ApplicationError, FieldError, KuiError}

/** The two translations between the metadata store's vocabulary and the cluster domain's, in one file so that
  * the whole table can be read at once.
  *
  * `ConfigStore` already returns typed `KuiError` values for its own failures — a version conflict, an
  * unreachable store, a write that could not be read back — so there is no exception catching here and no
  * second error hierarchy. What is left is the health projection and the one failure this module owns itself:
  * a record whose payload does not decode.
  */
object StoreErrorMapping {

  /** Bad data, not a broken store.
    *
    * It is an `ApplicationError` deliberately: ADR-039 §6 dims a capability only for an infrastructure
    * failure, and one hand-edited record must not grey out the cluster feature for every cluster. The key is
    * in the message because it is the only thing that lets an operator find the record; the decode failure's
    * own text is included because it names the field, and it is KUI's text rather than a broker's.
    */
  def undecodable(key: String, why: String): KuiError =
    ApplicationError.Invalid(
      s"the stored record '$key' could not be read: $why",
      List(FieldError.of(key, why))
    )

  /** The store's richer health, as the three cases the domain reasons about.
    *
    * `ReadOnly` becomes `NotConfigured` and not `Degraded`: running from files is a deployment choice, and
    * per ADR-039 §2 it must never render as broken. An operator who chose the file adapter has nothing to
    * fix, and a red badge would send them looking for a fault that does not exist.
    *
    * `since` is carried through from the store rather than recomputed, which is what keeps it *sticky*: it is
    * the timestamp of the first failure of the current outage, so "degraded for forty minutes" stays readable
    * instead of resetting on every retry.
    */
  def health(raw: ConfigHealth): DomainHealth = raw match {
    case ConfigHealth.Healthy(_, _, _) => DomainHealth.Online
    case ConfigHealth.Degraded(reason, since, _, _) => DomainHealth.Degraded(reason, since)
    case ConfigHealth.ReadOnly(_, _) => DomainHealth.NotConfigured
  }
}
