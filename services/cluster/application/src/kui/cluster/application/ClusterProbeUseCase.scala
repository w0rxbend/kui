package kui.cluster.application

import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.domain.{ClusterProfile, Connectivity, ConnectivityProbe}
import kui.kernel.error.KuiError

/** "Can KUI reach this cluster with these settings?", for a cluster nobody has saved yet.
  *
  * ==Why this is a use case and not a route calling the port==
  *
  * The port is `ConnectivityProbe`, which has existed, compiled and had a passing suite since M1 and was
  * constructed by nothing. What was missing was never the probing; it was the two lines around it — the
  * refusal that keeps a probe from being an open port scanner, and the log line that says a probe happened.
  *
  * ==The refusal==
  *
  * A "test connection" endpoint takes an address from a caller and opens a connection to it. That is a
  * request-forgery primitive if anyone may call it: an unauthenticated caller could use KUI as a scanner of
  * whatever KUI's network can reach, and read the answers off the three verdicts. So it requires the same
  * permission as the write it precedes, and the route checks that before this use case is reached. Nothing
  * here weakens that; it is recorded here because a reader of this file needs to know it happens.
  *
  * ==What it never returns==
  *
  * The verdict's `detail` is one of a fixed set of sentences chosen by the adapter. No exception message, no
  * host, no bootstrap string and no JAAS configuration reaches a caller — for the ordinary reason that
  * failures leak configuration, and for the specific one that a caller who may test connections is not
  * necessarily a caller who may read the credentials of the cluster they mistyped an address for.
  */
trait ClusterProbeUseCase[F[_]] {

  /** Opens one bounded connection using `profile` and reports what happened. Stores nothing. */
  def probe(profile: ClusterProfile): F[Either[KuiError, Connectivity]]
}

object ClusterProbeUseCase {

  val Operation: String = "kui.cluster.probe"

  def make[F[_]: Concurrent](
      probes: ConnectivityProbe[F],
      logger: StructuredLogger[F]
  ): ClusterProbeUseCase[F] =
    new ClusterProbeUseCase[F] {

      private val context: Map[String, String] =
        Map("service.name" -> ClusterService.Id.value, "operation" -> Operation)

      def probe(profile: ClusterProfile): F[Either[KuiError, Connectivity]] =
        probes
          .probe(profile)
          .flatMap(verdict =>
            logger
              .info(context ++ Map("cluster.id" -> profile.id.value, "probe.verdict" -> nameOf(verdict)))(
                "a connection test was run"
              )
              .as(verdict.asRight[KuiError])
          )

      // The adapter already turns every failure into a verdict — that is its whole contract — so there is
      // no error branch to handle here. The `Either` is in the signature anyway because a route that had to
      // change shape the first time this use case could fail would be a route with two callers to find.
    }

  /** The verdict's name for a log field. The wire spelling is the api module's, not this one's. */
  def nameOf(verdict: Connectivity): String = verdict match {
    case Connectivity.Reachable => "reachable"
    case Connectivity.AuthenticationFailed(_) => "authentication-failed"
    case Connectivity.Unreachable(_) => "unreachable"
  }
}
