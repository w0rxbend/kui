package kui.kafka

import java.util.concurrent.atomic.AtomicLong

import cats.effect.Sync

import kui.kafka.auth.ClientPurpose
import kui.kernel.ClusterId

/** The `client.id` KUI presents to a broker: `kui-admin-<clusterId>-<seq>`.
  *
  * Unique per *client*, not per cluster. A broker's request log and its quota accounting are both keyed by
  * `client.id` (`research/kafka/admin-capabilities.md` §0), so two clients sharing one make the log
  * unreadable and the quota unattributable. The sequence number comes from a process-wide counter, which
  * means a client rebuilt by invalidation is distinguishable in the broker log from the one it replaced — and
  * the moment a client is rebuilt is exactly the moment somebody is reading that log.
  */
opaque type ClientId = String

object ClientId {

  private val counter: AtomicLong = new AtomicLong(0L)

  def next[F[_]: Sync](purpose: ClientPurpose, cluster: ClusterId): F[ClientId] =
    Sync[F].delay(s"${purpose.prefix}-${cluster.value}-${counter.incrementAndGet()}")

  def unsafe(raw: String): ClientId = raw

  extension (id: ClientId) def value: String = id

  given CanEqual[ClientId, ClientId] = CanEqual.derived
}
