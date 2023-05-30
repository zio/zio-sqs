package zio.sqs

import zio.aws.sqs._
import zio.aws.sqs.model._
import zio._
import zio.stream._
import zio.aws.sqs.model.primitives.MessageAttributeName

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
   * Wraps the messages in a scoped ZIO that will delete the message from the queue only if the ZIO succeeds.
   * Lower level api, e.g. when you want to batch-process messages, or expose parsed messages to your users.
   * Use `processMessages` for the common case.
   */
  def deletingOnSuccess(
    url: String,
    settings: SqsStreamSettings = SqsStreamSettings(),
    deleteRetrySchedule: Schedule[Any, Any, Any] = defaultDeleteRetryPolicy
  ): ZStream[Sqs, Throwable, ZIO[Scope, Nothing, Message.ReadOnly]] =
    SqsStream(url, settings).mapZIO { msg: Message.ReadOnly =>
      ZIO.serviceWith[Sqs] { sqs =>
        ZIO.succeedNow(msg).withFinalizerExit { (msg, exit) =>
          exit match {
            case Exit.Success(_) =>
              SqsStream
                .deleteMessage(url, msg)
                .provideEnvironment(ZEnvironment(sqs))
                // Retry in case it fails to delete transiently
                .retry(deleteRetrySchedule)
                // Die if it fails and stop the stream, to avoid reprocessing validly processed messages
                // This would be very rare and caught early in testing.
                .orDie
            case Exit.Failure(_) =>
              ZIO.unit
          }
        }
      }
    }

  /**
   * Processes SQS messages from the queue using a given function and
   * deletes the messages from the queue only if they processed successfully.
   * This ensures that no message is lost in case of an error.
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
   * @param parallelism         how many messages are processed in parallel
   * @param deleteRetrySchedule the schedule by which to retry deleting the message (which should be rare)
   * @param process             the function that takes an ObjectSummary and returns a ZIO effect
   * @tparam E the error type of the ZIO effect
   * @tparam A the value type of the ZIO effect
   * @return a ZStream that can either fail with a Throwable or emit values of type Either[(Message.ReadOnly, Cause[E]), A]
   */
  def processMessages[E, A](
    url: String,
    settings: SqsStreamSettings,
    parallelism: Int = 1,
    deleteRetrySchedule: Schedule[Any, Any, Any] = defaultDeleteRetryPolicy
  )(process: Message.ReadOnly => ZIO[Any, E, A]): ZStream[Sqs, Throwable, Either[Cause[E], A]] =
    zio.sqs.SqsStream
      .deletingOnSuccess(url, settings, deleteRetrySchedule)
      .mapZIOPar(parallelism) { msg =>
        ZIO
          .scoped[Any] {
            msg.flatMap(process)
          }
          .sandbox // Capture any errors and defects
          .either // Move the error to the return value, so that it doesn't stop the stream
      }

  private val defaultDeleteRetryPolicy: Schedule[Any, Any, Any] =
    Schedule.exponential(10.milliseconds) && Schedule.upTo(30.seconds)
}
