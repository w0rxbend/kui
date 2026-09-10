package kui.connect.application

import cats.effect.{IO, Ref}

import kui.connect.domain.*
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.{ClusterId, ConnectName, ConnectorName, TaskId}
import kui.security.Principal

/** The fakes the application suites build on.
  *
  * The worker records **every call it was asked to make**, refused or not, because two of the required cases
  * are about what the worker was asked rather than about what it answered — "refused before the worker is
  * called" is a statement about which of those two happened, and a stub that answers a constant cannot tell
  * them apart.
  */
object ConnectRig {

  val cluster: ClusterId = ClusterId.unsafe("prod-eu")
  val readOnlyCluster: ClusterId = ClusterId.unsafe("prod-us")
  val bare: ClusterId = ClusterId.unsafe("no-connect")

  val payments: ConnectName = ConnectName.unsafe("payments")
  val analytics: ConnectName = ConnectName.unsafe("analytics")

  val elastic: ConnectorName = ConnectorName.unsafe("elastic-sink")

  val caller: Principal = Principal.Anonymous

  /** The rebalance refusal, exactly as `ConnectHttp` builds it: an `ApplicationError` so that ADR-039 §6
    * keeps it out of the capability registry, carrying `KUI-CONNECT-REBALANCING`.
    */
  val rebalancing: KuiError = ApplicationError.Refused(
    ErrorCode.ConnectRebalancing,
    "the Kafka Connect cluster 'analytics' is rebalancing and cannot answer yet"
  )

  val unreachable: KuiError =
    kui.kernel.error.InfrastructureError.Unreachable("kafka-connect.analytics", "connection refused")

  def connector(
      name: String,
      connect: ConnectName = payments,
      state: String = "RUNNING",
      tasks: List[ConnectorTask] = List(task(0, "RUNNING"))
  ): Connector =
    Connector(
      connect = connect,
      name = ConnectorName.unsafe(name),
      kind = ConnectorKind.Sink,
      state = ConnectorState(state),
      workerId = Some("10.0.0.1:8083"),
      trace = None,
      tasks = tasks
    )

  def task(id: Int, state: String, trace: Option[String] = None): ConnectorTask =
    ConnectorTask(TaskId.unsafe(id), ConnectorState(state), Some("10.0.0.1:8083"), trace)

  /** A worker that answers what it was told to, and remembers everything it was asked.
    *
    * @param answer
    *   what `connectors` returns
    * @param accepted
    *   what `operate` returns, so that a case can drive the refusal path as well as the accepted one
    */
  final class FakeWorker(
      answer: Either[KuiError, ConnectorFacts],
      accepted: Either[KuiError, Unit],
      val asked: Ref[IO, List[(ConnectorName, ConnectorOperation)]],
      val reads: Ref[IO, Int]
  ) extends ConnectWorkerPort[IO] {

    def connectors: IO[Either[KuiError, ConnectorFacts]] = reads.update(_ + 1).as(answer)

    def operate(connector: ConnectorName, operation: ConnectorOperation): IO[Either[KuiError, Unit]] =
      asked.update(_ :+ (connector, operation)).as(accepted)
  }

  object FakeWorker {

    def create(
        answer: Either[KuiError, ConnectorFacts] = Right(ConnectorFacts.complete(List(connector("a")))),
        accepted: Either[KuiError, Unit] = Right(())
    ): IO[FakeWorker] =
      for {
        asked <- Ref.of[IO, List[(ConnectorName, ConnectorOperation)]](Nil)
        reads <- Ref.of[IO, Int](0)
      } yield new FakeWorker(answer, accepted, asked, reads)
  }

  /** A worker that never answers, so that a case can prove a call was never made rather than that its result
    * was discarded. Its `connectors` fails the test if it is reached.
    */
  final class NeverWorker extends ConnectWorkerPort[IO] {

    def connectors: IO[Either[KuiError, ConnectorFacts]] =
      IO.raiseError(new AssertionError("the worker was called and should not have been"))

    def operate(connector: ConnectorName, operation: ConnectorOperation): IO[Either[KuiError, Unit]] =
      IO.raiseError(new AssertionError("the worker was called and should not have been"))
  }

  /** Three clusters: one ordinary with two Connect clusters, one read-only, and one with no Kafka Connect at
    * all — which is the ordinary case in most deployments and the one `not_configured` is for.
    */
  final class Source(workers: Map[(ClusterId, ConnectName), ConnectWorkerPort[IO]])
      extends ClusterConnectSource[IO] {

    private val views = List(
      ConnectProfileView(cluster, "Production EU", readOnly = false, connects = List(payments, analytics)),
      ConnectProfileView(readOnlyCluster, "Production US", readOnly = true, connects = List(payments)),
      ConnectProfileView(bare, "Staging", readOnly = false, connects = Nil)
    )

    def profileOf(id: ClusterId): IO[Either[KuiError, ConnectProfileView]] =
      IO.pure(
        views
          .find(_.cluster == id)
          .toRight(ApplicationError.NotFound("cluster", id.value, ErrorCode.ClusterNotFound): KuiError)
      )

    def all: IO[List[ConnectProfileView]] = IO.pure(views)

    def worker(id: ClusterId, connect: ConnectName): IO[Option[ConnectWorkerPort[IO]]] =
      IO.pure(workers.get((id, connect)))
  }

  /** Every operation record the guard wrote, in order. */
  final class RecordingSink(val written: Ref[IO, List[ConnectorOperationRecord]])
      extends ConnectorOperationSink[IO] {

    def record(entry: ConnectorOperationRecord): IO[Unit] = written.update(_ :+ entry)
  }

  object RecordingSink {

    def create: IO[RecordingSink] =
      Ref.of[IO, List[ConnectorOperationRecord]](Nil).map(new RecordingSink(_))
  }
}
