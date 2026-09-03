package kui.ui.kernel.state

import com.raquo.laminar.api.L.*

import kui.kernel.ClusterId

/** Which cluster the user is looking at.
  *
  * One of the five kernel-owned `Var`s (ADR-011). It is global because almost every request in KUI is about
  * one cluster, and threading it through every component would put a parameter on every signature for a value
  * that changes once a session.
  *
  * In M0 it is always `None`: there are no clusters yet (they arrive in M1), and a capability that does not
  * vary per cluster is keyed with `None` on the wire too (`CapabilityKey.cluster`). Having the `Var` now
  * means M1 adds a switcher rather than a concept.
  */
object CurrentCluster {

  val selected: Var[Option[ClusterId]] = Var(None)

  /** What the current cluster is, for code that only reads. */
  def signal: Signal[Option[ClusterId]] = selected.signal
}
