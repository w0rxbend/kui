package kui.gateway.application.topic

import cats.effect.kernel.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

import kui.consumer.contract.ConsumerEndpoints
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.gateway.application.topic.TopicOverviewUseCase.SectionSource
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, TopicName}

/** The topic page's Consumers tab, filled from the consumer service.
  *
  * ==Why this file exists==
  *
  * `TopicOverviewUseCase` has always had the extension point: a section with no registered `SectionSource` is
  * reported `NotConfigured`, which the browser renders as "this deployment has no such thing" rather than as
  * an error. That is the right answer for a section nobody has built.
  *
  * It was also, until this file, the answer a fully configured deployment got for `consumerGroups`. The
  * consumer service was deployed, its capability was reported `available`, its `forTopic` endpoint answered
  * correctly when called directly — and the tab said the deployment did not track consumer groups, because
  * nothing had ever put a source in the map. The section was designed, contracted, tested with a fabricated
  * source, rendered by a real panel in the browser, and never wired. It went unnoticed because the tab itself
  * only appeared when the consumers feature happened to have been downloaded already, so hardly anybody saw
  * the panel behind it.
  *
  * ==Why the rows travel as `Json`==
  *
  * The section's shape belongs to the consumer service, and the gateway must not declare it (ADR-012 §D13):
  * `ui-topics` never learns a route or a type of this service's, and `ui-consumers` decodes these rows with
  * the contract's own `TopicConsumerRowDto`. So the gateway carries them across without opening them, which
  * is exactly what `SectionSource` promises.
  *
  * A failure here fills this one section with `Unavailable` and leaves the other four alone — the tab says
  * the consumer service could not answer, and the topic's own overview is unaffected.
  */
object ConsumerGroupsSource {

  def apply[F[_]: Async](consumers: ServiceClient[F]): SectionSource[F] =
    new SectionSource[F] {
      def fetch(
          cluster: ClusterId,
          topic: TopicName,
          context: CallContext
      ): F[Either[KuiError, List[Json]]] =
        consumers
          .call(ConsumerEndpoints.forTopic, (cluster, topic))(context)
          .map(_.map(_.rows.map(_.asJson)))
    }

  /** The map entry, or none when this deployment has no consumer service.
    *
    * Written as a function rather than inline at the call site so that "the Consumers tab is wired" is one
    * named thing a test can assert about, instead of a fold buried in a composition root.
    */
  def sources[F[_]: Async](consumers: Option[ServiceClient[F]]): Map[String, SectionSource[F]] =
    consumers
      .map(client => kui.gateway.contract.dto.TopicOverviewDto.ConsumerGroupsSection -> apply[F](client))
      .toMap
}
