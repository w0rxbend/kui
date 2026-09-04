package kui.ui.clusters.component

/** Where this feature's byte formatting used to live.
  *
  * It moved to `kui.ui.kernel.component.Bytes` when the topic list became its fourth caller: a feature may
  * not depend on another feature, so the choice was to promote it or to copy it, and two copies of a rounding
  * rule are two answers to "is this 1.0 MiB". Re-exported rather than deleted so that this feature's call
  * sites and its suite are unchanged — the move is meant to be invisible to them, which is the evidence that
  * it was a move and not a rewrite.
  */
object Bytes {
  export kui.ui.kernel.component.Bytes.{format, fraction, fractionOf}
}
