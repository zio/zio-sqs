package zio.sqs

import zio.Schedule.WithState
import zio.aws.sqs._
import zio.aws.sqs.model._
import zio._
import zio.stream.ZStream
import zio.aws.sqs.model.primitives.MessageAttributeName

import java.time.OffsetDateTime

object SqsStream {

  def apply(
             queueUrl: String,
             settings: SqsStreamSettings = SqsStreamSettings()
           ): ZStream[Sqs, Throwable, Message.ReadOnly] = {

    val request = ReceiveMessageRequest(
      queueUrl = queueUrl,
      attributeNames = Some(settings.attributeNames),
      messageAttributeNames = Some(settings.messageAttributeNames.map(MessageAttributeName.apply(_))),
      maxNumberOfMessages = Some(settings.maxNumberOfMessages),
      visibilityTimeout = Some(settings.visibilityTimeout.getOrElse(30)),
      waitTimeSeconds = Some(settings.waitTimeSeconds.getOrElse(20))
    )

    ZStream
      .repeatZIO(
        zio.aws.sqs.Sqs
          .receiveMessage(request)
          .mapError(_.toThrowable)
      )
      .map(_.messages.getOrElse(List.empty))
      .takeWhile(_.nonEmpty || !settings.stopWhenQueueEmpty)
      .mapConcat(identity)
      .mapZIO(msg => ZIO.when(settings.autoDelete)(deleteMessage(queueUrl, msg)).as(msg))
  }

  def deleteMessage(queueUrl: String, msg: Message.ReadOnly): RIO[Sqs, Unit] =
    zio.aws.sqs.Sqs.deleteMessage(DeleteMessageRequest(queueUrl, msg.receiptHandle.getOrElse(""))).mapError(_.toThrowable)

  /**
   * Processes SQS messages from the queue using a given function and
   * deletes the messages from the queue only if they processed successfully.
   *
   * If the message fails to be processed (=error or defect),
   * it will reappear in the queue after the `visibilityTimeout` expires.
   * If a DLQ is attached to the queue, the message will move into the DLQ after a set number of retries.
   *
   * Returns a stream original message and the outcome of the processing, to allow attaching logging or metrics.
   * The returned Stream is also convenient for testing:
   * exposing what was processed and allowing to stop the stream on some ocndition.
   *
   * @param url                 the URL of the SQS queue
   * @param settings            the settings for the stream of messages from the queue
   * @param deleteRetrySchedule The schedule by which to retry deleting the message (which should be rare)
   * @param process             the function that takes an ObjectSummary and returns a ZIO effect
   * @tparam E the error type of the ZIO effect
   * @tparam A the value type of the ZIO effect
   * @return a ZStream that can either fail with a Throwable or emit values of type Either[(Message.ReadOnly, Cause[E]), A]
   */
  def processMessages[E, A](url: String, settings: SqsStreamSettings,
                            parallelism: Int = 1,
                            deleteRetrySchedule: Schedule[Any, Any, zio.Duration] = defaultDeleteRetryPolicy)
                           (process: Message.ReadOnly => ZIO[Any, E, A]): ZStream[Sqs, Throwable, Either[(Message.ReadOnly, Cause[E]), A]] =
    zio.sqs.SqsStream(url, settings)
      // Unordered since SQS (and queues in general) don't have ordering semantics
      .mapZIOParUnordered(parallelism) { msg =>
        process(msg)
          // Capture any errors and defects
          .sandbox
          .zipLeft {
            SqsStream.deleteMessage(url, msg)
              // Retry in case it fails to delete transiently
              .retry(defaultDeleteRetryPolicy)
              // Die if it fails and stop the stream, to avoid reprocessing validly processed messages
              // This would be very rare and caught early in testing.
              .orDie
          }.mapError(err => (msg, err))
          .either // Move the error to the return value, so that it doesn't stop the stream
      }

  private val defaultDeleteRetryPolicy: Schedule[Any, Any, zio.Duration] =
    (Schedule.exponential(10.milliseconds) >>> Schedule.elapsed).whileOutput(_ < 30.seconds)
}
