package kui.gateway.api.openapi

import scala.collection.immutable.ListMap

import sttp.apispec.openapi.{OpenAPI, Operation, Parameter, ParameterIn, PathItem, Reference}

import kui.gateway.api.EdgeHeaders

/** The published API as a **browser** is allowed to see it, and the source the browser's TypeScript client is
  * generated from.
  *
  * ==Why a second document exists at all==
  *
  * `docs/api/openapi.json` describes the contract KUI's own services speak. That contract includes a family
  * of headers the gateway mints and the services trust -- `X-Kui-Principal` says who the caller is (ADR-020)
  * -- and Tapir quite correctly documents them as required inputs, because to a service they are.
  *
  * A browser must never send one. [[EdgeHeaders]] strips every inbound `X-Kui-*` header at the edge before
  * anything else runs (ADR-040), precisely so that a user who types `X-Kui-Principal: admin` into a `fetch`
  * call achieves nothing.
  *
  * Those two true statements collide the moment a browser client is *generated* from the service-facing
  * document: the generated types oblige every call site to supply a header the gateway is guaranteed to throw
  * away. The type system would then be enforcing the exact inverse of the security boundary, and the friendly
  * way out -- casting around the types at each call site -- is how a forged header eventually gets sent for
  * real. (This is not hypothetical: generating a client from the aggregate and compiling it made `tsc` demand
  * `header: { "X-Kui-Principal": string }` at a consumer-group call site.)
  *
  * So the browser gets its own document. It is not written by hand and it is not maintained beside the other
  * one; it is **computed** from it, by exactly the rule the runtime applies.
  *
  * ==The one rule, and why it is shared rather than restated==
  *
  * A header parameter survives into the browser document if and only if `EdgeHeaders.isForbidden` says the
  * gateway would let it through. Not a copy of that rule -- a call to it. A second list of forbidden prefixes
  * would be correct on the day it was written and silently wrong on the day a fifth `X-Kui-*` header is
  * added, and "silently wrong" here means the browser's generated types would start advertising an internal
  * trust header as something a caller may set.
  *
  * Everything else stays, deliberately. `X-Csrf-Token` is required on 19 operations and `If-Match` on two,
  * and the browser genuinely does send both: leaving them in means the generated types *force* a call site to
  * supply them, which is the whole reason for generating types from a contract.
  *
  * Only header parameters are considered. Path, query and cookie parameters are the caller's business by
  * definition, and a cookie is set by the server and sent by the browser without any client code naming it.
  */
object BrowserProjection {

  /** The edge view of a document: every parameter the edge would strip, removed.
    *
    * Total and pure, so the rule can be tested by writing down a document and reading the answer, rather than
    * by starting a gateway and hoping the interesting operation was exercised.
    */
  def project(document: OpenAPI): OpenAPI =
    document.copy(
      paths = document.paths.copy(pathItems = document.paths.pathItems.map { case (path, item) =>
        path -> projectPathItem(item)
      }),
      components = document.components.map(components =>
        components.copy(parameters = components.parameters.filter { case (_, parameter) =>
          keep(parameter)
        })
      )
    )

  /** One path's operations, each projected, plus the parameters the path itself declares for all of them.
    *
    * Written out method by method rather than through a generic traversal because `PathItem` has no such
    * traversal, and a `copy` that names all nine verbs is easier to check by eye than a reflective one.
    */
  private def projectPathItem(item: PathItem): PathItem =
    item.copy(
      get = item.get.map(projectOperation),
      put = item.put.map(projectOperation),
      post = item.post.map(projectOperation),
      delete = item.delete.map(projectOperation),
      options = item.options.map(projectOperation),
      head = item.head.map(projectOperation),
      patch = item.patch.map(projectOperation),
      trace = item.trace.map(projectOperation),
      parameters = item.parameters.filter(keep)
    )

  private def projectOperation(operation: Operation): Operation =
    operation.copy(parameters = operation.parameters.filter(keep))

  /** Whether one parameter belongs in a document a browser generates a client from.
    *
    * A `$ref` to a component is kept: the component it points at is filtered by [[project]] itself, and a
    * dangling reference would be a broken document rather than a stripped header. In practice KUI's generator
    * inlines every parameter, so this branch has never been taken -- it is here so that it stays correct if
    * that ever changes.
    */
  private def keep(parameter: Either[Reference, Parameter]): Boolean =
    parameter match {
      case Left(_) => true
      case Right(declared) =>
        declared.in != ParameterIn.Header || !EdgeHeaders.isForbidden(declared.name)
    }

  /** The header parameter names a document still asks a caller for, by operation. Test material: it is what
    * lets a suite assert *which* headers survived, not merely how many.
    */
  def headerParameters(document: OpenAPI): ListMap[String, List[String]] =
    ListMap.from(document.paths.pathItems.toList.flatMap { case (path, item) =>
      val operations = List(
        "get" -> item.get,
        "put" -> item.put,
        "post" -> item.post,
        "delete" -> item.delete,
        "options" -> item.options,
        "head" -> item.head,
        "patch" -> item.patch,
        "trace" -> item.trace
      )
      operations.collect { case (method, Some(operation)) =>
        s"$method $path" -> (operation.parameters ++ item.parameters).collect {
          case Right(parameter) if parameter.in == ParameterIn.Header => parameter.name
        }
      }
    })
}
