package kui.serde

import cats.effect.{Resource, Sync}
import cats.syntax.all.*

import kui.kernel.error.{ApplicationError, InfrastructureError, KuiError}
import kui.kernel.{ClusterId, TopicName}
import kui.serde.builtin.BuiltinSerdes

/** The serdes one cluster has, and the rules that pick between them. */
trait ClusterSerdes[F[_]] {

  /** Every serde configured for this cluster, in resolution order, whether or not it can work right now. */
  def all: List[Serde[F]]

  /** Pattern, then the cluster default, then `String` — with an explicit choice overriding all three.
    *
    * Never fails for want of a serde. It fails only when the caller named one this cluster does not have, or
    * named one whose backing service is down.
    */
  def resolve(topic: TopicName, target: Target, explicit: Option[SerdeName]): F[Either[KuiError, Serde[F]]]

  /** The picker's rows (MS-009), ordered, with exactly one marked preferred. */
  def suggest(topic: TopicName, target: Target, use: SerdeUse): F[List[SerdeSuggestion]]

  /** The serde this cluster's configuration selects for a topic, when that serde cannot work right now.
    *
    * `Some((name, reason))` means: the operator configured `name` for this topic, `name` failed to build — a
    * schema registry that could not be reached, most often — and [[resolve]] has therefore quietly answered
    * with something else. `None` means resolution's answer is the configured one, or that nothing was
    * configured for this topic at all.
    *
    * It exists because the fall-through is silent by design and the silence is what misleads. Without this, a
    * record written against a registry that is now down renders through the fallback with the note "the
    * payload is not valid UTF-8" — a true statement about a decode nobody asked for, which sends the reader
    * to look at their data instead of at their registry.
    */
  def unavailableChoice(topic: TopicName, target: Target): Option[(SerdeName, String)]

  /** The terminal case. Always present, and the reason no browse can fail on a decode. */
  def fallback: Serde[F]
}

/** A serde that has to be built — and torn down — per cluster.
  *
  * The built-ins are values and need none of this. A Schema-Registry serde owns an HTTP client and two
  * caches, so it is a `Resource`, and it may legitimately fail to build: a registry that is unreachable at
  * startup must not stop the service, it must produce a serde row the picker shows disabled with a reason
  * (ADR-032).
  *
  * That is why `create` returns `Resource[F, Either[String, Serde[F]]]` rather than `Resource[F, Serde[F]]`.
  * A `Left` is "configured, currently unusable, here is what to tell the user" — a state the type system
  * would otherwise force into either an exception or a silent absence, and the silent absence is the one
  * ADR-032 exists to prevent.
  */
trait SerdeFactory[F[_]] {
  def name: SerdeName
  def describe: SerdeDescription
  def create(profile: SerdeProfile): Resource[F, Either[String, Serde[F]]]
}

/** Everything the serde layer needs to know about a cluster.
  *
  * **Not `ClusterProfile`.** `ARCHITECTURE.md` §4.4 sketches `forCluster(profile: ClusterProfile)`, and that
  * signature cannot be written: `ClusterProfile` lives in `services/cluster`, `libs` may not depend on a
  * service (rule A5), and the whole point of `libs/serde` is that it is a library the message service uses
  * rather than a piece of the cluster service. This carries the three things resolution actually needs, and
  * the adapter that has a `ClusterProfile` in hand builds it — which is one function in one place, in the one
  * module that legitimately sees both.
  *
  * @param version
  *   the profile version this was built from. The registry keys its open registries on `(cluster, version)`
  *   so that a configuration edit rebuilds them and an unchanged profile does not.
  */
final case class SerdeProfile(
    cluster: ClusterId,
    version: Long,
    rules: SerdeResolution.Rules,
    properties: Map[String, String]
)

object SerdeProfile {

  /** A cluster with no serde configuration at all: the built-ins, and nothing else. */
  def unconfigured(cluster: ClusterId, version: Long): SerdeProfile =
    SerdeProfile(cluster, version, SerdeResolution.Rules.empty, Map.empty)

  given CanEqual[SerdeProfile, SerdeProfile] = CanEqual.derived
}

object ClusterSerdes {

  /** The built-ins, plus whatever the factories could build, for one cluster.
    *
    * A factory that fails to build is not an error here. Its serde appears in `all` and in `suggest` marked
    * unavailable with the reason it gave, `resolve` will not choose it implicitly, and asking for it by name
    * returns `KUI-SERDE-UNAVAILABLE`. The alternative — omitting it — leaves a user who configured Avro
    * staring at a picker with no Avro in it and no way to tell a typo from an outage.
    */
  def resource[F[_]: Sync](
      profile: SerdeProfile,
      factories: List[SerdeFactory[F]]
  ): Resource[F, ClusterSerdes[F]] =
    factories
      .traverse(factory => factory.create(profile).map(built => Entry(factory, built)))
      .map(entries => new Impl[F](profile, entries))

