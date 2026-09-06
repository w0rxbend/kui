package kui.gateway.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.ErrorEnvelope
import kui.gateway.contract.dto.SearchAnswerDto

/** What one press of the top bar's search field asks for.
  *
  * A record rather than a tuple because both halves of the product build it: the gateway's fold takes it as
  * one value, and the browser's client is generated from the same endpoint. Two positional `String`/`Int`
  * parameters would be one careless swap away from searching for `"10"` at a limit of `orders`.
  */
final case class SearchQuery(q: String, limit: Int)

object SearchQuery {
  given CanEqual[SearchQuery, SearchQuery] = CanEqual.derived
}

/** `GET /api/v1/search`, the one field at the top of every screen.
  *
  * The endpoint is the gateway's own and not any service's, because the question — "where is `orders`?" — is
  * one no single service can answer. It is a fold over three list endpoints that already exist, and ADR-049
  * records why the fold is here rather than in a seventh service.
  */
object SearchEndpoints {

  val SearchSegment: String = "search"

  val QueryParam: String = "q"
  val LimitParam: String = "limit"

  /** The shortest and longest query the endpoint will act on.
    *
    * The lower bound is one character rather than zero: a blank query matches every name there is (that is
    * the substring rule, not an accident), and answering a whole cluster's topic list to an empty field is a
    * request a browser makes by accident on every backspace.
    *
    * The upper bound is 200 because a search term is something a person typed. It is a bound on the *URL*
    * more than on the matcher — a query longer than any Kafka name can match nothing at all, so refusing it
    * costs a caller nothing and keeps the query out of every log line it would otherwise land in.
    */
  val MinQueryLength: Int = 1
  val MaxQueryLength: Int = 200

  /** How many hits of each kind the answer carries, and the largest a caller may ask for.
    *
    * The result panel is a drop-down under a text field, so ten of each kind is already more than it shows
    * without scrolling. The ceiling is refused rather than clamped, unlike a list endpoint's `pageSize`: a
    * page that clamps is still a page of a list somebody is walking through, while a search that quietly
    * returned fifty of the two hundred asked for would look like a cluster with fewer topics than it has.
    */
  val DefaultLimit: Int = 10
  val MaxLimit: Int = 50

  val queryParams: EndpointInput[SearchQuery] =
    query[String](QueryParam)
      .description("What to look for: a case-insensitive substring of a topic, group or subject name")
      .validate(Validator.minLength[String](MinQueryLength).and(Validator.maxLength[String](MaxQueryLength)))
      .and(
        query[Int](LimitParam)
          .description("How many hits of each kind to return")
          .default(DefaultLimit)
          .validate(Validator.min(1).and(Validator.max(MaxLimit)))
      )
      .map(SearchQuery.apply.tupled)(params => (params.q, params.limit))

  val search: PublicEndpoint[SearchQuery, ErrorEnvelope, SearchAnswerDto, Any] =
    GatewayEndpoints.base.get
      .in(SearchSegment)
      .in(queryParams)
      .out(jsonBody[SearchAnswerDto])
      .name("gateway.search")
      .summary("Topics, consumer groups and subjects matching one query, across every cluster")
      .description(
        "Answers 200 whenever the query is well formed, however many of the three services could be " +
          "asked. A service this deployment does not route, or one that did not answer, contributes its " +
          "id to `partial` and no results; the other two still answer. A `q` outside 1 to 200 characters " +
          "is a 400 KUI-VALIDATION naming the field, because a search box with no term is a request the " +
          "browser should not have made rather than a cluster with nothing in it."
      )
      .tag("search")

  val all: List[AnyEndpoint] = List(search)
}
