package kui.ui.consumers

/** Where this feature's number formatting used to live.
  *
  * It moved to `kui.ui.kernel.component.Numbers` when the dashboard, the home page and the topic list turned
  * out to render the same figures — total lag, message counts, partition counts — as bare `toString`. A
  * feature may not depend on another feature, so the choice was to promote it or to copy it, and two copies
  * of a grouping rule are two answers to "is this 1204331 or 1 204 331" on two screens showing the same
  * number. Re-exported rather than deleted so that this feature's call sites and its suite are unchanged —
  * the move is meant to be invisible to them, which is the evidence that it was a move and not a rewrite.
  * This is the same treatment `Bytes` was given for the same reason.
  */
object Numbers {
  export kui.ui.kernel.component.Numbers.{GroupSeparator, grouped, rate, fraction}
}
