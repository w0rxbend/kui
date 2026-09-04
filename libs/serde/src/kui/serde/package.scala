package kui.serde

/** The serde vocabulary, aliased from `libs/kernel`.
  *
  * `SerdeName`, `Target`, `SerdeUse` and `PayloadKind` were declared here originally. They moved to
  * `kui.kernel.serde` when the message service's `domain` needed them: rule A1 lets that module see
  * `libs/kernel` and nothing else, and the wire contract and the browser — a Scala.js build, which cannot see
  * a JVM module at all — name them too. One definition in the one module all four can reach beats four that
  * agree today (M3, MSG-017).
  *
  * These are type aliases rather than `export`s on purpose: an `export`ed opaque type is a *new* opaque type
  * to the compiler, so `kui.serde.SerdeName` and `kui.kernel.serde.SerdeName` would stop being the same type.
  * An alias is the same type, which is what makes the move invisible to every call site here.
  */
type SerdeName = kui.kernel.serde.SerdeName
val SerdeName: kui.kernel.serde.SerdeName.type = kui.kernel.serde.SerdeName

type Target = kui.kernel.serde.Target
val Target: kui.kernel.serde.Target.type = kui.kernel.serde.Target

type SerdeUse = kui.kernel.serde.SerdeUse
val SerdeUse: kui.kernel.serde.SerdeUse.type = kui.kernel.serde.SerdeUse

type PayloadKind = kui.kernel.serde.PayloadKind
val PayloadKind: kui.kernel.serde.PayloadKind.type = kui.kernel.serde.PayloadKind
