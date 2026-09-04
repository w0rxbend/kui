package kui.ui.kernel.prefs

import scala.util.Try

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** Somewhere small to keep a browser preference, with every access already wrapped.
  *
  * The wrapping is not defensive noise. In a browser with site data blocked, `window.localStorage` throws on
  * *property access* — before any read or write — so a bare `dom.window.localStorage.getItem(key)` takes the
  * whole page down at the first star anybody clicks. A preference that cannot be stored must degrade to
  * "there is no preference", never to a broken screen.
  */
trait PreferenceStore {
  def read(key: String): Option[String]
  def write(key: String, value: String): Unit
}

object PreferenceStore {

  /** The browser's own `localStorage`, or nothing at all when it is unavailable or throwing. */
  val browser: PreferenceStore = new PreferenceStore {
    def read(key: String): Option[String] =
      Try(Option(dom.window.localStorage.getItem(key))).toOption.flatten

    def write(key: String, value: String): Unit =
      Try(dom.window.localStorage.setItem(key, value)).getOrElse(())
  }

  /** Remembers nothing and fails at nothing. The honest fallback, and what a test uses. */
  val none: PreferenceStore = new PreferenceStore {
    def read(key: String): Option[String] = None
    def write(key: String, value: String): Unit = ()
  }
}

/** Starred items, per cluster, in this browser.
  *
  * ==Why this never reaches the server==
  *
  * The lists it decorates are **server-paged**. If the favourite set influenced which items were on which
  * page, two tabs of the same user would disagree about what page 3 contains, and a link one person sent
  * would not reproduce for the person who received it. So favourites pin *within the page that is on screen*,
  * change no total, and are a preference of this browser and nothing more (DEVPLAN §10 D9; the feature
  * matrix's CL-010 row already says `localStorage`).
  *
  * ==Why it is a class with a namespace==
  *
  * Feature state is a class holding `Var`s, never a global (PLAN §21), and the namespace is what keeps the
  * topics feature's stars from colliding with the consumer groups' when M4 adds its own.
  *
  * @param namespace
  *   the key prefix, e.g. `"kui.favourites.topics"`
  * @param store
  *   where the set is kept. Injected so that a suite can hand in a store that throws, which is the behaviour
  *   of a real browser with site data blocked and is otherwise impossible to reproduce
  */
final class Favourites(namespace: String, store: PreferenceStore = PreferenceStore.browser) {

  /** Every cluster whose set has been read, and what it holds. A cluster absent from the map has not been
    * looked at yet; a cluster present with an empty set has been read and holds nothing.
    */
  private val state: Var[Map[String, Set[String]]] = Var(Map.empty)

  /** The starred names for one cluster. Reading the signal is what loads the set. */
  def signal(cluster: String): Signal[Set[String]] = {
    load(cluster): Unit
    state.signal.map(_.getOrElse(cluster, Set.empty)).distinct
  }

  def isFavourite(cluster: String, name: String): Boolean = load(cluster).contains(name)

  def toggle(cluster: String, name: String): Unit = {
    val current = load(cluster)
    val next = if current.contains(name) then current - name else current + name
    state.update(_.updated(cluster, next))
    // Wrapped here as well as inside `PreferenceStore.browser`, and deliberately: the contract is
    // "a store that cannot answer degrades to nothing starred", not "the default store happens to be
    // safe". A star that cannot be saved must still be a star on screen until the page is reloaded.
    Try(store.write(keyFor(cluster), encode(next))).getOrElse(())
  }

  /** Favourites first, then the given order, stable within each group.
    *
    * Stable is the whole contract: a starred row must not change its position relative to the other starred
    * rows when a sixth one is added, and an unstarred row must stay where the server's sort put it.
    */
  def pin[A](cluster: String, items: List[A])(name: A => String): List[A] = {
    val starred = load(cluster)
    val (favourites, rest) = items.partition(item => starred.contains(name(item)))
    favourites ++ rest
  }

  private def keyFor(cluster: String): String = s"$namespace.$cluster"

  private def load(cluster: String): Set[String] =
    state.now().get(cluster) match {
      case Some(known) => known
      case None =>
        val read = Try(store.read(keyFor(cluster))).toOption.flatten.fold(Set.empty[String])(decode)
        state.update(_.updated(cluster, read))
        read
    }

  /** The stored form: a format marker, then one name per line.
    *
    * Not JSON. Reading JSON back means casting a parsed value to a shape the compiler cannot check, which
    * `.scalafix.conf` forbids in this module for good reasons of its own. Lines are enough: a Kafka topic
    * name and a consumer group id may not contain a newline, so the format has no escaping and therefore no
    * escaping bugs.
    *
    * The marker is what makes a corrupt value *detectable* rather than merely harmless. Without it, anything
    * at all decodes into a set of nonsense names; with it, a value this version cannot read is recognised as
    * such and ignored.
    */
  private val Marker: String = "kui.favourites.v1"

  private def encode(names: Set[String]): String =
    (Marker :: names.toList.sorted).mkString("\n")

  /** A stored value this version of KUI cannot read is ignored, never fatal.
    *
    * `localStorage` outlives upgrades and is shared by every KUI on the origin, so the value here may have
    * been written by a different version of KUI — or by something else entirely. Of the two ways to be wrong,
    * "nothing is starred" is the safe one.
    */
  private def decode(raw: String): Set[String] =
    raw.split('\n').toList match {
      case Marker :: names => names.filter(_.nonEmpty).toSet
      case _ => Set.empty
    }
}
