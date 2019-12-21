package zio.sqs

import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry
import zio.test.Assertion._
import zio.test._

object SqsPublishEventResultSpec
    extends DefaultRunnableSpec(
      suite("SqsPublishEventResult")(
        test("SqsPublishEventResult can be created") {
          val event = SqsPublishEvent("e1")
          val res = SendMessageBatchResultEntry
            .builder()
            .id("1")
            .md5OfMessageAttributes("some-value1")
            .md5OfMessageBody("some-value2")
            .md5OfMessageSystemAttributes("some-value3")
            .messageId("msgId")
            .sequenceNumber("seqNum")
            .build()

          val r = SqsPublishEventResult(res, event)

          assert(r.id, equalTo("1"))
          assert(r.md5OfMessageAttributes, equalTo("some-value1"))
          assert(r.md5OfMessageBody, equalTo(Some("some-value2")))
          assert(r.md5OfMessageSystemAttributes, equalTo(Some("some-value3")))
          assert(r.messageId, equalTo("msgId"))
          assert(r.sequenceNumber, equalTo(Some("seqNum")))
          assert(r.event, equalTo(event))
        }
      ),
      List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
    )
