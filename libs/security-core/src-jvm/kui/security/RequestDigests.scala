package kui.security

import java.security.MessageDigest

/** Building a [[RequestDigest]] from a request body.
  *
  * This is JVM-only, and deliberately so. Hashing needs a SHA-256 implementation; the JVM has a vetted one in
  * its standard library, and a browser's is asynchronous and therefore unusable from a synchronous function.
  * Nothing in the browser ever signs a principal — only the gateway does — so rather than hand-writing a
  * second SHA-256 for a caller that does not exist, the capability lives where it is used.
  *
  * The shared half of the module still owns the type, and `RequestDigest.ofRequestLine` covers the body-less
  * case with a written-down constant.
  */
object RequestDigests {

  /** The digest of a call whose body has been read into memory. */
  def of(method: String, path: String, body: Array[Byte]): RequestDigest =
    RequestDigest(method.toUpperCase, path, sha256Hex(body))

  /** Lowercase hexadecimal SHA-256, the form every claim and log line in KUI uses. */
  def sha256Hex(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(byte => String.format("%02x", Integer.valueOf(byte & 0xff))).mkString
  }
}
