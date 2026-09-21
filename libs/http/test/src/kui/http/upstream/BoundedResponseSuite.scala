package kui.http.upstream

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

import munit.FunSuite
import sttp.client4.*
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.{Header, StatusCode}

final class BoundedResponseSuite extends FunSuite {

  test("a response exactly at the byte limit succeeds") {
    val body = "three bytes: €".getBytes(StandardCharsets.UTF_8)

    assertEquals(
      BoundedResponse.readUtf8(counting(body), body.length.toLong, Some(body.length.toLong)),
      "three bytes: €"
    )
  }

  test("one byte over fails after reading only the single excess byte") {
    val input = counting("1234567890".getBytes(StandardCharsets.UTF_8))

    val failure = intercept[BoundedResponse.LimitExceeded] {
      BoundedResponse.readUtf8(input, 5L, None)
    }

    assertEquals(failure.limitBytes, 5L)
    assertEquals(input.bytesRead, 6L)
  }

  test("an oversized Content-Length rejects the response without reading its body") {
    val input = counting("body is immaterial".getBytes(StandardCharsets.UTF_8))

    val failure = intercept[BoundedResponse.LimitExceeded] {
      BoundedResponse.readUtf8(input, 5L, Some(6L))
    }

    assertEquals(failure.limitBytes, 5L)
    assertEquals(input.bytesRead, 0L)
  }

  test("the response handler uses Content-Length as an early rejection hint") {
    val backend = SyncBackendStub.whenAnyRequest.thenRespond(
      ResponseStub
        .adjust("short", StatusCode.Ok)
        .copy(headers = Seq(Header.contentLength(6L)))
    )

    val failure = intercept[BoundedResponse.LimitExceeded] {
      basicRequest
        .get(uri"http://upstream.invalid")
        .response(BoundedResponse.asString(5L))
        .send(backend)
    }

    assertEquals(failure.limitBytes, 5L)
  }

  test("Content-Length is only an early hint and cannot weaken the incremental byte cap") {
    val input = counting("123456".getBytes(StandardCharsets.UTF_8))

    val failure = intercept[BoundedResponse.LimitExceeded] {
      BoundedResponse.readUtf8(input, 5L, Some(4L))
    }

    assertEquals(failure.limitBytes, 5L)
    assertEquals(input.bytesRead, 6L)
  }

  private def counting(bytes: Array[Byte]): CountingInputStream =
    new CountingInputStream(bytes)

  final private class CountingInputStream(bytes: Array[Byte]) extends InputStream {
    private val delegate = new ByteArrayInputStream(bytes)
    private val count = AtomicLong(0L)

    def bytesRead: Long = count.get()

    override def read(): Int = {
      val value = delegate.read()
      if value != -1 then {
        val _ = count.incrementAndGet()
      }
      value
    }

    override def read(target: Array[Byte], offset: Int, length: Int): Int = {
      val read = delegate.read(target, offset, length)
      if read > 0 then {
        val _ = count.addAndGet(read.toLong)
      }
      read
    }
  }
}
