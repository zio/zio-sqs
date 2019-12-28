package zio.sqs.serialization

import zio.test.Assertion._
import zio.test._

object SerializerSpec
    extends DefaultRunnableSpec(
      suite("Serializer")(
        test("serializeString can serialize the string") {
          val input    = "some-string-value"
          val expected = input
          val actual   = Serializer.serializeString(input)

          assert(actual, equalTo(expected))
        }
      ),
      List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
    )
