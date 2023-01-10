package zio.sqs.producer

import zio.aws.sqs.model.MessageAttributeValue
import zio.Duration

/**
 * Event to publish to SQS.
 * @param data payload to publish.
 * @param attributes a map of [[https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-message-attributes.html attributes]] to set.
 * @param groupId assigns a specific [[https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagegroupid-property.html message group]] to the message.
 * @param deduplicationId token used for [[https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagededuplicationid-property.html deduplication]] of sent messages.
 * @param delay in order to  [[https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-message-timers.html delay delivery]] of the message. Allowed values: 0 to 15 minutes.
 * @tparam T type of the payload for the event.
 */
final case class ProducerEvent[T](
  data: T,
  attributes: Map[String, MessageAttributeValue],
  groupId: Option[String],
  deduplicationId: Option[String],
  delay: Option[Duration] = None
)

object ProducerEvent {

  /**
   * Creates an event from a string without additional attributes or parameters.
   */
  def apply(body: String): ProducerEvent[String] =
    ProducerEvent(
      data = body,
      attributes = Map.empty[String, MessageAttributeValue],
      groupId = None,
      deduplicationId = None,
      delay = None
    )

}
