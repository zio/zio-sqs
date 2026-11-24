package zio.sqs

import zio._
import zio.aws.sqs.model._

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
) {

  /**
   * Adds an attribute name to the names requested with each message. The name is added at the front of the list.
   */
  def withAttributeName(attributeName: QueueAttributeName): SqsStreamSettings =
    copy(attributeNames = attributeName :: attributeNames)

  /**
   * Replaces the attribute names requested with each message.
   */
  def withAttributeNames(attributeNames: List[QueueAttributeName]): SqsStreamSettings =
    copy(attributeNames = attributeNames)

  /**
   * Sets the maximum number of messages to receive in one request. The valid range is 1 to 10.
   */
  def withMaxNumberOfMessages(maxNumberOfMessages: Int): SqsStreamSettings =
    copy(maxNumberOfMessages = Some(maxNumberOfMessages))

  /**
   * Adds a message attribute name to the names requested with each message. The name is added at the front of the list.
   */
  def withMessageAttributeName(name: String): SqsStreamSettings =
    copy(messageAttributeNames = name :: messageAttributeNames)

  /**
   * Appends the given message attribute names to the names requested with each message.
   */
  def withMessageAttributeNames(names: List[String]): SqsStreamSettings =
    copy(messageAttributeNames = messageAttributeNames ::: names)

  /**
   * Sets the visibility timeout of received messages, in seconds.
   */
  def withVisibilityTimeout(seconds: Int): SqsStreamSettings =
    copy(visibilityTimeout = Some(seconds))

  /**
   * Sets how long a receive request waits for messages, in seconds. A value above 0 enables long polling.
   */
  def withWaitTimeSeconds(seconds: Int): SqsStreamSettings =
    copy(waitTimeSeconds = Some(seconds))

  /**
   * Sets whether messages are deleted when the stream receives them, before they are processed.
   */
  def withAutoDelete(autoDelete: Boolean): SqsStreamSettings =
    copy(autoDelete = autoDelete)

  /**
   * Sets whether the stream ends when a receive request returns no messages.
   */
  def withStopWhenQueueEmpty(stopWhenQueueEmpty: Boolean): SqsStreamSettings =
    copy(stopWhenQueueEmpty = stopWhenQueueEmpty)
}

/**
 * Factory for [[SqsStreamSettings]].
 */
object SqsStreamSettings {

  /**
   * Long polling with a wait of 20 seconds, no automatic deletion, and a stream that keeps polling when the queue is empty.
   */
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

/**
 * Settings for extending the visibility timeout of messages while they are processed by [[SqsStream.consumeChunkAtLeastOnce]].
 *
 * @param automaticExtension If true, the visibility timeout of the messages is extended while they are processed.
 * @param maximumRetries Currently used as the new visibility timeout, in seconds, for each automatic extension. It is not used as a retry count.
 * @param overrideInitialDelay If set, the delay before the first extension. If None, half of the visibility timeout, or 15 seconds when no visibility timeout is set.
 * @param overrideRepeatSchedule If set, the schedule for the repeated extensions. If None, every half of the visibility timeout, or every 15 seconds when no visibility timeout is set.
 */
final case class SqsMessageLifetimeExtensionSettings(
  automaticExtension: Boolean,
  maximumRetries: Int,
  overrideInitialDelay: Option[Duration],
  overrideRepeatSchedule: Option[Schedule[Any, Any, Any]]
) {

  /**
   * Sets whether the visibility timeout is extended while messages are processed.
   */
  def withAutomaticExtension(automaticExtension: Boolean): SqsMessageLifetimeExtensionSettings =
    copy(automaticExtension = automaticExtension)

  /**
   * Sets the value of `maximumRetries`. See the class documentation for how it is used.
   */
  def withMaximumRetries(maximumRetries: Int): SqsMessageLifetimeExtensionSettings =
    copy(maximumRetries = maximumRetries)

  /**
   * Sets the delay before the first extension.
   */
  def withOverrideInitialDelay(overrideInitialDelay: Duration): SqsMessageLifetimeExtensionSettings =
    copy(overrideInitialDelay = Some(overrideInitialDelay))

  /**
   * Returns the schedule that repeats the extension. It is `overrideRepeatSchedule` when set. Otherwise it repeats every half of
   * the visibility timeout in `settings`, or every 15 seconds when `settings` has no visibility timeout.
   */
  def schedule(settings: SqsStreamSettings): Schedule[Any, Any, Any] =
    overrideRepeatSchedule.getOrElse(
      Schedule.spaced(
        settings.visibilityTimeout
          .map(_ / 2)
          .getOrElse(15)
          .seconds
      )
    )

  /**
   * Returns the delay before the first extension. It is `overrideInitialDelay` when set. Otherwise it is half of the
   * visibility timeout in `settings`, or 15 seconds when `settings` has no visibility timeout.
   */
  def initialDelay(settings: SqsStreamSettings): Duration =
    overrideInitialDelay.getOrElse(
      settings.visibilityTimeout
        .map(secs => (secs / 2).seconds)
        .getOrElse(15.seconds)
    )
}

/**
 * Factory for [[SqsMessageLifetimeExtensionSettings]].
 */
object SqsMessageLifetimeExtensionSettings {

  /**
   * Automatic extension on, `maximumRetries` of 16, and no overrides for the initial delay or the schedule.
   */
  val default: SqsMessageLifetimeExtensionSettings = SqsMessageLifetimeExtensionSettings(
    automaticExtension = true,
    maximumRetries = 16,
    overrideInitialDelay = None,
    overrideRepeatSchedule = None
  )
}
