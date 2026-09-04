package kui.config

import cats.data.ValidatedNel
import cats.syntax.all.*

/** Reading one dotted key out of the merged configuration layers, and one list-shaped section out of them.
  *
  * `KuiConfigSource` owns the layers themselves — the precedence chain of defaults, files, environment and
  * command line — and keeps them private, which is right: nothing outside it should be able to invent a
  * fourth layer. What the sections *do* need is two questions answered about those layers, and this is the
  * pair of function types that asks them. `ClusterSecurityConfig` already had the first one under the name
  * `Lookup`; `kui.auth` and `kui.rbac` are the first sections that also have to ask the second, because both
  * are lists of things whose length nobody knows until the file has been read.
  */
object ConfigReader {

  /** The value configured for a key, and which layer supplied it. `None` when no layer did. */
  type Lookup = String => Option[(ConfigSourceName, String)]

  /** The indices actually present under a list-shaped prefix — `kui.rbac.roles` answers `List(0, 1, 2)` for a
    * file with three roles in it. Sorted, distinct, and drawn from every layer at once, so a role added in
    * the environment is seen beside two written in a file.
    */
  type Indices = String => List[Int]

  type Problems[A] = ValidatedNel[ConfigProblem, A]

  /** One problem about one key, attributed to the layer the offending value came from. */
  def problem(lookup: Lookup, key: String, message: String): ConfigProblem =
    ConfigProblem(key, message, lookup(key).map((source, _) => source).getOrElse(ConfigSourceName.Default))

  /** A required string. */
  def required(lookup: Lookup, key: String, expectation: String): Problems[String] =
    lookup(key).map((_, raw) => raw.trim).filter(_.nonEmpty) match {
      case Some(value) => value.validNel
      case None => problem(lookup, key, s"is required; expected $expectation").invalidNel
    }

  /** An optional string. A key that is present but blank is treated as absent, because a YAML `name:` with
    * nothing after it is far more often a half-finished edit than a deliberate empty value.
    */
  def optional(lookup: Lookup, key: String): Option[String] =
    lookup(key).map((_, raw) => raw.trim).filter(_.nonEmpty)

  /** A string with a default. */
  def withDefault(lookup: Lookup, key: String, fallback: String): String =
    optional(lookup, key).getOrElse(fallback)

  /** `true` or `false`, defaulting when absent and reporting anything else. */
  def boolean(lookup: Lookup, key: String, fallback: Boolean): Problems[Boolean] =
    optional(lookup, key) match {
      case None => fallback.validNel
      case Some("true") => true.validNel
      case Some("false") => false.validNel
      case Some(other) => problem(lookup, key, s"expected true or false (found '$other')").invalidNel
    }

  /** A comma-separated list, which is how a YAML sequence of scalars and an environment variable both arrive.
    */
  def list(lookup: Lookup, key: String): List[String] =
    optional(lookup, key).toList.flatMap(_.split(',').toList.map(_.trim).filter(_.nonEmpty))

  /** Turns a list of validated things into a validated list, keeping **every** problem.
    *
    * `traverse` on `ValidatedNel` already accumulates, and this exists only to say so at the call sites: the
    * whole discipline of this loader is that an operator fixes every mistake in one restart rather than one
    * per restart, and a `sequence` that short-circuited would quietly end that.
    */
  def all[A](items: List[Problems[A]]): Problems[List[A]] = items.sequence

  /** A list index that is present but out of order or gapped.
    *
    * `kui.rbac.roles.0` and `kui.rbac.roles.2` with no `1` is almost always a deleted entry or a mistyped
    * environment variable name, and silently renumbering would hide both. The same rule `kui.clusters[]`
    * applies, stated once so a third list-shaped section does not have to restate it.
    */
  def denseIndices(prefix: String, indices: List[Int]): Problems[Unit] =
    indices.zipWithIndex.collectFirst {
      case (configured, expected) if configured != expected =>
        ConfigProblem(
          s"$prefix.$configured",
          if expected == 0 then s"expected the list to start at 0, not at $configured"
          else s"expected the list to be numbered from 0 with no gaps; $configured follows ${expected - 1}",
          ConfigSourceName.Default
        )
    } match {
      case None => ().validNel
      case Some(found) => found.invalidNel
    }
}
