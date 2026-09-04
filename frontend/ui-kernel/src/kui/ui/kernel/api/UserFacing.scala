package kui.ui.kernel.api

import kui.kernel.error.ErrorCode

/** The one rule for turning a server's error message into the sentence a screen shows.
  *
  * ==The problem it solves==
  *
  * Most of KUI's error messages are written for the person reading them, name the thing the request was
  * about, and are far better than anything a browser could invent: "topic 'orders' does not exist" is exactly
  * right. A handful are not. An upstream failure's message describes KUI's own plumbing —
  * `kafka answered with status 502` — and pressing *Read* on a browse against a dead broker put that on
  * screen verbatim. It tells an operator the wrong thing twice over: no Kafka broker speaks HTTP, so there is
  * no 502 to go and look for, and the actual problem, that the broker is unreachable, is not stated anywhere.
  *
  * ==The rule==
  *
  * A message is shown as the server wrote it, *except* for the small set of codes that mean "something KUI
  * depends on is not working". Those get a sentence about the cluster, which is what the reader can act on.
  * The code itself is never discarded — it stays on the `ApiError`, it is in the correlation line a user
  * quotes in a support request, and it is in the log on both sides.
  *
  * The set is deliberately small and listed rather than derived. Every other code either belongs to a
  * business failure, whose message is the whole point, or is unknown to this build — and an older browser
  * must show a newer KUI's message rather than replace it with a guess.
  */
object UserFacing {

  val Unreachable: String =
    "KUI cannot reach the cluster. It is unreachable, or it is not accepting connections."

  val Slow: String = "The cluster did not answer in time. It may be overloaded."

  val Credentials: String =
    "KUI's credentials for the cluster were rejected. Its configuration has to change before this can work."

  /** The sentence to render, given the wire code and what the server said.
    *
    * @param code
    *   the envelope's `code`, as a `String` because a browser must cope with a code this build has never
    *   heard of
    * @param serverMessage
    *   what the server wrote. Returned unchanged for everything the rule does not cover
    */
  def sentence(code: String, serverMessage: String): String =
    if code == ErrorCode.UpstreamUnavailable.wire then Unreachable
    else if code == ErrorCode.Timeout.wire then Slow
    else if code == ErrorCode.UpstreamAuth.wire then Credentials
    else serverMessage
}
