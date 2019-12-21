package zio.sqs

import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry
import zio.test.Assertion._
import zio.test._

object SqsPublishEventErrorSpec
    extends DefaultRunnableSpec(
      suite("SqsPublishEventError")(
        test("SqsPublishEventError can be created from BatchResultErrorEntry") {
          val event = SqsPublishEvent("e1")
          val errEntry =
            BatchResultErrorEntry.builder().id("id1").code("code2").message("message3").senderFault(true).build()

          val e = SqsPublishEventError(errEntry, event)

          assert(e.id, equalTo("id1"))
          assert(e.code, equalTo("code2"))
          assert(e.message, equalTo(Some("message3")))
          assert(e.senderFault, equalTo(true))
          assert(e.event, equalTo(event))
        },
        test("SqsPublishEventError can be created from BatchResultErrorEntry without message") {
          val event    = SqsPublishEvent("e2")
          val errEntry = BatchResultErrorEntry.builder().id("id1").code("code2").senderFault(true).build()

          val e = SqsPublishEventError(errEntry, event)

          assert(e.id, equalTo("id1"))
          assert(e.code, equalTo("code2"))
          assert(e.message, equalTo(None))
          assert(e.senderFault, equalTo(true))
          assert(e.event, equalTo(event))
        }
      ),
      List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
    )
