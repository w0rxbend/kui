package kui.kafka.admin

import java.lang.reflect.{InvocationHandler, Method, Proxy}

import cats.effect.IO

import kui.kafka.AdminClientPool
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterConnection
import org.apache.kafka.clients.admin.Admin

/** An `Admin` that answers one method and refuses every other.
  *
  * `Admin` has some seventy methods, so a hand-written stub would be seventy lines of noise around the one
  * line under test, and each new Kafka release would break it. A reflective proxy answers exactly what a test
  * arranges and fails loudly for anything else — which is itself an assertion: a port method that quietly
  * calls a second admin API is a change worth noticing.
  */
object StubAdmin {

  def apply(answers: PartialFunction[String, AnyRef]): Admin =
    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[Admin]),
        new InvocationHandler {
          def invoke(proxy: Any, method: Method, args: Array[AnyRef] | Null): AnyRef =
            answers.applyOrElse(
              method.getName,
              (name: String) => throw new AssertionError(s"this test's Admin does not answer $name")
            )
        }
      )
      .asInstanceOf[Admin]

  /** A pool that hands every call the same stubbed client. */
  def pool(admin: Admin): AdminClientPool[IO] = new AdminClientPool[IO] {
    def run[A](connection: ClusterConnection, operation: String)(call: Admin => IO[A]): IO[A] =
      call(admin)
    def invalidate(id: ClusterId): IO[Unit] = IO.unit
    def evict(id: ClusterId): IO[Unit] = IO.unit
  }

  /** A pool whose first call fails and whose later calls succeed, for testing a capability downgrade. */
  def failingOnce(failure: Throwable, admin: Admin): IO[AdminClientPool[IO]] =
    cats.effect.Ref.of[IO, Int](0).map { calls =>
      new AdminClientPool[IO] {
        def run[A](connection: ClusterConnection, operation: String)(call: Admin => IO[A]): IO[A] =
          calls.getAndUpdate(_ + 1).flatMap { seen =>
            if seen == 0 then IO.raiseError[A](failure) else call(admin)
          }
        def invalidate(id: ClusterId): IO[Unit] = IO.unit
        def evict(id: ClusterId): IO[Unit] = IO.unit
      }
    }
}
