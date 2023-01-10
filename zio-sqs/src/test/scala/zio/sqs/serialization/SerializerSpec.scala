package zio.sqs.serialization

import zio.{ Chunk, ExecutionStrategy }
import zio.test.Assertion._
import zio.test._
import zio.test.TestEnvironment

object SerializerSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment, Any] =
    suite("Serializer")(
      test("serializeString can serialize the string") {
        val input    = "some-string-value"
        val expected = input
        val actual   = Serializer.serializeString(input)

        assert(actual)(equalTo(expected))
      }
    )

  override def aspects: Chunk[TestAspect[Nothing, TestEnvironment, Nothing, Any]] =
    Chunk(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
}
