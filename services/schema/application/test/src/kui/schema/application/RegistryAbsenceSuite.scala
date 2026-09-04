package kui.schema.application

import cats.effect.IO
import cats.effect.kernel.Ref

import kui.kernel.Subject
import kui.kernel.error.ErrorCode
import kui.schema.domain.*
import kui.security.Principal
import kui.security.audit.{MutationKind, MutationOutcome}
import kui.testkit.KuiIOSuite

/** The three-way answer every read in this service gives, and the fact the whole service is shaped around.
  *
  * "There is no such cluster", "this cluster has no registry" and "the registry did not answer" are three
  * different things. Collapsing any pair of them produces a screen that lies:
  *
  *   - unknown as not-configured hides a broken link behind a feature that looks switched off;
  *   - not-configured as unreachable puts a permanently red panel on a deployment where nothing is wrong;
  *   - unreachable as an empty list claims the registry holds nothing, which sends an operator looking for a
  *     schema somebody deleted instead of at a registry that is down.
  */
final class RegistryAbsenceSuite extends KuiIOSuite {

  private val orders = Subject.unsafe("orders-value")

  test("a cluster KUI has never heard of is a cluster-not-found, not a missing registry") {
    for {
      registry <- SchemaRig.registry()
      useCase = SubjectListUseCase.make[IO](SchemaRig.registries(registry))
      result <- useCase.list(SchemaRig.Unknown, SubjectQuery.Default)
    } yield assertEquals(result.left.map(_.code), Left(ErrorCode.ClusterNotFound))
  }

  test("a configured cluster with no registry is unsupported, and the message names the key to set") {
    for {
      registry <- SchemaRig.registry()
      useCase = SubjectListUseCase.make[IO](SchemaRig.registries(registry))
      result <- useCase.list(SchemaRig.WithoutRegistry, SubjectQuery.Default)
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.Unsupported))
      assert(
        clue(result.swap.toOption.map(_.message)).exists(_.contains("kui.clusters.<n>.schemaRegistry.url")),
        "the refusal has to say what to configure, or it reads as a KUI limitation"
      )
    }
  }

  test("a registry that does not answer fails; it never becomes an empty subject list") {
    for {
      registry <- SchemaRig.registry(failure = Some(SchemaRig.unreachable))
      useCase = SubjectListUseCase.make[IO](SchemaRig.registries(registry))
      result <- useCase.list(SchemaRig.WithRegistry, SubjectQuery.Default)
    } yield assertEquals(result, Left(SchemaRig.unreachable))
  }

  test("a subject the registry does not hold is a schema-not-found") {
    for {
      registry <- SchemaRig.registry(subjects = Map("orders-value" -> List(1, 2)))
      useCase = SubjectVersionsUseCase.make[IO](SchemaRig.registries(registry))
      missing <- useCase.versions(SchemaRig.WithRegistry, Subject.unsafe("nope-value"))
      found <- useCase.versions(SchemaRig.WithRegistry, orders)
    } yield {
      assertEquals(missing.left.map(_.code), Left(ErrorCode.SchemaNotFound))
      assertEquals(found.map(_.map(_.value)), Right(List(1, 2)))
    }
  }
}

/** Where a subject's compatibility level comes from, which is a fact a screen must not flatten. */
final class CompatibilityReadSuite extends KuiIOSuite {

  private val orders = Subject.unsafe("orders-value")

  test("a subject with its own level reports it as its own") {
    for {
      logger <- SchemaRig.logger
      registry <- SchemaRig.registry(subjectLevels = Map("orders-value" -> CompatibilityLevel.Full))
      useCase = CompatibilityReadUseCase.make[IO](SchemaRig.registries(registry), logger)
      result <- useCase.forSubject(SchemaRig.WithRegistry, orders)
    } yield assertEquals(result, Right(SubjectCompatibility.own(CompatibilityLevel.Full)))
  }

  test("a subject with no level of its own reports the global one, marked as inherited") {
    for {
      logger <- SchemaRig.logger
      registry <- SchemaRig.registry(globalLevel = CompatibilityLevel.ForwardTransitive)
      useCase = CompatibilityReadUseCase.make[IO](SchemaRig.registries(registry), logger)
      result <- useCase.forSubject(SchemaRig.WithRegistry, orders)
    } yield {
      // Both halves matter. Without the level the screen has nothing to show; without the flag,
      // "confirming" what is displayed writes an override the operator never intended.
      assertEquals(result.map(_.level), Right(CompatibilityLevel.ForwardTransitive))
      assertEquals(result.map(_.inheritedFromGlobal), Right(true))
    }
  }

  test("an inherited level that cannot be read fails rather than reporting the registry's default") {
    for {
      logger <- SchemaRig.logger
      registry <- SchemaRig.registry(failure = Some(SchemaRig.unreachable))
      useCase = CompatibilityReadUseCase.make[IO](SchemaRig.registries(registry), logger)
      result <- useCase.forSubject(SchemaRig.WithRegistry, orders)
    } yield assertEquals(result, Left(SchemaRig.unreachable))
  }
}

/** The one mutation: refused on a read-only cluster, audited either way. */
final class SetCompatibilitySuite extends KuiIOSuite {

  private val orders = Subject.unsafe("orders-value")
  private val who: Principal = Principal.Anonymous

