package kui.kafka.auth

/** Which kind of client the properties are for.
  *
  * It changes only the `client.id` prefix and which non-security defaults are added, but it is an explicit
  * parameter rather than a default because a `client.id` shared between an admin client and a consumer makes
  * broker-side quotas and broker logs unattributable (`research/kafka/admin-capabilities.md` §0, "Client
  * id"): an operator looking at a broker log cannot tell which of KUI's clients caused the traffic, and a
  * quota applied to one applies to all.
  */
enum ClientPurpose {
  case Admin
  case Consumer
  case Producer

  /** The first segment of a generated `client.id`. */
  def prefix: String = this match {
    case Admin => "kui-admin"
    case Consumer => "kui-consumer"
    case Producer => "kui-producer"
  }
}

object ClientPurpose {
  given CanEqual[ClientPurpose, ClientPurpose] = CanEqual.derived
}
