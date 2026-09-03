package kui.kafka

import java.util.concurrent.CompletionException

import org.apache.kafka.common.errors.*

import kui.testkit.KuiSuite

/** The set that decides whether a failure kills the connection or only the request.
  *
  * The negative half of this table is the important half. Kafbat recreates its admin client on any
  * admin error, which means a user asking about a topic they are not authorized for costs a full
  * reconnect including the SASL handshake. Every class in the second list below is one that must
  * *not* have that effect.
  */
final class AdminInvalidationSuite extends KuiSuite {

  private val reconnect: List[Throwable] = List(
    new TimeoutException("timed out"),
    new SaslAuthenticationException("bad credentials"),
    new SslAuthenticationException("bad certificate"),
    new BrokerNotAvailableException("no broker")
  )

  private val requestLevel: List[Throwable] = List(
    new TopicAuthorizationException("not allowed"),
    new UnknownTopicOrPartitionException("no such topic"),
    new InvalidRequestException("bad request"),
    new ClusterAuthorizationException("not allowed"),
    new UnsupportedVersionException("too old"),
    new InvalidConfigurationException("bad config"),
    new PolicyViolationException("refused by policy"),
    new SecurityDisabledException("no security")
  )

  test("theReconnectClassesAreExactlyFour") {
    assertEquals(AdminInvalidation.reconnectClasses.size, 4)
  }

  test("aConnectionLevelFailureIsReconnectClass") {
    reconnect.foreach(failure =>
      assert(AdminInvalidation.isReconnectClass(failure), failure.getClass.getName)
    )
  }

  test("aRequestLevelFailureIsNot") {
    requestLevel.foreach(failure =>
      assert(!AdminInvalidation.isReconnectClass(failure), failure.getClass.getName)
    )
  }

  test("theClassificationSurvivesTheWrapperTheyActuallyArriveIn") {
    // Every one of these reaches KUI inside a `CompletionException`, because that is how a
    // `KafkaFuture` reports a failure. A predicate that only worked on the bare exception would
    // answer `false` for all eight.
    reconnect.foreach(failure =>
      assert(
        AdminInvalidation.isReconnectClass(new CompletionException(failure)),
        failure.getClass.getName
      )
    )

    requestLevel.foreach(failure =>
      assert(
        !AdminInvalidation.isReconnectClass(new CompletionException(failure)),
        failure.getClass.getName
      )
    )
  }

  test("anUnrelatedThrowableIsNotReconnectClass") {
    assert(!AdminInvalidation.isReconnectClass(new IllegalStateException("something else")))
  }
}
