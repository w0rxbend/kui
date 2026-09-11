package kui.ksql.application

import java.time.Instant

import cats.Monad
import cats.effect.kernel.Clock
import cats.syntax.all.*
import fs2.Stream

import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.ksql.domain.*
import kui.security.Principal

/** What the object read produced for one cluster.
  *
  * Two cases and not an `Either` that might be empty. `NotConfigured` is a deployment with no
  * `kui.clusters.<n>.ksql.url`, which is the ordinary case and reaches the browser as `not_configured` with a
  * 200. Rule A3 keeps `Section` out of this layer, so the distinction is made in this vocabulary and
  * `KsqlMapping` turns it into the wire's.
  */
enum KsqlListing {
  case NotConfigured
  case Answered(objects: Either[KuiError, KsqlObjects])
}

object KsqlListing {
  given CanEqual[KsqlListing, KsqlListing] = CanEqual.derived
}

/** What running this statement would do, and the confirmation it needs if it needs one.
  *
  * @param warnings
  *   sentences for the person about to confirm, in the order they should be read. **A warning that cannot
  *   name a figure says so in words**: a `DROP … DELETE TOPIC` whose object this cluster's ksqlDB does not
  *   list produces *"KUI could not find … so it cannot say which Kafka topic would be deleted"* rather than a
  *   warning about a topic whose name was guessed.
  * @param token
  *   `None` when nothing needs confirming, which is every statement that is not destructive.
  */
final case class StatementPlan(
    statement: KsqlStatement,
    warnings: List[String],
    token: Option[String],
    expiresAt: Option[Instant],
    computedAt: Instant
)

object StatementPlan {
  given CanEqual[StatementPlan, StatementPlan] = CanEqual.derived
}

/** A statement that ran, and what it produced. */
final case class ExecutedStatement(
    statement: KsqlStatement,
    outcome: StatementOutcome,
    executedAt: Instant
)

object ExecutedStatement {
  given CanEqual[ExecutedStatement, ExecutedStatement] = CanEqual.derived
}

/** The four things a caller can do to this service.
  *
  * ==`Left` on the read is only ever a wrong request==
  *
  * A cluster KUI has never heard of is a 404, because the caller followed a link to something that does not
  * exist. Everything else answers 200 with a document that says what it knows, exactly as the metrics, alerts
  * and connect services do: the ksqlDB screen sits in a product where the rest of the screens work, and a 4xx
  * would make a server behaving exactly as designed — down, starting, half-answering — indistinguishable from
  * a broken KUI.
  *
  * ==The writes are the other way round==
  *
  * A statement is a request to change something, so its failure is the caller's answer: a refused statement
  * must not answer 200 with a document saying it did not happen, or a browser that showed a success toast
  * would be telling the truth about the response and lying about the cluster.
  */
trait KsqlUseCases[F[_]] {

  def objects(principal: Principal, cluster: ClusterId): F[Either[KuiError, KsqlListing]]

  def plan(principal: Principal, cluster: ClusterId, statement: String): F[Either[KuiError, StatementPlan]]

  def execute(
      principal: Principal,
      cluster: ClusterId,
      statement: String,
      token: Option[String]
  ): F[Either[KuiError, ExecutedStatement]]

  /** A push query, opened.
    *
    * The outer `F` fails when the request cannot start at all — a cluster that does not exist, a deployment
    * with no ksqlDB, a statement that is not a push query — and the inner stream carries everything that goes
    * wrong afterwards, because a failure after the response headers have gone has to reach the browser as an
    * `error` frame rather than as a connection that simply closes (ADR-035).
    */
  def stream(
      principal: Principal,
      cluster: ClusterId,
      statement: String
  ): F[Either[KuiError, Stream[F, Either[KuiError, QueryFrame]]]]
}

object KsqlUseCases {

  /** The sentence a push query gets when it is sent to the JSON endpoint, and a finishing statement gets when
    * it is sent to the stream. Both name the address that *does* answer, because "wrong endpoint" with no
    * alternative is a dead end for whoever is holding a `curl`.
    */
  val PushQueryElsewhere: String =
    "a SELECT ... EMIT CHANGES is a push query: it never finishes, so it is answered by " +
      "GET .../ksql/stream?statement=... and not here"

  val FinishingQueryElsewhere: String =
    "this stream answers push queries only; a statement that finishes is answered by " +
      "POST .../ksql/statements"

