package zio.sqs

import zio.aws.sqs.model._
import zio._

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
    waitTimeSeconds = None,
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
