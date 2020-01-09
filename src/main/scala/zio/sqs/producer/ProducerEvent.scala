package zio.sqs.producer

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

/**
 * Event to publish to SPS.
 * @param data payload to publish.
 * @param attributes a map of [[(https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-message-attributes.html attributes]] to set.
 * @param groupId assigns a specific [[https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagegroupid-property.html message group]] to the message.
 * @param deduplicationId token used for [[https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagededuplicationid-property.html deduplication]] of sent messages.
 * @tparam T type of the payload for the event.
 */
final case class ProducerEvent[T](
  data: T,
  attributes: Map[String, MessageAttributeValue],
  groupId: Option[String],
  deduplicationId: Option[String]
)

object ProducerEvent {

  /**
   * Creates an event from a string without additional attributes or parameters.
   */
  def apply(body: String): ProducerEvent[String] = ProducerEvent(
    data = body,
    attributes = Map.empty[String, MessageAttributeValue],
    groupId = None,
    deduplicationId = None
  )

}
