package kui.identity.app

import cats.effect.ExitCode

import kui.testkit.KuiIOSuite

/** The one rule this tool exists to enforce: the password does not travel as an argument.
  *
  * An argument is visible in `ps` to every user on the machine and is written into the shell's history file,
  * which is why `HashPassword` reads standard input and refuses everything else. The refusal was carried by
  * no case at all — this module shipped a test target with no test source — so the check could have been
  * dropped, or inverted, with the whole repository green.
  */
final class HashPasswordSuite extends KuiIOSuite {

  test("a password passed as an argument is refused, and nothing is hashed") {
    // Exit 2 rather than 1: 1 is "the password you typed is not acceptable", and a script that
    // distinguishes them is distinguishing "you used this wrongly" from "try a better password".
    // The refusal also has to happen *before* standard input is read, which is what makes this case
    // safe to run in a suite that has no standard input to give it.
    HashPassword.run(List("hunter2")).assertEquals(ExitCode(2))
  }

  test("several arguments are refused the same way") {
    HashPassword.run(List("--stdin", "hunter2")).assertEquals(ExitCode(2))
  }
}
