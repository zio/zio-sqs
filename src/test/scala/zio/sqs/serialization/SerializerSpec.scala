package zio.sqs.serialization

import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment

object SerializerSpec extends DefaultRunnableSpec {

  override def spec: ZSpec[TestEnvironment, Any] =
    suite("Serializer")(
      test("serializeString can serialize the string") {
        val input    = "some-string-value"
        val expected = input
        val actual   = Serializer.serializeString(input)

        assert(actual)(equalTo(expected))
      }
    )

  override def aspects: List[TestAspect[Nothing, TestEnvironment, Nothing, Any]] =
    List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
}
