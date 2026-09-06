package kui.cluster.domain

import scala.concurrent.duration.FiniteDuration

/** How much of a stated window this cluster had an active controller.
  *
  * ==Why a window and not an instant==
  *
  * Every other figure on `ClusterTopology` is a fact about one scrape. This one is not: "99.98 %" is a
  * statement about a period, and the period has to be carried with it or the number means nothing. A
  * percentage computed over the four minutes since the process booted and printed as "over the last 6h" is
  * the failure `SeriesWindow` in `libs/cache` was built to make impossible, and this figure refuses the same
  * way — `percent` is `None` until the window has actually been collecting for `window`.
  *
  * `coverage` is carried alongside so that the refusal can be explained rather than merely shown: a client
  * that got `None` can say "collecting, 41m of 6h" instead of drawing an empty ring, which is the difference
  * between a KUI that has just started and one that cannot measure this at all.
  *
  * @param window
  *   the period the percentage is over. It travels to the browser so that the label reads "over the last 6h"
  *   from data rather than from a literal that would go stale the day the window is retuned
  * @param coverage
  *   how long the window has been collecting, capped by its own retention. Never longer than `window` in any
  *   value this service produces, because the window is what it is capped at
  * @param percent
  *   the share of the observed samples in which a controller was present, to two decimals. `None` when the
  *   window does not yet span `window`, and also when it spans it having observed nothing at all — a
  *   percentage over no observations is not a percentage, whatever shape the buckets came back in
  */
final case class ControllerUptime(
    window: FiniteDuration,
    coverage: FiniteDuration,
    percent: Option[Double]
)

object ControllerUptime {

  /** Folds the samples a window answered with into a percentage, or refuses.
    *
    * `observations` is `None` when the window declined to answer at all — it has not been collecting for
    * `window` — and `Some(empty)` when it answered with nothing but gaps, which is what a cluster KUI has
    * been unable to reach for the whole window looks like. Both are `None` here, and they are kept apart at
    * the call site by `coverage`, not by inventing a figure for either.
    *
    * A sample is `true` when that scrape found a controller. A scrape that failed records **nothing** rather
    * than `false`: KUI being unable to ask is not the cluster being without a controller, and a gap says so
    * where a zero would not.
    */
  def of(
      window: FiniteDuration,
      coverage: FiniteDuration,
      observations: Option[Vector[Boolean]]
  ): ControllerUptime =
    ControllerUptime(
      window = window,
      coverage = coverage,
      percent = observations.filter(_.nonEmpty).map { samples =>
        percentage(samples.count(identity), samples.size)
      }
    )

  /** Two decimals, because the figure this feeds is drawn as `99.98 %` and a client that rounded it itself
    * would be a second rounding rule for one number.
    */
  private def percentage(present: Int, observed: Int): Double =
    math.round(present.toDouble / observed.toDouble * 100.0d * 100.0d).toDouble / 100.0d

  given CanEqual[ControllerUptime, ControllerUptime] = CanEqual.derived
}
