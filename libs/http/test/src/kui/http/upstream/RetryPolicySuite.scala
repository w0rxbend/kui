package kui.http.upstream

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import munit.FunSuite
import sttp.model.Method

/** When a failed call may be tried again, and how long to wait first.
  *
  * `UpstreamClientSuite` drives the policy through a real client and pins the two rules that decide *whether*
  * to retry. What it never passes is a `random` outside `[0, 1]`, so `backoff`'s clamp — the line that keeps
  * the wait inside the interval the caller was promised — was executed by nothing: made
  * `val fraction = random`, `libs.http.test` and the eight other `libs` suites stayed at 835 of 835.
  *
  * The clamp's reachability is honest to state: `backoff`'s default argument is `math.random()`, which is
  * already in range, so the only caller that can violate it today is a suite. That is exactly why it is worth
  * a case rather than a deletion — the parameter exists so that a *future* caller can supply the randomness,
  * and a guard nothing checks is one edit from being wrong on the day one does.
  */
final class RetryPolicySuite extends FunSuite {

  private val base: FiniteDuration = 100.milliseconds

  test("a wait is never longer than the interval it was drawn from, whatever the source of randomness") {
    // Full jitter is "uniformly from [0, base * 2^attempt]". A source that answered 1.4 would put the
    // wait 40% beyond the ceiling the caller sized its timeout against; one that answered -0.5 would
    // produce a negative duration, which sleeps for no time at all and turns the backoff into a
    // hot loop against an upstream that has just fallen over — the failure jitter exists to prevent.
    assertEquals(RetryPolicy.backoff(1, base, random = 1.4), 200.milliseconds)
    assertEquals(RetryPolicy.backoff(1, base, random = -0.5), 0.milliseconds)
    assert(RetryPolicy.backoff(1, base, random = 1.4) <= 200.milliseconds)
    assert(RetryPolicy.backoff(1, base, random = -0.5) >= 0.milliseconds)
  }

  test("the interval stops growing, so a long-lived client cannot wait for minutes") {
    // Eight doublings of a 100 ms base is already 25 seconds, which is longer than any call KUI makes
    // is allowed to take, and the absolute cap is thirty.
    assert(RetryPolicy.backoff(50, base, random = 1.0) <= 30.seconds)
    assertEquals(RetryPolicy.backoff(8, base, random = 1.0), RetryPolicy.backoff(40, base, random = 1.0))
  }

  test("a negative attempt is the first wait rather than a shorter one") {
    assertEquals(RetryPolicy.backoff(-3, base, random = 1.0), base)
  }

  test("only a method that can be repeated safely is retried") {
    // A GET that failed may or may not have reached the upstream and repeating it changes nothing;
    // a POST that failed may have created something.
    assert(RetryPolicy.isIdempotent(Method.GET))
    assert(RetryPolicy.isIdempotent(Method.HEAD))
    assert(!RetryPolicy.isIdempotent(Method.POST))
    assert(!RetryPolicy.isIdempotent(Method.DELETE))
  }

  test("a status is retried only when the caller named it, and only for a repeatable method") {
    val retryable = Set(409)

    assert(RetryPolicy.shouldRetry(Method.GET, Right(409), retryable))
    // A 500 that arrived is a decision the upstream made, and repeating the call repeats it.
    assert(!RetryPolicy.shouldRetry(Method.GET, Right(500), retryable))
    assert(!RetryPolicy.shouldRetry(Method.POST, Right(409), retryable))
  }

  test("a connection-level failure is retried for a repeatable method and never for a POST") {
    val failure = Left(new java.net.ConnectException("refused"))

    assert(RetryPolicy.shouldRetry(Method.GET, failure, Set.empty))
    assert(!RetryPolicy.shouldRetry(Method.POST, failure, Set.empty))
  }
}
