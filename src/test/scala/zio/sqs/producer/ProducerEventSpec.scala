package zio.sqs.producer

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import zio.test.Assertion.equalTo
import zio.test._

object ProducerEventSpec
    extends DefaultRunnableSpec(
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
          assert(e.groupId)(equalTo(Some("2"))) &&
          assert(e.deduplicationId)(equalTo(Some("3")))
        },
        test("it can be created from a string") {
          val e = ProducerEvent(
            body = "1"
          )

          assert(e.data)(equalTo("1")) &&
          assert(e.attributes.size)(equalTo(0)) &&
          assert(e.groupId)(equalTo(None)) &&
          assert(e.deduplicationId)(equalTo(None))
        }
      ),
      List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
    )
