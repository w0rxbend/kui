package kui.config

import kui.kernel.Secret

/** The `kui.streaming.*` slice: the one key that signs everything KUI hands a browser and takes back.
  *
  * ==What the key is for==
  *
  * Two features mint an opaque string, give it to the browser, and later accept it back as an instruction:
  *
  *   - the message browser's paging cursor (ADR-026), which names a cluster, a topic and a position;
  *   - the offset reset's plan token (ADR-045), which names the exact offsets an operator was shown and
  *     authorises writing precisely those.
  *
  * Both are HMAC-SHA256 over a payload KUI wrote, and both are trusted *because* of that signature — the
  * apply endpoint of a reset takes a token and nothing else, so a token an attacker could mint would be a
  * token that writes offsets nobody was shown. ADR-045 reuses ADR-026's key deliberately: one secret, one
  * rotation procedure, and a payload version prefix keeping the two uses apart.
  *
  * ==Why it is configuration and not a fresh random per process==
  *
  * It used to be generated at startup in each service's composition root, which is honest for exactly one
  * deployment shape — a single all-in-one process that nobody restarts. Everywhere else it is wrong in two
  * ways. A second replica rejects every cursor and every plan token its neighbour minted, because it is
  * verifying against a key it has never seen; and a restart of the one process invalidates a reset wizard
  * that an operator had left open, so the plan they were reading has to be composed again from the start.
  *
  * `None` keeps the old behaviour rather than refusing to start: a laptop running the quickstart has one
  * process, no replicas and nothing worth persisting across a restart, and demanding a generated secret
  * before anything works would be a worse first five minutes for no security gained. The composition roots
  * that fall back log that they did.
  *
  * @param cursorKey
  *   the shared signing key, from `kui.streaming.cursorKey`. Written as a literal, as `env:NAME` or as
  *   `file:/path`, exactly like `kui.gateway.principalKeys[].key`. HMAC-SHA256 wants 256 bits, so a resolved
  *   value shorter than 32 bytes is refused at startup rather than silently weakening every token
  */
final case class StreamingConfig(cursorKey: Option[Secret[String]])

object StreamingConfig {

  /** 256 bits. The same floor `kui.gateway.principalKeys` applies, and for the same reason. */
  val MinCursorKeyBytes: Int = 32

  val Default: StreamingConfig = StreamingConfig(cursorKey = None)

  given CanEqual[StreamingConfig, StreamingConfig] = CanEqual.derived
}
