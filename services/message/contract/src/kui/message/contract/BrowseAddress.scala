package kui.message.contract

import kui.contracts.sse.SseEventName

/** Where a browse lives and what its parts are called: the path segments, the query-parameter names and the
  * names of the events the stream emits.
  *
  * ==Why these are here and not beside the endpoint==
  *
  * `MessageEndpoints` cannot be cross-compiled. Its output is a server-sent-event body, which needs `fs2` and
  * a server-side stream body, and neither links for Scala.js — that is why it lives in the service's `api`
  * module and says so. But the browser still has to build the very URL that endpoint answers on, and it
  * cannot do that from a value it is unable to see.
  *
  * The two ways out of that are: type the strings a second time in `frontend/ui-messages`, or put them in the
  * one module both halves compile. The first is the failure this project has already shipped once — M1's
  * dashboard decoded a document nobody sent, both suites green, because each side held its own idea of the
  * payload — and a renamed segment would behave identically: the frontend would keep compiling and would 404
  * at runtime. So they are here, and both halves read them.
  *
  * `BrowseParams`, next door, is the other half of the same grammar: this file names the parameters and that
  * one says how their values are spelled. Between them a browse URL is completely described by the contract
  * module, which is what lets a suite on either side assert against the same source.
  */
object BrowseAddress {

  // --- The path ---------------------------------------------------------------------------------

  val ClustersSegment: String = "clusters"
  val TopicsSegment: String = "topics"
  val MessagesSegment: String = "messages"
  val StreamSegment: String = "stream"

  val ClusterIdParam: String = "clusterId"
  val TopicNameParam: String = "topicName"

  // --- The query --------------------------------------------------------------------------------

  /** Where to start reading. Repeats; `BrowseParams.seekModeCodec` owns the grammar of each value. */
  val SeekParam: String = "seekTo"

  /** Which partitions to read. Repeats; absent means every partition. */
  val PartitionParam: String = "partition"

  val DirectionParam: String = "direction"
  val LimitParam: String = "limit"
  val IsolationParam: String = "isolation"
  val KeySerdeParam: String = "keySerde"
  val ValueSerdeParam: String = "valueSerde"

  /** A plain substring the decoded record must contain. `q`, the same name every other list screen uses. */
  val QueryParam: String = "q"

  /** Tail mode: start at the end and keep the stream open. */
  val LiveParam: String = "live"

  /** The registered smart filter this browse runs (MS-007, ADR-017).
    *
    * An id and not the expression itself, because a CEL program can be a paragraph and this parameter is in a
    * URL somebody sends to a colleague.
    */
  val FilterIdParam: String = "filterId"

  /** The expression the id was minted from, sent alongside it (ADR-017).
    *
    * It looks redundant and is the opposite. The id is `sha256(source)`, so any replica can check that the
    * two agree — and a replica that has never seen this id, which is every replica after a restart and half
    * of them behind a load balancer, compiles the carried source instead of telling the user their filter has
    * expired. Kafbat's equivalent id is salted per process, which is exactly the failure this avoids.
    */
  val FilterSourceParam: String = "filterSource"

  /** Where a filter is registered and where one is tried against a single record. */
  val FiltersSegment: String = "filters"
  val TestSegment: String = "test"

  /** The signed continuation from a finished browse (ADR-026): "carry on from where that stopped".
    *
    * It replaces the start position rather than adding to one, so it is refused alongside `seekTo` rather
    * than resolved by a precedence rule — a caller who sent both means one of them, and a rule about which
    * wins is a thing they would have to know instead of be told.
    *
    * Everything the next page needs is inside it: the per-partition offsets, the direction, the page size,
    * the serdes and the saved filter. That is deliberate and is why paging works behind a load balancer: the
    * reference product keeps paging state in a process-local cache, so the id one replica hands out means
    * nothing to its neighbour, and "next page" stops working the moment a request is routed elsewhere.
    */
  val CursorParam: String = "cursor"

  // --- The events -------------------------------------------------------------------------------

  /** The names of the events a browse emits, over and above the shared ones every KUI stream sends.
    *
    * `phase`, `done`, `error` and `heartbeat` are `SseEventName`'s and are handled by the kernel's transport
    * for every stream in the product; these two are this stream's own. A client that listened for a name the
    * server does not send would sit on an open connection receiving nothing, which looks exactly like a topic
    * with no records in it — so there is one spelling and both ends read it.
    */
  object Events {
    val Phase: String = SseEventName.Phase
    val Message: String = "message"
    val Consumed: String = "consumed"

    /** What a browse client subscribes to. The shared names are deliberately absent: the kernel's `Sse`
      * handles `error`, `done` and `heartbeat` itself and rejects callers that list them.
      */
    val browse: List[String] = List(Phase, Message, Consumed)
  }
}
