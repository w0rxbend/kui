package kui.consumer.application

import kui.kernel.ServiceId

/** Who this service is, in the one place every log line, metric and internal call reads it from. */
object ConsumerService {
  val Id: ServiceId = ServiceId.unsafe("kui-consumer-service")
}
