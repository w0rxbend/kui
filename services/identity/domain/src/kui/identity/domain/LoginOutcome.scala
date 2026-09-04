package kui.identity.domain

import kui.kernel.{Secret, UserName, ValidationError}

/** Who somebody turned out to be, in this service's own terms.
  *
  * It carries a name and a set of group names, and no roles. Roles are the deployment's policy applied to
  * these facts, and applying it is the `application` layer's job — which is why an identity looks the same
  * whether it came from a password, from an OpenID Connect provider or from a directory server, and why
  * adding a fourth source changes nothing here.
  */
final case class Identity(name: UserName, groups: Set[String])

object Identity {
  given CanEqual[Identity, Identity] = CanEqual.derived
}

/** How an attempt to sign in ended.
  *
  * There are exactly three endings, and the middle one is the reason this is an enum rather than an
  * `Either[Error, Identity]`: an account that has to change its password has not failed to authenticate, and
  * it has not signed in either. Modelling it as a success with a flag beside it is how a "you must change
  * your password" checkbox ends up being something only the browser enforces.
  */
enum LoginOutcome {

  /** Signed in. */
  case Authenticated(identity: Identity)

  /** The password was right, and this account may not have a session until it has a new one.
    *
    * @param challenge
    *   the single-use token the change must be presented with. It exists so that the change endpoint does not
    *   have to accept a username and an old password a second time — and so that a caller cannot reach the
    *   change endpoint at all without having already proved the current password once.
    */
  case PasswordChangeRequired(name: UserName, challenge: Secret[String])

  /** No. Deliberately carries no detail: see [[LoginOutcome.Rejected]].
    */
  case Rejected
}

object LoginOutcome {

  given CanEqual[LoginOutcome, LoginOutcome] = CanEqual.derived
}

/** Why a login was rejected — for this process's own log, and for nowhere else.
  *
  * The caller is told "those credentials are not right" whichever of these it was. Telling them that the user
  * exists but the password is wrong is a way to enumerate accounts, and it is the difference between an
  * attacker guessing passwords for one name they confirmed and guessing pairs.
  */
enum RejectionReason {
  case NoSuchUser
  case WrongPassword

  def label: String = this match {
    case NoSuchUser => "no such user"
    case WrongPassword => "wrong password"
  }
}

object RejectionReason {
  given CanEqual[RejectionReason, RejectionReason] = CanEqual.derived
}

/** What a new password has to be.
  *
  * Length and nothing else, on purpose. Composition rules — a digit, a symbol, mixed case — are what NIST SP
  * 800-63B stopped recommending, because they push people towards `Password1!` and towards writing it down. A
  * minimum length is the rule that still earns its place.
  */
object PasswordRules {

  val MinimumLength: Int = 12

  /** The maximum is a denial-of-service bound rather than a security rule: a key derivation function will
    * happily spend a second hashing a megabyte somebody pasted.
    */
  val MaximumLength: Int = 1024

  private val Field: String = "newPassword"

  def check(password: Secret[String]): Either[ValidationError, Secret[String]] = {
    val length = password.value.length

    if length < MinimumLength then
      Left(
        ValidationError.Range(
          Field,
          Some(s"$MinimumLength characters"),
          Some(s"$MaximumLength characters"),
          s"$length characters"
        )
      )
    else if length > MaximumLength then
      Left(
        ValidationError.Range(
          Field,
          Some(s"$MinimumLength characters"),
          Some(s"$MaximumLength characters"),
          s"$length characters"
        )
      )
    else Right(password)
  }
}