  def make[F[_]: {Monad, Clock}](
      profiles: ClusterKsqlSource[F],
      tokens: KsqlPlanToken[F],
      guard: MutationGuard[F]
  ): KsqlUseCases[F] =
    new KsqlUseCases[F] {

      def objects(principal: Principal, cluster: ClusterId): F[Either[KuiError, KsqlListing]] =
        profiles.profileOf(cluster).flatMap {
          case Left(error) => error.asLeft[KsqlListing].pure[F]
          case Right(profile) if !profile.configured =>
            KsqlListing.NotConfigured.asRight[KuiError].pure[F]
          case Right(_) =>
            profiles.client(cluster).flatMap {
              case None => KsqlListing.Answered(notWired(cluster).asLeft).asRight[KuiError].pure[F]
              case Some(client) =>
                client.objects.map(answer => KsqlListing.Answered(answer).asRight[KuiError])
            }
        }

      /** The plan, which changes nothing and is therefore **not** inside the guard.
        *
        * It writes no audit record for the same reason: an audit trail that recorded every preview would
        * record the statements nobody ran, and "who dropped this topic" would then have to be answered by
        * ignoring most of its own rows. The read-only refusal still applies — it is at the route, because
        * `Action.KsqlExecute.isAlter` is what the plan endpoint declares — and ADR-055 §7 says why a preview
        * is gated by the permission of the thing it previews.
        */
      def plan(
          principal: Principal,
          cluster: ClusterId,
          raw: String
      ): F[Either[KuiError, StatementPlan]] =
        KsqlStatement.parse(raw) match {
          case Left(problem) => invalid(problem).asLeft[StatementPlan].pure[F]
          case Right(statement) =>
            profiles.profileOf(cluster).flatMap {
              case Left(error) => error.asLeft[StatementPlan].pure[F]
              case Right(profile) if !profile.configured =>
                notConfigured(cluster).asLeft[StatementPlan].pure[F]
              case Right(_) =>
                for {
                  now <- Clock[F].realTimeInstant
                  described <- describe(cluster, statement)
                  minted <-
                    if statement.destructive then
                      tokens.mint(cluster, statement.canonical, now.plus(KsqlPlanToken.Ttl)).map(Some(_))
                    else none[String].pure[F]
                } yield StatementPlan(
                  statement = statement,
                  warnings = described,
                  token = minted,
                  expiresAt = minted.as(now.plus(KsqlPlanToken.Ttl)),
                  computedAt = now
                ).asRight[KuiError]
            }
        }

      def execute(
          principal: Principal,
          cluster: ClusterId,
          raw: String,
          token: Option[String]
      ): F[Either[KuiError, ExecutedStatement]] =
        KsqlStatement.parse(raw) match {
          case Left(problem) => invalid(problem).asLeft[ExecutedStatement].pure[F]

          // Before the guard, and deliberately: a push query sent here ran nothing, changed nothing and is
          // a malformed request rather than an attempt to alter a cluster. Auditing it would put rows in
          // the trail that describe things that did not happen.
          case Right(statement) if statement.push =>
            ApplicationError
              .Invalid(PushQueryElsewhere, Nil)
              .asLeft[ExecutedStatement]
              .pure[F]

          case Right(statement) =>
            guard.guard(principal, cluster, statement, KsqlPlanToken.Operation) {
              profiles.profileOf(cluster).flatMap {
                case Left(error) => error.asLeft[ExecutedStatement].pure[F]

                case Right(profile) if !profile.configured =>
                  notConfigured(cluster).asLeft[ExecutedStatement].pure[F]

                case Right(_) =>
                  confirmed(cluster, statement, token).flatMap {
                    case Left(refusal) => refusal.asLeft[ExecutedStatement].pure[F]
                    case Right(_) =>
                      profiles.client(cluster).flatMap {
                        case None => notWired(cluster).asLeft[ExecutedStatement].pure[F]
                        case Some(client) =>
                          client.execute(statement).flatMap {
                            case Left(error) => error.asLeft[ExecutedStatement].pure[F]
                            case Right(outcome) =>
                              Clock[F].realTimeInstant
                                .map(now => ExecutedStatement(statement, outcome, now).asRight[KuiError])
                          }
                      }
                  }
              }
            }
        }

      def stream(
          principal: Principal,
          cluster: ClusterId,
          raw: String
      ): F[Either[KuiError, Stream[F, Either[KuiError, QueryFrame]]]] =
        KsqlStatement.parse(raw) match {
          case Left(problem) => invalid(problem).asLeft[Stream[F, Either[KuiError, QueryFrame]]].pure[F]

          case Right(statement) if !statement.push =>
            ApplicationError
              .Invalid(FinishingQueryElsewhere, Nil)
              .asLeft[Stream[F, Either[KuiError, QueryFrame]]]
              .pure[F]

          case Right(statement) =>
            profiles.profileOf(cluster).flatMap {
              case Left(error) => error.asLeft[Stream[F, Either[KuiError, QueryFrame]]].pure[F]

              // The same refusal `MutationGuard` makes for a statement, made here because a push query is
              // not a mutation and never reaches that guard. A push query asks ksqlDB to start and hold a
              // query, which `Action.KsqlExecute` classifies as altering, and a read-only cluster whose
              // ksqlDB anybody can set queries running on is not read-only in any sense an operator means.
              // ADR-055 §3 states the cost: a read-only cluster cannot watch a stream either.
              case Right(profile) if profile.readOnly =>
                ApplicationError
                  .Refused(
                    ErrorCode.ReadOnly,
                    s"cluster ${profile.displayName} is configured read-only, so no ksqlDB query is started"
                  )
                  .asLeft[Stream[F, Either[KuiError, QueryFrame]]]
                  .pure[F]

              case Right(profile) if !profile.configured =>
                notConfigured(cluster).asLeft[Stream[F, Either[KuiError, QueryFrame]]].pure[F]

              case Right(_) =>
                profiles.client(cluster).map {
                  case None => notWired(cluster).asLeft[Stream[F, Either[KuiError, QueryFrame]]]
                  case Some(client) => client.rows(statement).asRight[KuiError]
                }
            }
        }

      /** The plan's warnings, and the one figure it goes and looks up.
        *
        * A destructive statement's whole content is *which topic disappears*, so the object it names is
        * looked up in the cluster's own listing and the topic behind it is quoted. When the lookup fails —
        * the server did not answer, or does not know the object — the warning says exactly that instead of
        * naming a topic nobody measured.
        */
      private def describe(cluster: ClusterId, statement: KsqlStatement): F[List[String]] =
        if !statement.destructive then (if statement.push then List(PushQueryElsewhere) else Nil).pure[F]
        else
          profiles.client(cluster).flatMap {
            case None => List(cannotName(statement, "KUI has no client for this cluster's ksqlDB")).pure[F]
            case Some(client) =>
              client.objects.map {
                case Left(error) =>
                  List(cannotName(statement, s"the ksqlDB cluster did not answer: ${error.message}"))
                case Right(objects) =>
                  topicOf(objects, statement.target) match {
                    case Some(topic) =>
                      List(
                        s"This deletes the Kafka topic '$topic' and every record in it. Nothing in KUI " +
                          "can undo it."
                      )
                    case None =>
                      List(
                        cannotName(
                          statement,
                          "this cluster's ksqlDB does not list an object by that name"
                        )
                      )
                  }
              }
          }

      private def cannotName(statement: KsqlStatement, why: String): String =
        s"This deletes the Kafka topic behind ${statement.target.getOrElse("the object it names")} and " +
          s"every record in it. KUI cannot say which topic that is: $why."

      /** The confirmation, checked only for the statements that need one.
        *
        * A token sent with a harmless statement is **ignored rather than refused**: a client that always
        * sends back the token it last received is doing nothing wrong, and refusing it would make the editor
        * fail on the request after a confirmed one.
        */
      private def confirmed(
          cluster: ClusterId,
          statement: KsqlStatement,
          token: Option[String]
      ): F[Either[KuiError, Unit]] =
        if !statement.destructive then ().asRight[KuiError].pure[F]
        else
          token match {
            case None =>
              ApplicationError
                .Invalid(
                  "this statement deletes a Kafka topic, so it needs a confirmation: plan it at " +
                    "POST .../ksql/statements/plan and send the token it answers with",
                  Nil
                )
                .asLeft[Unit]
                .pure[F]
            case Some(value) =>
              Clock[F].realTimeInstant.flatMap(now => tokens.verify(cluster, statement.canonical, value, now))
          }
    }

