package zio.sqs.producer

import java.util.concurrent.TimeUnit

import zio.aws.sqs.model.MessageAttributeValue
import zio.{ Chunk, Duration, ExecutionStrategy }
import zio.test.Assertion._
import zio.test._
import zio.test.TestEnvironment

object ProducerEventSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment, Any] =
    suite("ProducerEvent")(
      test("it can be created") {
        val attr = MessageAttributeValue(Some("Jane"), dataType = "String")

        val e = ProducerEvent(
          data = "1",
          attributes = Map("Name" -> attr),
          groupId = Some("2"),
          deduplicationId = Some("3"),
          delay = Some(Duration(3, TimeUnit.SECONDS))
        )

        assert(e.data)(equalTo("1")) &&
        assert(e.attributes.size)(equalTo(1)) &&
        assert(e.groupId)(isSome(equalTo("2"))) &&
        assert(e.deduplicationId)(isSome(equalTo("3"))) &&
        assert(e.delay)(isSome(equalTo(Duration(3, TimeUnit.SECONDS))))
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

  override def aspects: Chunk[TestAspect[Nothing, TestEnvironment, Nothing, Any]] =
    Chunk(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
}
