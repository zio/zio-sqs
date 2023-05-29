package zio.sqs
import zio.test._
import zio._
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.SendMessageRequest
object SqsConsumerSpec extends ZIOSpecDefault {

  def produce(url: String, message: String): ZIO[Sqs, Throwable, Unit] =
    Sqs.sendMessage(SendMessageRequest(url, message)).unit.mapError(_.toThrowable)
  override def spec: Spec[TestEnvironment with Scope, Any]             =
    suite("SQS Consumer spec")(
      test("retry failed messages indefinitely") {
        val settings = SqsStreamSettings(
          autoDelete = false,
          stopWhenQueueEmpty = true,
          visibilityTimeout = Some(1),
          waitTimeSeconds = Some(2) // more than visibilityTimeout to allow errors to reappear
        )
        for {
          _        <- Utils.createQueue("test-queue")
          url      <- Utils.getQueueUrl("test-queue")
          _        <- produce(url, "1")
          _        <- produce(url, "not number1")
          _        <- produce(url, "2")
          messages <- SqsStream
                        .processMessages(url, settings)(
                          _.getBody.flatMap(body => ZIO.attempt(body.toInt))
                        )
                        .take(3 + 2)
                        .runCollect
          successes = messages.collect { case Right(v) => v }
          failures  = messages.collect { case Left(v) => v }
        } yield assertTrue(
          successes == Chunk(1, 2),
          failures.length == 3, // It is retried multiple times
          failures.map(_._1.body.toOption.get).distinct.length == 1
        )
      }
    ).provideLayer(MockSqsServerAndClient.layer)
}
