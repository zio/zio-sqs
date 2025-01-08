package zio.sqs

import zio.aws.sqs.model._
import zio._

case class SqsStreamSettings(
  attributeNames: List[QueueAttributeName] = Nil,
  maxNumberOfMessages: Int = 1,
  messageAttributeNames: List[String] = Nil,
  visibilityTimeout: Option[Int] = Some(30),
  waitTimeSeconds: Option[Int] = Some(20),
  autoDelete: Boolean = true,
  stopWhenQueueEmpty: Boolean = false
)

final case class SqsMessageLifetimeExtensionSettings(
  automaticExtension: Boolean,
  maximumRetries: Int,
  overrideInitialDelay: Option[Duration],
  overrideRepeatSchedule: Option[Schedule[Any, Any, Any]]
)                                          {
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
