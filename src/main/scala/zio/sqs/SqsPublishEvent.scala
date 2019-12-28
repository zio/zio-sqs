package zio.sqs

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

final case class SqsPublishEvent[T](
  data: T,
  attributes: Map[String, MessageAttributeValue],
  groupId: Option[String],
  deduplicationId: Option[String]
)

object SqsPublishEvent {

  def apply(body: String): SqsPublishEvent[String] = SqsPublishEvent(
    data = body,
    attributes = Map.empty[String, MessageAttributeValue],
    groupId = None,
    deduplicationId = None
  )

}
