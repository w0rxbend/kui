package kui.metrics.infrastructure.prometheus

import scala.jdk.CollectionConverters.*

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.opentelemetry.sdk.metrics.data.MetricData
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit

import kui.kernel.ClusterId
import kui.observability.MetricNames
import kui.testkit.KuiIOSuite

final class PrometheusQueryMetricsSuite extends KuiIOSuite {

  private val context = PrometheusQueryContext(
    source = ClusterId.unsafe("prod"),
    query = QueryId.from("consumer.lag").toOption.get,
    operation = QueryOperation.Range
  )

  test("one logical call records duration, count, bounded outcome and a balanced in-flight value") {
    recorded { metrics =>
      metrics.observe(context)(IO.pure(42))(_ => QueryOutcome.Success).void
    }.map { collected =>
      assertEquals(
        names(collected),
        Set(
          MetricNames.PrometheusQueryDuration,
          MetricNames.PrometheusQueryRequests,
          MetricNames.PrometheusQueryInFlight
        )
      )
      assertEquals(longSum(collected, MetricNames.PrometheusQueryRequests), List(1L))
      assertEquals(longSum(collected, MetricNames.PrometheusQueryInFlight), List(0L))

      val expected = Map(
        MetricNames.Attr.Source -> "prod",
        MetricNames.Attr.Query -> "consumer.lag",
        MetricNames.Attr.Operation -> "range",
        MetricNames.Attr.Outcome -> "success"
      )
      assertEquals(attributes(collected, MetricNames.PrometheusQueryRequests), List(expected))
      assertEquals(attributes(collected, MetricNames.PrometheusQueryDuration), List(expected))
      assertEquals(
        attributes(collected, MetricNames.PrometheusQueryInFlight),
        List(expected - MetricNames.Attr.Outcome)
      )
    }
  }

  test("effects that fail or are cancelled still balance in-flight and use closed outcome values") {
    val canary = "https://alice:secret@prometheus.internal/api/v1/query?query=private_metric"

    recorded { metrics =>
      metrics
        .observe(context)(IO.raiseError[Unit](new RuntimeException(canary)))(_ => QueryOutcome.Success)
        .attempt
        .void *> metrics.observe(context)(IO.canceled)(_ => QueryOutcome.Success).start.flatMap(_.join).void
    }.map { collected =>
      val requestAttributes = attributes(collected, MetricNames.PrometheusQueryRequests)
      assertEquals(
        requestAttributes.flatMap(_.get(MetricNames.Attr.Outcome)).toSet,
        Set("failure", "cancelled")
      )
      assertEquals(longSum(collected, MetricNames.PrometheusQueryRequests), List(1L, 1L))
      assertEquals(attributes(collected, MetricNames.PrometheusQueryDuration).size, 2)
      assertEquals(longSum(collected, MetricNames.PrometheusQueryInFlight), List(0L))
      List(canary, "alice", "secret", "prometheus.internal", "private_metric").foreach(fragment =>
        assert(!collected.toString.contains(fragment), s"'$fragment' reached metric data")
      )
    }
  }

  test("a throwing outcome classifier cannot fail the query or leave in-flight unbalanced") {
    recorded { metrics =>
      metrics
        .observe(context)(IO.pure("answer"))(_ => throw new IllegalArgumentException("classifier-canary"))
        .flatMap(answer => IO(assertEquals(answer, "answer")))
    }.map { collected =>
      assertEquals(longSum(collected, MetricNames.PrometheusQueryRequests), List(1L))
      assertEquals(
        attributes(collected, MetricNames.PrometheusQueryRequests).flatMap(_.get(MetricNames.Attr.Outcome)),
        List("failure")
      )
      assertEquals(longSum(collected, MetricNames.PrometheusQueryInFlight), List(0L))
      assert(!collected.toString.contains("classifier-canary"), collected.toString)
    }
  }

  test("the in-flight gauge rises with concurrent callers and returns to zero") {
    OtelJavaTestkit.inMemory[IO]().use { testkit =>
      for {
        meter <- testkit.meterProvider.get("kui.metrics")
        metrics <- PrometheusQueryMetrics.otel4s[IO](meter)
        gate <- Deferred[IO, Unit]
        started <- Ref.of[IO, Int](0)
        call = metrics.observe(context)(started.update(_ + 1) *> gate.get)(_ => QueryOutcome.Success)
        callers <- List.fill(3)(call).parSequence.start
        _ <- waitUntil(started.get.map(_ == 3))
        during <- testkit.collectMetrics
        _ <- gate.complete(())
        _ <- callers.joinWithNever
        after <- testkit.collectMetrics
      } yield {
        assertEquals(longSum(during.toList, MetricNames.PrometheusQueryInFlight), List(3L))
        assertEquals(longSum(after.toList, MetricNames.PrometheusQueryInFlight), List(0L))
        assertEquals(longSum(after.toList, MetricNames.PrometheusQueryRequests), List(3L))
      }
    }
  }

