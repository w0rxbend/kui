package kui.message.infrastructure

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Optional

import cats.effect.IO
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType

import kui.testkit.KuiIOSuite

final class KafkaBrowseConsumerSuite extends KuiIOSuite {

  test("header size counts UTF-8 bytes rather than UTF-16 code units") {
    val key = "ключ"
    val value = Array[Byte](1, 2, 3)
    val headers = new RecordHeaders().add(key, value)
    val record = new ConsumerRecord[Array[Byte], Array[Byte]](
      "orders.v1",
      0,
      42L,
      0L,
      TimestampType.CREATE_TIME,
      0,
      0,
      Array.emptyByteArray,
      Array.emptyByteArray,
      headers,
      Optional.empty[Integer]()
    )

    IO {
      val raw = KafkaBrowseConsumer.rawRecordOf(record)
      val expected = key.getBytes(UTF_8).length + value.length

      assertEquals(raw.headersSize, expected)
      assertEquals(expected, 11, clue = "the Cyrillic key occupies eight bytes and the value three")
    }
  }
}
