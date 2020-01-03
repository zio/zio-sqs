package zio.sqs.producer

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

final case class ProducerEvent[T](
  data: T,
  attributes: Map[String, MessageAttributeValue],
  groupId: Option[String],
  deduplicationId: Option[String]
)

object ProducerEvent {

  def apply(body: String): ProducerEvent[String] = ProducerEvent(
    data = body,
    attributes = Map.empty[String, MessageAttributeValue],
    groupId = None,
    deduplicationId = None
  )

}
