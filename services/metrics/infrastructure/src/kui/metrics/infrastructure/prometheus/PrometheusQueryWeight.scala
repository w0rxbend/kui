package kui.metrics.infrastructure.prometheus

import java.nio.charset.StandardCharsets

/** Conservative decoded-result weights for the per-source query cache.
  *
  * This is an accounting model, not a JVM object-layout estimate. It includes every payload-dependent UTF-8
  * label byte, every decoded sample and timestamp, series/container overhead, freshness, and bounded
  * diagnostics. A large matrix therefore cannot count as one tiny cache value merely because it is wrapped in
  * one Scala object.
  */
object PrometheusQueryWeight {
  def instant(answer: QueryAnswer[InstantQueryResult]): Long =
    answerWeight(
      answer.result.series.foldLeft(0L)((total, series) =>
        add(total, add(SeriesOverhead, add(labels(series.labels), SampleWeight)))
      ),
      answer.diagnostics
    )

  def range(answer: QueryAnswer[RangeQueryResult]): Long =
    answerWeight(
      answer.result.series.foldLeft(0L)((total, series) =>
        add(
          total,
          add(SeriesOverhead, add(labels(series.labels), multiply(series.samples.size.toLong, SampleWeight)))
        )
      ),
      answer.diagnostics
    )

  private def answerWeight(series: Long, diagnostics: QueryDiagnostics): Long =
    add(
      AnswerOverhead,
      add(
        series,
        multiply(
          add(diagnostics.warningCount.max(0).toLong, diagnostics.infoCount.max(0).toLong),
          DiagnosticWeight
        )
      )
    )

  private def labels(labels: MetricLabels): Long =
    labels.values.foldLeft(0L) { case (total, (name, value)) =>
      add(total, add(LabelOverhead, add(utf8(name), utf8(value))))
    }

  private def utf8(value: String): Long = value.getBytes(StandardCharsets.UTF_8).length.toLong

  private def add(left: Long, right: Long): Long =
    if left > Long.MaxValue - right then Long.MaxValue else left + right

  private def multiply(left: Long, right: Long): Long =
    if left != 0L && right > Long.MaxValue / left then Long.MaxValue else left * right

  private val AnswerOverhead = 64L
  private val SeriesOverhead = 48L
  private val LabelOverhead = 24L
  private val SampleWeight = 40L
  private val DiagnosticWeight = 8L
}
