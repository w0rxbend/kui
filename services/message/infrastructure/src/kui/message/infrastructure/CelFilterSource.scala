package kui.message.infrastructure

import cats.Applicative
import cats.syntax.all.*

import kui.filter.{FilterId, FilterableRecord, MessageFilterPort, MessagePredicate}
import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, KuiError}
import kui.message.domain.ports.{CompiledFilter, FilterSample, FilterSource, FilterVerdict}
import kui.message.domain.{DecodedRecord, FilterRef}

/** The message service's smart filters, over `libs/filter`'s CEL engine.
  *
  * ==Why an adapter and not the engine itself==
  *
  * Because the domain's port speaks about `DecodedRecord`, which is what a browse has in its hand, and the
  * engine speaks about `FilterableRecord`, which is what a predicate can be written against. The two are
  * nearly the same shape and are deliberately different types: the engine may not depend on this service, and
  * the domain may not depend on CEL. This file is the whole of the join, and it is the only place in the
  * service where `kui.filter` is named.
  *
  * ==One engine per cluster==
  *
  * The compiled-program cache is per cluster because a filter is written against one cluster's data and
  * because an operator reading cache statistics is asking about one cluster at a time. A request naming a
  * cluster with no engine — one that is configured but was not running when this service started — is refused
  * with `KUI-UNSUPPORTED` rather than quietly matching every record, which is the failure mode that teaches a
  * user their filter works when it is doing nothing at all.
  */
final class CelFilterSource[F[_]: Applicative](engines: Map[ClusterId, MessageFilterPort[F]])
    extends FilterSource[F] {

  def compile(cluster: ClusterId, filter: FilterRef): F[Either[KuiError, CompiledFilter[F]]] =
    withEngine(cluster) { engine =>
      // The id is checked here rather than inside the engine so that an id no KUI could have minted is a
      // validation failure naming the parameter, instead of a cache miss that then tries to compile a
      // source that was never sent.
      FilterId.fromString(filter.id) match {
        case None =>
          malformed.asLeft[CompiledFilter[F]].pure[F]
        case Some(id) =>
          engine.predicate(id, filter.source).map(_.map(asCompiled))
      }
    }

  def register(cluster: ClusterId, source: String): F[Either[KuiError, String]] =
    withEngine(cluster)(_.register(source).map(_.map(_.value)))

  def check(cluster: ClusterId, source: String, record: FilterSample): F[Either[KuiError, FilterVerdict]] =
    withEngine(cluster) { engine =>
      engine.test(source, sample(record)).map {
        case Right(matched) => Right(if matched then FilterVerdict.Matched else FilterVerdict.DidNotMatch)
        // The engine reports a *runtime* failure on the test endpoint as a `Left`, because there it is the
        // answer. The port distinguishes the two: a compile failure means the expression is wrong, and a
        // runtime failure means the expression is legal and this record does not suit it. Both reach the
        // editor; only the second leaves the filter usable.
        case Left(error) if error.code == kui.kernel.error.ErrorCode.Validation =>
          Right(FilterVerdict.Failed(error.message))
        case Left(error) => Left(error)
      }
    }

  private def withEngine[A](
      cluster: ClusterId
  )(use: MessageFilterPort[F] => F[Either[KuiError, A]]): F[Either[KuiError, A]] =
    engines.get(cluster) match {
      case Some(engine) => use(engine)
      case None =>
        ApplicationError
          .Unsupported(s"cluster '${cluster.value}' has no filter engine, so a smart filter cannot be run")
          .asLeft[A]
          .pure[F]
    }

  private val malformed: KuiError =
    ApplicationError.Invalid(
      "the filter id is not one KUI could have minted",
      List(kui.kernel.error.FieldError.of("filterId", "16 lowercase hexadecimal characters"))
    )

  /** The engine's predicate, as the domain's compiled filter.
    *
    * The three-way verdict is the reason this mapping is worth writing out. A record the expression threw on
    * is neither a match nor a non-match: it becomes `Failed`, which the browse counts into `filterErrors` and
    * shows, so a filter that is broken on every record cannot pass for a filter that matches nothing.
    */
  private def asCompiled(predicate: MessagePredicate[F]): CompiledFilter[F] = new CompiledFilter[F] {
    def test(record: DecodedRecord): F[FilterVerdict] =
      predicate.test(filterable(record)).map {
        case Right(true) => FilterVerdict.Matched
        case Right(false) => FilterVerdict.DidNotMatch
        case Left(failure) => FilterVerdict.Failed(failure.describe)
      }
  }

  /** A decoded record as a filter sees it: text on both sides, headers flattened, and nothing that could
    * throw. The decoded text is used rather than the raw bytes for the same reason the substring filter is
    * applied after decoding — a person writing `record.value.status` means the document they can see.
    */
  private def sample(record: FilterSample): FilterableRecord =
    FilterableRecord(
      partition = record.partition,
      offset = record.offset,
      timestampMs = record.timestampMs,
      keyAsText = record.keyAsText,
      valueAsText = record.valueAsText,
      headers = record.headers
    )

  private def filterable(record: DecodedRecord): FilterableRecord =
    FilterableRecord(
      partition = record.partition.value,
      offset = record.offset.value,
      timestampMs = record.timestamp.toEpochMilli,
      keyAsText = record.key.text,
      valueAsText = record.value.text,
      headers = record.headers.map(header => header.key -> header.value).toMap
    )
}
