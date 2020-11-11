package zio.sqs.producer

import io.github.vigoo.zioaws.sqs.model.BatchResultErrorEntry
import zio.ExecutionStrategy
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment

object ProducerErrorSpec extends DefaultRunnableSpec {

  override def spec: ZSpec[TestEnvironment, Any] =
    suite("ProducerError")(
      test("it can be created from BatchResultErrorEntry") {
        val event    = ProducerEvent("e1")
        val errEntry = BatchResultErrorEntry.wrap(BatchResultErrorEntry("id1", senderFault = true, "code2", Some("message3")).buildAwsValue())

        val e = ProducerError(errEntry, event)

        assert(e.code)(equalTo("code2")) &&
        assert(e.message)(isSome(equalTo("message3"))) &&
        assert(e.senderFault)(isTrue) &&
        assert(e.event)(equalTo(event))
      },
      test("it can be created from BatchResultErrorEntry without message") {
        val event    = ProducerEvent("e2")
        val errEntry = BatchResultErrorEntry.wrap(BatchResultErrorEntry("id1", senderFault = true, "code2").buildAwsValue())

        val e = ProducerError(errEntry, event)

        assert(e.code)(equalTo("code2")) &&
        assert(e.message)(isNone) &&
        assert(e.senderFault)(isTrue) &&
        assert(e.event)(equalTo(event))
      }
    )

  override def aspects: List[TestAspect[Nothing, TestEnvironment, Nothing, Any]] =
    List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
}
