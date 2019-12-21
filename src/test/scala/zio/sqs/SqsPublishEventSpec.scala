package zio.sqs

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import zio.test.Assertion._
import zio.test._

object SqsPublishEventSpec
    extends DefaultRunnableSpec(
      suite("SqsPublishEvent")(
        test("SqsPublishEvent can be created") {
          val attr = MessageAttributeValue
            .builder()
            .dataType("String")
            .stringValue("Jane")
            .build()

          val e = SqsPublishEvent(
            body = "1",
            attributes = Map("Name" -> attr),
            groupId = Some("2"),
            deduplicationId = Some("3")
          )

          assert(e.body, equalTo("1"))
          assert(e.attributes.size, equalTo(1))
          assert(e.groupId, equalTo(Some("2")))
          assert(e.deduplicationId, equalTo(Some("3")))
        },
        test("SqsPublishEvent can be created from a string") {
          val e = SqsPublishEvent(
            body = "1"
          )

          assert(e.body, equalTo("1"))
          assert(e.attributes.size, equalTo(0))
          assert(e.groupId, equalTo(None))
          assert(e.deduplicationId, equalTo(None))
        }
      ),
      List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
    )
