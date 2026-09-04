package kui.identity.app

import cats.effect.{ExitCode, IO, IOApp}

import kui.identity.domain.PasswordRules
import kui.identity.infrastructure.Pbkdf2PasswordHasher
import kui.kernel.Secret

/** Turning a password into the string that goes into `kui.auth.users[].passwordHash`.
  *
  * {{{
  * ./mill services.identity.app.runMain kui.identity.app.HashPassword
  * }}}
  *
  * ==Why it reads the password from standard input and not from an argument==
  *
  * A command-line argument is visible in `ps` to every user on the machine, and it is written into the
  * shell's history file. Standard input is neither. It is the same reason `kui.auth.users[].passwordHash`
  * accepts `env:NAME` and `file:PATH`: the shapes that leak are the convenient ones, so the tool has to make
  * the safe shape the only one.
  *
  * ==What it prints==
  *
  * One line: the encoded hash. Nothing else, so that `./mill ... HashPassword < password.txt >> kui.yaml`
  * composes, and so that a screenshot of the terminal carries no password.
  */
object HashPassword extends IOApp {

  private val Usage: String =
    "usage: HashPassword  — reads the password from standard input and prints the encoded hash"

  def run(args: List[String]): IO[ExitCode] =
    if args.nonEmpty then
      IO.println(
        s"$Usage\n\nThe password is deliberately not an argument: an argument is visible in 'ps' to " +
          "every user on this machine and is written to the shell's history."
      ).as(ExitCode(2))
    else
      for {
        typed <- IO.blocking(Option(scala.io.StdIn.readLine()).map(_.trim).getOrElse(""))
        exit <- PasswordRules.check(Secret(typed)) match {
          case Left(broken) => IO.println(broken.message).as(ExitCode(1))
          case Right(password) =>
            for {
              hasher <- Pbkdf2PasswordHasher.make[IO]
              hashed <- hasher.hash(password)
              _ <- IO.println(hashed.encoded)
            } yield ExitCode.Success
        }
      } yield exit
}
