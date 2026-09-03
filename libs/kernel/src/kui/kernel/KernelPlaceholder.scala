package kui.kernel

/** Placeholder so that `libs/kernel` is a compilable, non-empty module from the first commit.
  *
  * A Scala file containing only a package clause is not enough: the compiler warns
  * "No class, trait or object is defined in the compilation unit", and BUILD-002 turns warnings
  * into errors, so the module needs at least one definition to survive its own quality gate.
  *
  * KERN-001 replaces this with the real kernel vocabulary (`KuiError`, identifiers, refinements).
  */
object KernelPlaceholder
