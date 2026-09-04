package kui.consumer.contract.dto

/** Everything this contract module needs to put a value on the wire, gathered into one import.
  *
  * A contract file that wrote out its own `import kui.contracts.KernelCodecs.given`,
  * `import kui.contracts.consumer.GroupCodecs.given`, `import kui.contracts.ErrorEnvelope.given` and
  * `import kui.contracts.KernelSchemas.given` would be four chances to forget one, and forgetting one is not
  * a compile error in the interesting case — it is a *different* given being found, which is how two
  * endpoints in the same service end up rendering a timestamp two different ways. M1's integration found
  * exactly that, across two screens.
  *
  * So the rule in this module is: `import kui.consumer.contract.dto.ConsumerCodecs.given`, and nothing else.
  * Everything below is re-exported from where it is declared — none of it is declared here, because a codec
  * for a shared type declared in a service's contract module is a codec the next service will declare again
  * (ADR-007, and build rule A14 for the vocabulary in particular).
  */
object ConsumerCodecs {

  /** Kernel identifiers: `ClusterId`, `GroupId`, `TopicName`, `PageToken`, `SortOrder`. */
  export kui.contracts.KernelCodecs.given

  /** The Tapir schemas and the path/query codecs for those same identifiers. */
  export kui.contracts.KernelSchemas.given

  /** `Instant`, in the one RFC 3339 rendering KUI uses everywhere (`ErrorEnvelope.formatTimestamp`). */
  export kui.contracts.ErrorEnvelope.given

  /** The consumer-group vocabulary: `GroupState`, `GroupProtocol`, `ResetTarget`, `LagAnomaly`. */
  export kui.contracts.consumer.GroupCodecs.given
}
