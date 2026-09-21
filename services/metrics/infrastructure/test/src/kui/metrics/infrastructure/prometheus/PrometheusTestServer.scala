package kui.metrics.infrastructure.prometheus

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CopyOnWriteArrayList, ExecutorService, Executors}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

import cats.effect.IO
import cats.effect.kernel.Resource
import com.sun.net.httpserver.{HttpExchange, HttpServer}

/** A real loopback HTTP peer for the Prometheus client suite.
  *
  * A backend stub cannot prove form encoding, reverse-proxy path handling, redirect refusal, incremental
  * response limits or socket timeouts. This fixture keeps those facts observable without depending on an
  * external Prometheus process.
  */
final class PrometheusTestServer private (
    private[prometheus] val server: HttpServer,
    private[prometheus] val executor: ExecutorService,
    val port: Int,
    requestsRef: CopyOnWriteArrayList[PrometheusTestRequest],
    responseRef: AtomicReference[Vector[PrometheusTestResponse]],
    callCount: AtomicInteger
) {

  def baseUrl(prefix: String = "/prometheus"): String =
    s"http://127.0.0.1:$port$prefix"

  def requests: IO[Vector[PrometheusTestRequest]] =
    IO(requestsRef.asScala.toVector)

  def calls: IO[Int] = IO(callCount.get())

  def respondWith(responses: PrometheusTestResponse*): IO[Unit] =
    IO(responseRef.set(responses.toVector))
}

final case class PrometheusTestRequest(
    method: String,
    path: String,
    headers: Map[String, List[String]],
    body: String
) {
  def header(name: String): List[String] =
    headers.collectFirst { case (found, values) if found.equalsIgnoreCase(name) => values }.getOrElse(Nil)
}

final case class PrometheusTestResponse(
    status: Int,
    body: String,
    headers: Map[String, String] = Map("Content-Type" -> "application/json"),
    delay: FiniteDuration = 0.millis,
    chunked: Boolean = false
)

object PrometheusTestServer {

  def resource(initial: PrometheusTestResponse*): Resource[IO, PrometheusTestServer] =
    Resource.make(start(initial.toVector))(stop)

  private def start(initial: Vector[PrometheusTestResponse]): IO[PrometheusTestServer] =
    IO.blocking {
      val requests = new CopyOnWriteArrayList[PrometheusTestRequest]()
      val responses = new AtomicReference(initial)
      val calls = new AtomicInteger(0)
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      val executor = Executors.newCachedThreadPool()
      server.setExecutor(executor)
      server.createContext(
        "/",
        (exchange: HttpExchange) => {
          val number = calls.getAndIncrement()
          val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          val headers = exchange.getRequestHeaders.asScala.view.mapValues(_.asScala.toList).toMap
          requests.add(
            PrometheusTestRequest(
              exchange.getRequestMethod,
              exchange.getRequestURI.getRawPath,
              headers,
              body
            )
          )

          val configured = responses.get()
          val response =
            configured
              .lift(number)
              .orElse(configured.lastOption)
              .getOrElse(
                PrometheusTestResponse(500, "{}")
              )

          try {
            if response.delay.length > 0L then Thread.sleep(response.delay.toMillis)
            response.headers.foreach((name, value) => exchange.getResponseHeaders.add(name, value))
            val bytes = response.body.getBytes(StandardCharsets.UTF_8)
            val length = if response.chunked then 0L else bytes.length.toLong
            exchange.sendResponseHeaders(response.status, length)
            exchange.getResponseBody.write(bytes)
          } finally exchange.close()
        }
      )
      server.start()
      new PrometheusTestServer(
        server,
        executor,
        server.getAddress.getPort,
        requests,
        responses,
        calls
      )
    }

  private def stop(fixture: PrometheusTestServer): IO[Unit] =
    IO.blocking {
      fixture.server.stop(0)
      val _ = fixture.executor.shutdownNow()
    }
}
