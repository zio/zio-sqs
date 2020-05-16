package zio.sqs.producer

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import zio.ExecutionStrategy
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment

object ProducerEventSpec extends DefaultRunnableSpec {

  override def spec: ZSpec[TestEnvironment, Any] =
    suite("ProducerEvent")(
      test("it can be created") {
        val attr = MessageAttributeValue
          .builder()
          .dataType("String")
          .stringValue("Jane")
          .build()

        val e = ProducerEvent(
          data = "1",
          attributes = Map("Name" -> attr),
          groupId = Some("2"),
          deduplicationId = Some("3")
        )

        assert(e.data)(equalTo("1")) &&
        assert(e.attributes.size)(equalTo(1)) &&
        assert(e.groupId)(isSome(equalTo("2"))) &&
        assert(e.deduplicationId)(isSome(equalTo("3")))
      },
      test("it can be created from a string") {
        val e = ProducerEvent(
          body = "1"
        )

        assert(e.data)(equalTo("1")) &&
        assert(e.attributes.size)(equalTo(0)) &&
        assert(e.groupId)(isNone) &&
        assert(e.deduplicationId)(isNone)
      }
    )

  override def aspects: List[TestAspect[Nothing, TestEnvironment, Nothing, Any]] =
    List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
}
