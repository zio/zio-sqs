package zio.sqs

import zio.aws.sqs.model._
import zio._

/**
 * Configuration settings for consuming messages from an SQS queue.
 *
 * @param attributeNames List of queue attribute names to retrieve with each message. See AWS SQS API documentation for valid values.
 * @param maxNumberOfMessages Maximum number of messages to retrieve in a single request. Valid values are between 1 and 10.
 * @param messageAttributeNames List of message attribute names to retrieve with each message.
 * @param visibilityTimeout The duration (in seconds) that the received messages are hidden from subsequent retrieve requests.
 *                         If None, uses the queue's default visibility timeout. See AWS SQS Visibility Timeout documentation.
 * @param waitTimeSeconds The duration (in seconds) for which the call waits for messages to arrive in the queue before returning.
 *                       Maximum is 20 seconds. If None, uses the queue's default wait time. Enables long polling when > 0.
 *                       Note that setting this to None will use short polling and increases the number of requests made to SQS causing an increase in costs.
 * @param autoDelete If true, messages will be automatically deleted from the queue when consumed by the stream.
 *                  If false, messages must be explicitly deleted using `SqsStream.deleteMessage`.
 * @param stopWhenQueueEmpty If true, the stream will stop when the queue is empty.
 *                          If false, the stream will continue polling for new messages indefinitely.
 */
final case class SqsStreamSettings(
  attributeNames: List[QueueAttributeName],
  maxNumberOfMessages: Option[Int],
  messageAttributeNames: List[String],
  visibilityTimeout: Option[Int],
  waitTimeSeconds: Option[Int],
  autoDelete: Boolean,
  stopWhenQueueEmpty: Boolean
)                        {
  def withAttributeName(attributeName: QueueAttributeName): SqsStreamSettings =
    copy(attributeNames = attributeName :: attributeNames)

  def withAttributeNames(attributeNames: List[QueueAttributeName]): SqsStreamSettings =
    copy(attributeNames = attributeNames)

  def withMaxNumberOfMessages(maxNumberOfMessages: Int): SqsStreamSettings =
    copy(maxNumberOfMessages = Some(maxNumberOfMessages))

  def withMessageAttributeName(name: String): SqsStreamSettings =
    copy(messageAttributeNames = name :: messageAttributeNames)

  def withMessageAttributeNames(names: List[String]): SqsStreamSettings =
    copy(messageAttributeNames = messageAttributeNames ::: names)

  def withVisibilityTimeout(seconds: Int): SqsStreamSettings =
    copy(visibilityTimeout = Some(seconds))

  def withWaitTimeSeconds(seconds: Int): SqsStreamSettings =
    copy(waitTimeSeconds = Some(seconds))

  def withAutoDelete(autoDelete: Boolean): SqsStreamSettings =
    copy(autoDelete = autoDelete)

  def withStopWhenQueueEmpty(stopWhenQueueEmpty: Boolean): SqsStreamSettings =
    copy(stopWhenQueueEmpty = stopWhenQueueEmpty)
}
object SqsStreamSettings {
  val default = SqsStreamSettings(
    attributeNames = Nil,
    maxNumberOfMessages = None,
    messageAttributeNames = Nil,
    visibilityTimeout = None,
    waitTimeSeconds = Some(20), // Long polling by default
    autoDelete = false,
    stopWhenQueueEmpty = false
  )
}

final case class SqsMessageLifetimeExtensionSettings(
  automaticExtension: Boolean,
  maximumRetries: Int,
  overrideInitialDelay: Option[Duration],
  overrideRepeatSchedule: Option[Schedule[Any, Any, Any]]
)                                          {
  def withAutomaticExtension(automaticExtension: Boolean): SqsMessageLifetimeExtensionSettings =
    copy(automaticExtension = automaticExtension)

  def withMaximumRetries(maximumRetries: Int): SqsMessageLifetimeExtensionSettings =
    copy(maximumRetries = maximumRetries)

  def withOverrideInitialDelay(overrideInitialDelay: Duration): SqsMessageLifetimeExtensionSettings =
    copy(overrideInitialDelay = Some(overrideInitialDelay))

  def schedule(settings: SqsStreamSettings): Schedule[Any, Any, Any] =
    overrideRepeatSchedule.getOrElse(
      Schedule.spaced(
        settings.visibilityTimeout
          .map(_ / 2)
          .getOrElse(15)
          .seconds
      )
    )

  def initialDelay(settings: SqsStreamSettings): Duration =
    overrideInitialDelay.getOrElse(
      settings.visibilityTimeout
        .map(secs => (secs / 2).seconds)
        .getOrElse(15.seconds)
    )
}
object SqsMessageLifetimeExtensionSettings {
  val default: SqsMessageLifetimeExtensionSettings = SqsMessageLifetimeExtensionSettings(
    automaticExtension = true,
    maximumRetries = 16,
    overrideInitialDelay = None,
    overrideRepeatSchedule = None
  )
}