  private def invalid(problem: StatementProblem): KuiError =
    ApplicationError.Invalid(problem.message, Nil)

  /** A cluster that configured no ksqlDB, asked to do something with one.
    *
    * `KUI-UNSUPPORTED`, which is the same classification the read gives it as a section: one fact, one code,
    * whether it arrives as a `not_configured` section or as a status.
    */
  private def notConfigured(cluster: ClusterId): KuiError =
    ApplicationError.Unsupported(
      s"no ksqlDB is configured for cluster '${cluster.value}' (kui.clusters.<n>.ksql.url)"
    )

  /** Configured, and no client built for it. A wiring failure rather than a deployment choice, and it says
    * so: reporting it as "not configured" would hide a KUI defect behind a row that looks deliberately
    * switched off.
    */
  private def notWired(cluster: ClusterId): KuiError =
    ApplicationError.InvalidState(
      s"a ksqlDB is configured for cluster '${cluster.value}' and this process could not build a client " +
        "for it"
    )

  /** The Kafka topic behind the stream or table a `DROP` named, if this listing has one. */
  private def topicOf(objects: KsqlObjects, target: Option[String]): Option[String] =
    target.flatMap(name =>
      objects.items.collectFirst {
        case KsqlObject.Stream(objectName, topic, _) if objectName.equalsIgnoreCase(name) => topic
        case KsqlObject.Table(objectName, topic, _, _) if objectName.equalsIgnoreCase(name) => topic
      }
    )
}
