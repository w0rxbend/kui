package kui.ui.shell

import scala.concurrent.{ExecutionContext, Future, Promise}

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import io.circe.syntax.*
import munit.FunSuite
import sttp.client4.testing.BackendStub
import sttp.client4.{Backend, GenericRequest}
import sttp.model.{StatusCode, Uri}

import kui.gateway.contract.CapabilityEndpoints
import kui.gateway.contract.dto.{AuthMeResponse, PermissionDto, PrincipalDto}
import kui.kernel.ClusterId
import kui.security.PrincipalKind
import kui.security.rbac.{Action, ClusterScope, Resource}
import kui.ui.kernel.api.{ApiClient, SttpApiClient}
import kui.ui.kernel.state.Auth

/** Start-up's half of the CSRF mechanism (ADR-019).
  *
  * `ApiClientSuite` in the kernel proves that a client which *has* a token puts it on a mutation. Nothing
  * proved that anything ever gave the client a token, and for a while nothing did: `Auth.refresh` had no
  * production caller at all, so `csrfToken` stayed `None` for the life of the page and the gateway answered
  * every non-`GET` with `403` and "X-Csrf-Token is missing". These tests cover the wiring itself.
  */
class ShellSessionSuite extends FunSuite {

  given executionContext: ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  /** A backend that remembers every request and answers `/auth/me` with a real session. */
  private final class Gateway(token: String) {

    val auth: Auth = new Auth

    var seen: List[GenericRequest[?, ?]] = Nil

    private val meBody: String =
      AuthMeResponse(PrincipalDto("ada", List("admin"), "session"), token, "basic", Nil).asJson.noSpaces

    val backend: Backend[Future] =
      BackendStub
        .asynchronousFuture(using executionContext)
        .whenRequestMatches { request =>
          seen = request :: seen
          true
        }
        .thenRespondAdjust(meBody, StatusCode.Ok)

    val client: ApiClient =
      new SttpApiClient(Uri.unsafeParse("http://gateway.test/api/v1"), auth, backend)
  }

  /** Issues the one mutating request M0 has, and completes when it has been answered. */
  private def probe(gateway: Gateway)(using Owner): Future[Unit] = {
    val done = Promise[Unit]()
    val call = gateway.client.call(CapabilityEndpoints.probe, "clusters")
    call.foreach(_ => done.trySuccess(()): Unit): Unit
    done.future
  }

  /** Completes as soon as the session has adopted a token, so the test can act on a live session rather
    * than guessing how many event-loop turns the fetch takes.
    */
  private def tokenArrives(auth: Auth)(using Owner): Future[String] = {
    val arrived = Promise[String]()
    auth.csrfToken.signal.foreach(_.foreach(token => arrived.trySuccess(token): Unit)): Unit
    arrived.future
  }

  test("aMutationIssuedAfterStartUpCarriesTheCsrfHeaderTheGatewayDemands") {
    given owner: ManualOwner = new ManualOwner
    val gateway = new Gateway("token-from-auth-me")

    Shell.startSession(gateway.client, gateway.auth)

    for {
      _ <- tokenArrives(gateway.auth)
      // The only mutation M0 has: the "Retry now" button in a degraded feature's fallback panel.
      _ <- probe(gateway)
    } yield {
      val mutation = gateway.seen.head
      assertEquals(mutation.uri.path.lastOption, Some("probe"))
      assertEquals(mutation.header(ApiClient.CsrfHeader), Some("token-from-auth-me"))
      owner.killSubscriptions()
    }
  }

  test("startUpAsksTheGatewayWhoTheBrowserIsAndAdoptsTheAnswer") {
    given owner: ManualOwner = new ManualOwner
    val gateway = new Gateway("token-1")

    Shell.startSession(gateway.client, gateway.auth)

    tokenArrives(gateway.auth).map { token =>
      assertEquals(token, "token-1")
      assertEquals(gateway.seen.map(_.uri.toString).count(_.endsWith("/auth/me")), 1)
      assertEquals(gateway.auth.principal.now().map(_.kind), Some(PrincipalKind.Session))
      assertEquals(gateway.auth.authType.now(), "basic")
      owner.killSubscriptions()
    }
  }

  test("anExpiredSessionIsReEstablishedWithoutAReload") {
    // A session times out, some request comes back `401`, `ApiClient` clears the token. If nothing
    // re-fetches `/auth/me`, every later mutation on that page is a `403` until the user reloads.
    given owner: ManualOwner = new ManualOwner
    val gateway = new Gateway("token-1")

    Shell.startSession(gateway.client, gateway.auth)

    for {
      _ <- tokenArrives(gateway.auth)
      _ = gateway.auth.markExpired()
      _ = assertEquals(gateway.auth.csrfToken.now(), None)
      restored <- tokenArrives(gateway.auth)
    } yield {
      assertEquals(restored, "token-1")
      assertEquals(gateway.seen.map(_.uri.toString).count(_.endsWith("/auth/me")), 2)
      owner.killSubscriptions()
    }
  }

  test("anUnrecognisedPrincipalKindDoesNotStopTheBrowserFromStarting") {
    assertEquals(
      Shell
        .toAuthInfo(AuthMeResponse(PrincipalDto("x", Nil, "martian"), "t", "none", Nil))
        .principal
        .map(_.kind),
      Some(PrincipalKind.Anonymous)
    )
  }

  test("aPermissionArrivesWithItsPatternCompiledAndItsClustersScoped") {
    val granted = Shell.toPermission(
      PermissionDto(List("production", "staging"), "TOPIC", Some("orders.*"), List("DELETE", "VIEW"))
    )

    assertEquals(granted.map(_.permission.resource), Some(Resource.Topic))
    assertEquals(
      granted.map(_.clusters),
      Some(ClusterScope.Named(Set(ClusterId.unsafe("production"), ClusterId.unsafe("staging"))))
    )
    assert(granted.exists(_.permission.value.exists(_.matches("orders-dlq"))))
    assert(granted.exists(_.permission.actions.contains(Action.TopicDelete)))
  }

  test("theStarInTheClusterListMeansEveryCluster") {
    // A cluster id is a lowercase slug, so `*` cannot collide with a real one — which is what lets the
    // gateway say "everywhere" in a response it computes without knowing what the clusters are.
    assertEquals(
      Shell.toPermission(PermissionDto(List("*"), "TOPIC", Some(".*"), List("VIEW"))).map(_.clusters),
      Some(ClusterScope.Every)
    )
  }

  test("aGrantThisBrowserCannotUnderstandIsDroppedRatherThanKept") {
    // Dropping hides a control; keeping offers one the server will refuse. The first is the safe way to
    // be wrong, and it means a gateway that grows a twelfth resource does not stop an older browser.
    assertEquals(Shell.toPermission(PermissionDto(List("*"), "QUANTUM", None, List("VIEW"))), None)
    assertEquals(Shell.toPermission(PermissionDto(List("*"), "TOPIC", None, List("TELEPORT"))), None)
    assertEquals(Shell.toPermission(PermissionDto(List("*"), "TOPIC", Some("orders["), List("VIEW"))), None)
  }

  test("anEmptyTokenIsTreatedAsNoTokenRatherThanAnEmptyHeader") {
    assertEquals(
      Shell.toAuthInfo(AuthMeResponse(PrincipalDto("x", Nil, "anonymous"), "", "none", Nil)).csrfToken,
      None
    )
  }
}
