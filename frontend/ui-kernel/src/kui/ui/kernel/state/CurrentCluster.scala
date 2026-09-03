package kui.ui.kernel.state

import scala.util.Try

import com.raquo.airstream.web.{WebStorageBuilder, WebStorageVar}
import com.raquo.laminar.api.L.*

import kui.kernel.ClusterId

/** Which cluster the user is looking at.
  *
  * One of the five kernel-owned `Var`s (ADR-011). It is global because almost every request in KUI is about
  * one cluster, and threading it through every component would put a parameter on every signature for a value
  * that changes once a session.
  *
  * ## Why it is remembered, and why the URL still wins
  *
  * The choice is stored in this browser, so a reload comes back to the cluster somebody was working on rather
  * than to whichever one sorts first. But a URL naming a cluster overrides it on load: a link is usually
  * pasted by a colleague, and it has to show the recipient what the sender saw. The router sets this from the
  * route before anything reads it.
  *
  * A stored id that no longer parses — a cluster renamed since it was written — reads as `None` rather than
  * as a broken selection.
  */
object CurrentCluster {

  /** The `localStorage` key. Namespaced, because a KUI deployment may share an origin. */
  val StorageKey: String = "kui.cluster.current"

  /** `lazy` so that merely importing this object does not touch `localStorage`. */
  lazy val selected: Var[Option[ClusterId]] =
    persisted(WebStorageVar.localStorage(StorageKey, syncOwner = None))

  /** What the current cluster is, for code that only reads. */
  def signal: Signal[Option[ClusterId]] = selected.signal

  private[kernel] def persisted(storage: WebStorageBuilder): Var[Option[ClusterId]] =
    storage.withCodec[Option[ClusterId]](
      encode = _.fold("")(_.value),
      // Decoding cannot fail: an empty or unparseable id is "no cluster chosen", which is a state the
      // whole application already handles, rather than a value that would have to be rejected somewhere.
      decode = raw => scala.util.Success(Option(raw).filter(_.nonEmpty).flatMap(ClusterId.from(_).toOption)),
      default = Try(None)
    )
}
