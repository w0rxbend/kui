package kui.serde.confluent

import cats.effect.Async
import cats.syntax.all.*
import com.networknt.schema.Schema as JsonSchemaDocument
import io.circe.Json

import kui.cache.BoundedCache
import kui.kernel.TopicName
import kui.serde.*

/** A schema, parsed once and reused for every record that names it.
  *
  * Parsing is the expensive half of decoding — an Avro schema of a few hundred fields costs far more to parse
  * than one record costs to read — and a schema id is immutable, so a parsed schema is valid for as long as
  * the process runs. The three cases are the three registry schema languages; `Protobuf` carries only its
  * source text because KUI has no dynamic Protobuf decoder (see the module header in `build.mill`), and
  * keeping the case rather than dropping it is what lets the failure message name the format instead of
  * saying "unknown".
  */
enum ParsedSchema {
  case Avro(schema: org.apache.avro.Schema)
  case Json(schema: JsonSchemaDocument)
  case Protobuf(definition: String)
}

/** The Schema-Registry serde: `SerdeName.SchemaRegistry`, as `libs/serde` has always named it.
  *
  * One serde covers all three registry formats rather than three serdes covering one each, because a record
  * does not tell the reader which format it is in — it tells the reader a *schema id*, and the format is a
  * property of the schema the registry returns. A user who had to choose "Avro" or "Protobuf" in the picker
  * would be choosing something the record already decided, and choosing it wrongly half the time.
  *
  * ==What "can this serde read this topic?" means here==
  *
  * `canDeserialize` asks the registry whether the topic's subject exists. Strictly, decoding does not need
  * that — the schema id travels in the record — but the picker does: offering a Schema-Registry row for a
  * topic with no subject is offering a choice that will fail on every record, and `ClusterSerdes.suggest`
  * exists to keep such rows out (ADR-032). A topic whose subject was deleted after its records were written
  * therefore stops being *suggested* while its records remain readable by explicit choice, which is the right
  * way round.
  *
  * ==Subject naming==
  *
  * `TopicNameStrategy` only: `<topic>-key` and `<topic>-value`. Confluent's other two strategies key the
  * subject on the record's own type name, which is inside the payload that has not been decoded yet, so they
  * are unusable on the read path by construction. The `subject` parameter on the produce form is the escape
  * hatch for a topic that does not follow the convention.
  */
object SchemaRegistrySerde {

  /** The produce-form parameter that overrides the derived subject.
    *
    * It exists because `TopicNameStrategy` is a convention and not a rule: a team that registered its schema
    * under `orders.v2.OrderPlaced` has a perfectly working topic that this serde would otherwise refuse to
    * write to.
    */
  val SubjectParameter: String = "subject"

  private val Summary: String =
    "Decodes payloads written with a Confluent Schema Registry header - one magic byte, the schema id, " +
      "then the body - by fetching that exact schema from the registry and using it. Avro and JSON Schema " +
      "are supported; Protobuf payloads are recognised and reported rather than decoded. Schemas are " +
      "cached by id, which is safe because a registry never reissues an id."

  def apply[F[_]: Async](
      registry: SchemaRegistry[F],
      parsed: BoundedCache[F, java.lang.Integer, ParsedSchema]
  ): Serde[F] = new Impl[F](registry, parsed)