  test("response, cache, coalescing, limit and diagnostic measurements have only bounded dimensions") {
    recorded { metrics =>
      metrics.response(context, bytes = 4096L, series = 3L, samples = 120L) *>
        metrics.cache(context, QueryCacheAccess.Coalesced, QueryCacheFreshness.Stale) *>
        metrics.limitRejected(context, QueryLimit.Series) *>
        metrics.diagnostics(context, QueryDiagnostics(warningCount = 2, infoCount = 1))
    }.map { collected =>
      assertEquals(histogramSum(collected, MetricNames.PrometheusResponseBytes), List(4096.0))
      assertEquals(histogramSum(collected, MetricNames.PrometheusResponseSeries), List(3.0))
      assertEquals(histogramSum(collected, MetricNames.PrometheusResponseSamples), List(120.0))
      assertEquals(longSum(collected, MetricNames.PrometheusQueryCoalesced), List(1L))
      assertEquals(longSum(collected, MetricNames.PrometheusQueryLimitRejected), List(1L))
      assertEquals(longSum(collected, MetricNames.PrometheusQueryDiagnostics), List(1L, 2L))

      assertEquals(
        attributes(collected, MetricNames.PrometheusQueryCacheAccess).flatMap(_.get(MetricNames.Attr.State)),
        List("coalesced_stale")
      )
      assertEquals(
        attributes(collected, MetricNames.PrometheusQueryLimitRejected)
          .flatMap(_.get(MetricNames.Attr.Limit)),
        List("series")
      )
      assertEquals(
        attributes(collected, MetricNames.PrometheusQueryDiagnostics)
          .flatMap(_.get(MetricNames.Attr.Kind))
          .toSet,
        Set("info", "warning")
      )

      val allowed = Set(
        MetricNames.Attr.Source,
        MetricNames.Attr.Query,
        MetricNames.Attr.Operation,
        MetricNames.Attr.Outcome,
        MetricNames.Attr.State,
        MetricNames.Attr.Limit,
        MetricNames.Attr.Kind
      )
      assert(allAttributes(collected).forall(_.keySet.subsetOf(allowed)))
    }
  }

  test("zero diagnostics do not create empty series") {
    recorded(_.diagnostics(context, QueryDiagnostics.Empty)).map { collected =>
      assertEquals(attributes(collected, MetricNames.PrometheusQueryDiagnostics), Nil)
    }
  }

  private def recorded(write: PrometheusQueryMetrics[IO] => IO[Unit]): IO[List[MetricData]] =
    OtelJavaTestkit.inMemory[IO]().use { testkit =>
      for {
        meter <- testkit.meterProvider.get("kui.metrics")
        metrics <- PrometheusQueryMetrics.otel4s[IO](meter)
        _ <- write(metrics)
        collected <- testkit.collectMetrics
      } yield collected.toList
    }

  private def names(metrics: List[MetricData]): Set[String] = metrics.map(_.getName).toSet

  private def longSum(metrics: List[MetricData], name: String): List[Long] =
    metrics
      .filter(_.getName == name)
      .flatMap(_.getLongSumData.getPoints.asScala.toList)
      .map(_.getValue)
      .sorted

  private def histogramSum(metrics: List[MetricData], name: String): List[Double] =
    metrics
      .filter(_.getName == name)
      .flatMap(_.getHistogramData.getPoints.asScala.toList)
      .map(_.getSum)
      .sorted

  private def attributes(metrics: List[MetricData], name: String): List[Map[String, String]] =
    metrics
      .filter(_.getName == name)
      .flatMap(_.getData.getPoints.asScala.toList)
      .map(point => point.getAttributes.asMap.asScala.map((key, value) => key.getKey -> value.toString).toMap)
      .sortBy(_.toList.sorted.mkString)

  private def allAttributes(metrics: List[MetricData]): List[Map[String, String]] =
    metrics.flatMap(metric => attributes(List(metric), metric.getName))

  private def waitUntil(condition: IO[Boolean]): IO[Unit] =
    condition.flatMap(if _ then IO.unit else IO.cede *> waitUntil(condition))
}
