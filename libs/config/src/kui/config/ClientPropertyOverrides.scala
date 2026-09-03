package kui.config

import scala.util.matching.Regex

/** The raw Kafka client properties an operator may set alongside KUI's typed model, and the rule that keeps
  * them out of log lines.
  *
  * ADR-022's escape hatch: whatever KUI renders from the typed security ADT, these properties are applied
  * last and win. That is what lets a mechanism KUI has not modelled yet be used without waiting for a
  * release. The cost is that KUI has no idea what is in the map, which is why the redaction here is by key
  * *pattern* rather than by a list of known-sensitive keys — a list could not possibly be complete for a map
  * whose whole purpose is to hold things KUI does not know about.
  *
  * Shared by the `kui.store.kafka.properties` slice (STORE-004) and the `kui.clusters[].properties` slice
  * (CFGOP-001), so that the two cannot drift into redacting different things.
  */
object ClientPropertyOverrides {

  /** Any key that looks like it names a credential. Deliberately broad: a false positive costs an operator
    * one `***` in a diagnostic line, and a false negative costs them a password in a log aggregator.
    *
    * `jaas` is on the list for a specific reason: `sasl.jaas.config` is the single most credential-bearing
    * property name in Kafka and it contains none of the obvious words. A property test over real Kafka
    * property names is what found that, and it is why the list is a pattern rather than intuition.
    */
  val SecretKeyPattern: Regex = "(?i).*(password|secret|key|token|credential|jaas|passwd|auth).*".r

  def isSecretKey(name: String): Boolean = SecretKeyPattern.matches(name)

  /** The map as it may be printed: sensitive values replaced, keys kept.
    *
    * Keys are kept because an operator debugging a connection needs to see *which* properties are set, and a
    * property name is not a secret. It is the value that is.
    */
  def redact(properties: Map[String, String]): Map[String, String] =
    properties.map((name, value) => name -> (if isSecretKey(name) then "***" else value))

  /** One line, in key order, safe to log. */
  def render(properties: Map[String, String]): String =
    redact(properties).toList.sortBy(_._1).map((name, value) => s"$name=$value").mkString(", ")
}