  final private class Impl[F[_]: Async](
      registry: SchemaRegistry[F],
      parsed: BoundedCache[F, java.lang.Integer, ParsedSchema]
  ) extends Serde[F]
      with SampleDetector {

    val name: SerdeName = SerdeName.SchemaRegistry

    val describe: SerdeDescription = SerdeDescription(name, Summary, coveredByIntegrationTest = false)

    def claims(sample: Array[Byte]): Boolean = WireFormat.looksLikeRegistryPayload(sample)

    private def subjectOf(topic: TopicName, target: Target): String =
      SchemaRegistry.subjectFor(topic.value, target.label)

    /** Whether the topic has a subject. A registry that cannot be reached answers "no" here rather than
      * failing: this question is asked while drawing a picker, and a picker that throws is a screen that does
      * not open. The registry being down is reported through the factory's disabled row instead, which is the
      * one place that can say why.
      */
    private def subjectExists(topic: TopicName, target: Target): F[Boolean] =
      registry.latestForSubject(subjectOf(topic, target)).map(_.fold(_ => false, _.isDefined))

    def canDeserialize(topic: TopicName, target: Target): F[Boolean] = subjectExists(topic, target)

    def canSerialize(topic: TopicName, target: Target): F[Boolean] = subjectExists(topic, target)

    /** The same question as `canDeserialize`, and that is not an oversight.
      *
      * `preferable` is the topic-level recommendation used when there is no sample to look at, and for this
      * serde the topic-level fact — "this topic has a registered schema" — genuinely is the recommendation. A
      * topic with a subject is a topic whose producer is registry-aware. When there *is* a sample,
      * auto-detection uses `claims` instead, which reads the magic byte and is stronger evidence.
      */
    def preferable(topic: TopicName, target: Target): F[Boolean] = subjectExists(topic, target)

    def schema(topic: TopicName, target: Target): F[Option[SchemaDescription]] =
      registry.latestForSubject(subjectOf(topic, target)).map {
        case Left(_) => None
        case Right(None) => None
        // A JSON Schema is already the thing the produce form validates against, so it is its own
        // projection. An Avro or Protobuf schema has no JSON Schema projection yet (SD-004), and `None` is
        // what tells the form to fall back to a free editor while the serializer still validates.
        case Right(Some(found)) =>
          val projection = Option.when(found.schemaType == SchemaType.Json)(found.definition)
          Some(found.describe(projection))
      }

    def parameters(topic: TopicName, target: Target): F[List[SerdeParameter]] =
      List(
        SerdeParameter(
          SubjectParameter,
          "The registry subject to write against. Defaults to the topic name followed by '-key' or " +
            "'-value', which is what a producer using the default TopicNameStrategy registers.",
          required = false,
          default = Some(subjectOf(topic, target))
        )
      ).pure[F]

    def deserializer(topic: TopicName, target: Target): F[Deserializer[F]] =
      (new Deserializer[F] {
        val serde: SerdeName = name

        def deserialize(
            headers: List[RawHeader],
            bytes: Array[Byte]
        ): F[Either[DeserializeFailure, DeserializeResult]] =
          WireFormat.read(bytes) match {
            case Left(why) => fail(why).pure[F]
            case Right(framed) => decodeFramed(framed, topic, target)
          }
      }: Deserializer[F]).pure[F]

    private def decodeFramed(
        framed: Framed,
        topic: TopicName,
        target: Target
    ): F[Either[DeserializeFailure, DeserializeResult]] =
      registry.schemaById(framed.schemaId).flatMap {
        case Left(error) => fail(error.message).pure[F]
        case Right(found) =>
          parseAndCache(found).map {
            case Left(why) => fail(why)
            case Right(ParsedSchema.Avro(schema)) =>
              AvroPayload
                .decode(schema, framed.body)
                .map(text => enrich(DeserializeResult.json(text), found, topic, target))
                .leftFlatMap(fail)
            case Right(ParsedSchema.Json(_)) =>
              JsonSchemaPayload
                .decode(framed.body)
                .map(text => enrich(DeserializeResult.json(text), found, topic, target))
                .leftFlatMap(fail)
            case Right(ParsedSchema.Protobuf(_)) => fail(protobufUnsupported(found.id))
          }
      }

    /** What the browser needs in order to link a record to its schema (ADR-035).
      *
      * `{type, id, subject}` and nothing more. The schema *text* is deliberately not here: it would be
      * repeated on every record of a page, and the schema panel already fetches it once through [[schema]].
      */
    private def enrich(
        result: DeserializeResult,
        found: RegistrySchema,
        topic: TopicName,
        target: Target
    ): DeserializeResult =
      result.copy(properties =
        Map(
          "type" -> Json.fromString(found.schemaType.label),
          "id" -> Json.fromInt(found.id),
          "subject" -> Json.fromString(subjectOf(topic, target))
        )
      )

    def serializer(topic: TopicName, target: Target, params: Map[String, String]): F[Serializer[F]] = {
      val subject =
        params.get(SubjectParameter).map(_.trim).filter(_.nonEmpty).getOrElse(subjectOf(topic, target))
      (new Serializer[F] {
        val serde: SerdeName = name

        def serialize(input: String, headers: List[RawHeader]): F[Either[SerializeFailure, Array[Byte]]] =
          registry.latestForSubject(subject).flatMap {
            case Left(error) => encodeFail(error.message).pure[F]
            case Right(None) =>
              encodeFail(
                s"the registry has no subject '$subject', so KUI does not know what schema to write this " +
                  s"record against. Register one, or set the '$SubjectParameter' parameter to the subject " +
                  "this topic actually uses"
              ).pure[F]
            case Right(Some(found)) => encodeWith(found, input)
          }
      }: Serializer[F]).pure[F]
    }

    private def encodeWith(
        found: RegistrySchema,
        input: String
    ): F[Either[SerializeFailure, Array[Byte]]] =
      parseAndCache(found).map {
        case Left(why) => encodeFail(why)
        case Right(ParsedSchema.Avro(schema)) =>
          AvroPayload.encode(schema, input).map(WireFormat.frame(found.id, _)).leftFlatMap(encodeFail)
        case Right(ParsedSchema.Json(schema)) =>
          JsonSchemaPayload.encode(schema, input).map(WireFormat.frame(found.id, _)).leftFlatMap(encodeFail)
        case Right(ParsedSchema.Protobuf(_)) => encodeFail(protobufUnsupported(found.id))
      }

    /** Parses a schema, or returns the one already parsed.
      *
      * Only successes are cached, and a `Protobuf` schema counts as a success: it parses trivially (its
      * parsed form is its text) and the refusal happens where the format is used, so the message can say
      * whether the user was reading or writing.
      */
    private def parseAndCache(found: RegistrySchema): F[Either[String, ParsedSchema]] = {
      val key = java.lang.Integer.valueOf(found.id)
      parsed.get(key).flatMap {
        case Some(hit) => hit.asRight[String].pure[F]
        case None =>
          val attempt = found.schemaType match {
            case SchemaType.Avro => AvroPayload.parse(found.definition).map(ParsedSchema.Avro(_))
            case SchemaType.Json => JsonSchemaPayload.parse(found.definition).map(ParsedSchema.Json(_))
            case SchemaType.Protobuf => Right(ParsedSchema.Protobuf(found.definition))
          }
          attempt match {
            case Right(value) => parsed.put(key, value).as(attempt)
            case Left(_) => attempt.pure[F]
          }
      }
    }

    /** The one thing this module does not do, said plainly and with the reason.
      *
      * A record that renders as "unsupported" with a sentence is worth far more than one that renders as
      * Base64 and leaves an operator to work out why. Decoding it needs a `.proto` parser and a descriptor
      * builder, and the only maintained one is Confluent's, under the Confluent Community License.
      */
    private def protobufUnsupported(id: Int): String =
      s"schema $id is a Protobuf schema, and KUI cannot decode Protobuf payloads yet: doing so needs a " +
        "dynamic .proto parser, and the only maintained one is licensed under the Confluent Community " +
        "License rather than Apache-2.0. Avro and JSON Schema payloads decode normally"

    private def fail(cause: String): Either[DeserializeFailure, DeserializeResult] =
      Left(DeserializeFailure(name, cause))

    private def encodeFail(cause: String): Either[SerializeFailure, Array[Byte]] =
      Left(SerializeFailure(name, cause))
  }
}
