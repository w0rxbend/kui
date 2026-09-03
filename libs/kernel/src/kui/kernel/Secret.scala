package kui.kernel

import scala.annotation.nowarn

/** A value that must never be printed: a password, an API key, a signing key, a JAAS string.
  *
  * The problem this solves is that secrets do not leak through code that handles them, they leak through code
  * that does not know it is handling them. A configuration case class is logged at startup "for diagnostics";
  * a request is dumped into an error message; a `toString` lands in a stack trace that reaches a browser.
  * Every one of those is a `toString` call nobody wrote on purpose.
  *
  * So `Secret` refuses. `toString` returns `Secret(***)` — which is also what string interpolation,
  * `println`, a logger's default renderer and a `case class` that contains one will print, because all of
  * them go through `toString`. The value is reachable only through `value`, which is a single grep away in a
  * review: "who calls `.value` on a `Secret`" is a question with a short answer.
  *
  * It is deliberately **not** a `case class`. A case class would generate a `toString` that prints its field,
  * an `equals` that compares in variable time, and a `copy` and `unapply` that hand the value out again —
  * every one of those defeating the point of the type.
  */
final class Secret[+A] private (private val underlying: A) {

  /** The only way to the value. Call it at the edge — handing the key to a signer, the password to a client —
    * and never one line earlier.
    */
  def value: A = underlying

  /** Derives another secret from this one — decoding a key from base64, say — without the value becoming
    * visible in between.
    */
  def map[B](f: A => B): Secret[B] = new Secret(f(underlying))

  override def toString: String = Secret.Redacted

  /** Compares two secrets without leaking how much of them matched.
    *
    * For strings the comparison looks at every character even after a mismatch. That is not theatre: an
    * attacker who can time an equality check that returns early learns the value one character at a time.
    * Other value types fall back to ordinary equality, because the timing channel is about the
    * length-prefixed scan, not about `Int` comparison.
    */
  // Overriding `equals` forces the parameter type `Any`, and Scala 3's future source rules warn
  // that `Any` is not a legal thing to pattern match on (only `Matchable` is). There is no way to
  // both override `equals` and satisfy that rule, so the warning is silenced here and only here.
  @nowarn("msg=pattern selector")
  override def equals(that: Any): Boolean = that match {
    case other: Secret[?] =>
      (underlying, other.underlying) match {
        case (mine: String, theirs: String) => Secret.constantTimeEquals(mine, theirs)
        case (mine, theirs) => mine == theirs
      }
    case _ => false
  }

  /** The same number for every secret.
    *
    * A hash code derived from the value would put a secret in a hash bucket that a timing or collision
    * observation could probe, and would print the value's fingerprint in a heap dump. The cost is that a
    * `Map` keyed by `Secret` degenerates to a linear scan, which is the right trade: nothing should be keyed
    * by a secret anyway.
    */
  override def hashCode: Int = Secret.HashCode
}

object Secret {

  /** What every rendering path shows instead of the value. */
  val Redacted: String = "Secret(***)"

  private val HashCode: Int = 0x5ec7e7

  def apply[A](a: A): Secret[A] = new Secret(a)

  /** Compares two strings in time that depends on their lengths but not on their contents. */
  private def constantTimeEquals(left: String, right: String): Boolean = {
    val lengthsDiffer = left.length != right.length
    val compared = if lengthsDiffer then left else right
    val difference = left
      .zip(compared)
      .foldLeft(0)((acc, pair) => acc | (pair._1 ^ pair._2))
    !lengthsDiffer && difference == 0
  }

  given [A] => CanEqual[Secret[A], Secret[A]] = CanEqual.derived
}
