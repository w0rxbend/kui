package kui.consumer.domain

import java.time.Instant

import kui.kernel.{
  GroupId,
  Offset,
  TopicPartition
}

/** Every rule of KIP-122, and every rule the reference products learned, in one pure function.
  *
  * Pure on purpose. The most consequential arithmetic in the milestone — where a running application will
  * start reading — is decided by a function whose inputs are a record and whose output is a value, so every
  * one of its rules is reachable by a constructor call rather than by arranging a broker into a state.
  *
  * The planner refuses rather than partially plans. A leaderless partition fails the whole reset, which is
  * what `failOnUnknownLeader = true` means in the reference: a partial reset leaves a group where nobody
  * asked for it, and the operator finds out when one partition replays and the others do not.
  */
object ResetPlanner {

  def plan(
      group: GroupId,
      scope: ResetScope,
      spec: ResetSpec,
      window: OffsetWindow,
      now: Instant
  ): Either[ResetRefusal, ResetPlan] = {
    val partitions = scope.partitions

    val offline = partitions.intersect(window.leaderless)
    val unknown =
      partitions.filterNot(partition => window.begin.contains(partition) && window.end.contains(partition))

    if partitions.isEmpty then Left(ResetRefusal.NoPartitionsInScope)
    else if offline.nonEmpty then Left(ResetRefusal.Leaderless(offline))
    else if unknown.nonEmpty then Left(ResetRefusal.UnknownPartition(unknown -- offline))
    else
      // Sorted, so that the same inputs produce a byte-identical plan: the plan is signed into a
      // token (ADR-045), and a set's iteration order would make the same reset produce two tokens.
      partitions.toList.sorted
        .foldLeft[Either[ResetRefusal, (List[PlannedPartition], List[ResetWarning])]](Right((Nil, Nil))) {
          case (Left(refusal), _) => Left(refusal)
          case (Right((planned, warnings)), partition) =>
            planOne(partition, spec, window).map { (one, said) =>
              (planned :+ one, warnings ++ said)
            }
        }
        .map { (planned, warnings) =>
          val settled = planned
            .filter(one => one.current.contains(one.proposed))
            .map(one => ResetWarning.NoChange(one.partition))

          ResetPlan(
            group = group,
            scope = scope,
            spec = spec,
            partitions = planned,
            warnings = warnings ++ settled,
            computedAt = now
          )
        }
  }

  /** One partition, one mode. Every branch ends in a clamp, so nothing below can propose an offset the
    * partition does not hold.
    */
  private def planOne(
      partition: TopicPartition,
      spec: ResetSpec,
      window: OffsetWindow
  ): Either[ResetRefusal, (PlannedPartition, List[ResetWarning])] = {
    val begin = window.begin.getOrElse(partition, Offset.unsafe(0L))
    val end = window.end.getOrElse(partition, begin)
    val current = window.committed.get(partition)

    val proposal: Either[ResetRefusal, (Long, List[ResetWarning])] = spec match {
      case ResetSpec.ToEarliest => Right((begin.value, Nil))
      case ResetSpec.ToLatest => Right((end.value, Nil))

      case ResetSpec.ToTimestamp(_) | ResetSpec.ByDuration(_) =>
        window.atTimestamp.get(partition).flatten match {
          case Some(at) => Right((at.value, Nil))
          // KIP-122: no record at or after that time means the end of the partition. That is
          // "skip everything", so it is warned about rather than quietly applied.
          case None => Right((end.value, List(ResetWarning.TimestampBeyondEnd(partition, end))))
        }

      case ResetSpec.ToOffsets(offsets) =>
        offsets.get(partition) match {
          case Some(requested) => Right((requested.value, Nil))
          // A mode that names offsets per partition and names none for this one leaves it where it
          // is, rather than inventing a target for a partition the operator did not mention.
          case None => Right((current.getOrElse(begin).value, Nil))
        }

      case ResetSpec.ShiftBy(records) =>
        val from = current.getOrElse(begin)
        val warned =
          if current.isEmpty then List(ResetWarning.ShiftedFromBeginning(partition)) else Nil
        val shifted = from.value + records

        if shifted < 0L && records < 0L then
          // Shifting back past the start of the log is clamped to the start, like every other
          // out-of-range offset; only arithmetic that cannot be clamped is a refusal.
          Right((begin.value, warned))
        else Right((shifted, warned))
    }

    proposal.flatMap { (raw, warnings) =>
      if raw < 0L then Left(ResetRefusal.NegativeResult(partition))
      else {
        val clamped = math.max(begin.value, math.min(end.value, raw))
        val clampWarning =
          Option.when(clamped != raw)(
            ResetWarning.Clamped(partition, Offset.unsafe(raw), Offset.unsafe(clamped))
          )

        Right(
          (
            PlannedPartition(
              partition = partition,
              current = current,
              proposed = Offset.unsafe(clamped),
              delta = current.map(at => clamped - at.value)
            ),
            warnings ++ clampWarning.toList
          )
        )
      }
    }
  }

  /** The instant a `ByDuration` spec resolves to.
    *
    * Exposed because the caller has to resolve it *before* it can ask the cluster for offsets at that time,
    * and because `ByDuration(2.hours)` and `ToTimestamp(now - 2.hours)` must plan identically — one rule, one
    * place, rather than a subtraction in the use case and another one here.
    */
  def timestampOf(spec: ResetSpec, now: Instant): Option[Instant] = spec match {
    case ResetSpec.ToTimestamp(at) => Some(at)
    case ResetSpec.ByDuration(back) => Some(now.minusMillis(back.toMillis))
    case _ => None
  }
}
