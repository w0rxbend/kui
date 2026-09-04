package kui.schema.application

import kui.kernel.ServiceId

/** Who this service is, in the one place every log line, metric and internal call reads it from. */
object SchemaService {
  val Id: ServiceId = ServiceId.unsafe("kui-schema-service")
}