  private def rig(
      registry: FakeRegistry
  ): IO[(SetCompatibilityUseCase[IO], RecordingAudit)] =
    for {
      logger <- SchemaRig.logger
      records <- Ref.of[IO, List[kui.security.audit.MutationRecord]](Nil)
      audit = new RecordingAudit(records)
    } yield (SetCompatibilityUseCase.make[IO](SchemaRig.registries(registry), audit, logger), audit)

  test("a read-only cluster is refused, and the registry is never contacted") {
    for {
      registry <- SchemaRig.registry()
      pair <- rig(registry)
      (useCase, audit) = pair
      result <- useCase.setGlobal(who, SchemaRig.ReadOnly, CompatibilityLevel.None)
      writes <- registry.writes.get
      records <- audit.records.get
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.ReadOnly))
      // Not contacting the registry is the assertion, not an optimisation: a refused write that still
      // reached the upstream is a call an operator's proxy logs and then has to explain.
      assertEquals(writes, Nil)
      assertEquals(records.map(_.outcome), List(MutationOutcome.Refused))
    }
  }

  test("a successful write is audited with the level that was in force before it") {
    for {
      registry <- SchemaRig.registry(globalLevel = CompatibilityLevel.Full)
      pair <- rig(registry)
      (useCase, audit) = pair
      result <- useCase.setGlobal(who, SchemaRig.WithRegistry, CompatibilityLevel.None)
      writes <- registry.writes.get
      records <- audit.records.get
    } yield {
      assertEquals(result, Right(CompatibilityLevel.None))
      assertEquals(writes, List("global" -> CompatibilityLevel.None))
      assertEquals(records.map(_.kind), List(MutationKind.SetGlobalCompatibility))
      assertEquals(records.map(_.outcome), List(MutationOutcome.Succeeded))
      // "Somebody set NONE" is not the record an incident review needs; "somebody replaced FULL with
      // NONE" is.
      assertEquals(records.flatMap(_.before), List("FULL"))
      assertEquals(records.flatMap(_.after), List("NONE"))
    }
  }

  test("a subject that was inheriting says so in the audit record, and stops inheriting afterwards") {
    for {
      registry <- SchemaRig.registry(globalLevel = CompatibilityLevel.Backward)
      pair <- rig(registry)
      (useCase, audit) = pair
      result <- useCase.setForSubject(who, SchemaRig.WithRegistry, orders, CompatibilityLevel.Full)
      records <- audit.records.get
    } yield {
      assertEquals(result, Right(SubjectCompatibility.own(CompatibilityLevel.Full)))
      assertEquals(records.flatMap(_.before), List("inherited from the global level"))
    }
  }

  test("a cluster with no registry is refused and the attempt is still recorded") {
    for {
      registry <- SchemaRig.registry()
      pair <- rig(registry)
      (useCase, audit) = pair
      result <- useCase.setGlobal(who, SchemaRig.WithoutRegistry, CompatibilityLevel.Full)
      records <- audit.records.get
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.Unsupported))
      assertEquals(records.map(_.outcome), List(MutationOutcome.Failed))
    }
  }
}

/** The check: a read that carries a body, answered on a read-only cluster. */
final class CompatibilityCheckSuite extends KuiIOSuite {

  private val orders = Subject.unsafe("orders-value")

  private def proposal(text: String): ProposedSchema = ProposedSchema(SchemaFormat.Avro, text, Nil)

  test("a read-only cluster still gets an answer") {
    for {
      registry <- SchemaRig.registry(subjects = Map("orders-value" -> List(1)))
      useCase = CompatibilityCheckUseCase.make[IO](SchemaRig.registries(registry))
      result <- useCase.check(SchemaRig.ReadOnly, orders, VersionSelector.Latest, proposal("compatible"))
    } yield assertEquals(result.map(_.compatible), Right(true))
  }

  test("an empty schema is refused before the registry is asked") {
    for {
      registry <- SchemaRig.registry(subjects = Map("orders-value" -> List(1)))
      useCase = CompatibilityCheckUseCase.make[IO](SchemaRig.registries(registry))
      result <- useCase.check(SchemaRig.WithRegistry, orders, VersionSelector.Latest, proposal("   "))
    } yield assertEquals(result.left.map(_.code), Left(ErrorCode.Validation))
  }

  test("a schema past the size bound is refused rather than forwarded") {
    val huge = "x" * (CompatibilityCheckUseCase.MaxDefinitionBytes + 1)

    for {
      registry <- SchemaRig.registry(subjects = Map("orders-value" -> List(1)))
      useCase = CompatibilityCheckUseCase.make[IO](SchemaRig.registries(registry))
      result <- useCase.check(SchemaRig.WithRegistry, orders, VersionSelector.Latest, proposal(huge))
    } yield assertEquals(result.left.map(_.code), Left(ErrorCode.Validation))
  }

  test("an unknown subject is a schema-not-found rather than a fabricated 'compatible'") {
    for {
      registry <- SchemaRig.registry()
      useCase = CompatibilityCheckUseCase.make[IO](SchemaRig.registries(registry))
      result <- useCase.check(SchemaRig.WithRegistry, orders, VersionSelector.Latest, proposal("anything"))
    } yield assertEquals(result.left.map(_.code), Left(ErrorCode.SchemaNotFound))
  }
}