  /** One configured serde and whether it could be built. */
  final private case class Entry[F[_]](factory: SerdeFactory[F], built: Either[String, Serde[F]])

  final private class Impl[F[_]: Sync](profile: SerdeProfile, extras: List[Entry[F]])
      extends ClusterSerdes[F] {

    private val builtIns: List[Serde[F]] = BuiltinSerdes.all[F]

    val fallback: Serde[F] = FallbackSerde[F]

    /** Configured serdes first, then the built-ins.
      *
      * A serde an operator went to the trouble of configuring is more likely to be the one they want than a
      * primitive that is always there, so it sorts first in the picker. `Fallback` is in neither list: it is
      * where resolution ends, not something a user chooses.
      */
    val all: List[Serde[F]] = extras.flatMap(_.built.toOption) ++ builtIns

    /** Names that `resolve` may pick, which is not the same as names that exist: a configured serde whose
      * registry is down exists and cannot be picked.
      */
    private val usable: Set[SerdeName] = all.map(_.name).toSet

    private val unavailable: Map[SerdeName, String] =
      extras.collect { case Entry(factory, Left(reason)) => factory.name -> reason }.toMap

    def resolve(
        topic: TopicName,
        target: Target,
        explicit: Option[SerdeName]
    ): F[Either[KuiError, Serde[F]]] =
      Sync[F].pure {
        // An explicit choice of a serde that exists but cannot work now is its own answer, distinct from a
        // name nobody configured. MSG-029 renders it as a 503 *before* the stream starts, which is the
        // difference between "the schema registry is down" and a page of mojibake.
        explicit.flatMap(name => unavailable.get(name).map(name -> _)) match {
          case Some((name, reason)) =>
            Left(InfrastructureError.SerdeUnavailable(name.value, reason))
          case None =>
            SerdeResolution.resolve(profile.rules, usable, topic, target, explicit).flatMap { name =>
              all
                .find(_.name == name)
                .toRight(ApplicationError.Unsupported(s"the serde '${name.value}' is not available"))
            }
        }
      }

    def unavailableChoice(topic: TopicName, target: Target): Option[(SerdeName, String)] =
      SerdeResolution
        .configuredFor(profile.rules, topic, target)
        .flatMap(name => unavailable.get(name).map(name -> _))

    def suggest(topic: TopicName, target: Target, use: SerdeUse): F[List[SerdeSuggestion]] =
      for {
        // Only serdes that say they can do the thing being asked about. A Schema-Registry serde can read a
        // topic whose subject exists and cannot write to one whose subject does not, and offering the wrong
        // half of that is offering a choice that fails on submit.
        capable <- all.filterA(serde =>
          use match {
            case SerdeUse.Deserialize => serde.canDeserialize(topic, target)
            case SerdeUse.Serialize => serde.canSerialize(topic, target)
          }
        )
        preferredName <- preferred(capable, topic, target)
        rows <- capable.traverse(serde => row(serde, topic, target, preferredName.contains(serde.name)))
        disabled = extras.collect { case Entry(factory, Left(reason)) =>
          SerdeSuggestion(
            factory.name,
            factory.describe.description,
            preferred = false,
            schema = None,
            parameters = Nil,
            available = false,
            unavailableReason = Some(reason)
          )
        }
      } yield disabled ++ rows

    /** Exactly one row is marked preferred, or none is.
      *
      * "Or none" matters: two preferred rows is a picker with two highlighted options and no way for the user
      * to tell which one the product actually chose, and marking one arbitrarily when nothing prefers the
      * topic would be a recommendation KUI cannot justify. What resolution would pick is the honest answer,
      * so that is what is marked.
      */
    private def preferred(
        capable: List[Serde[F]],
        topic: TopicName,
        target: Target
    ): F[Option[SerdeName]] =
      Sync[F].pure(
        SerdeResolution
          .resolve(profile.rules, capable.map(_.name).toSet, topic, target, None)
          .toOption
          .filter(name => capable.exists(_.name == name))
      )

    private def row(
        serde: Serde[F],
        topic: TopicName,
        target: Target,
        isPreferred: Boolean
    ): F[SerdeSuggestion] =
      for {
        schema <- serde.schema(topic, target)
        parameters <- serde.parameters(topic, target)
      } yield SerdeSuggestion(
        serde.name,
        serde.describe.description,
        preferred = isPreferred,
        schema = schema,
        parameters = parameters,
        available = true,
        unavailableReason = None
      )
  }
}
