package zio.sqs

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

final case class SqsPublishEvent(
  body: String,
  attributes: Map[String, MessageAttributeValue],
  groupId: Option[String],
  deduplicationId: Option[String]
)

object SqsPublishEvent {

  def apply(body: String): SqsPublishEvent = SqsPublishEvent(
    body = body,
    attributes = Map.empty[String, MessageAttributeValue],
    groupId = None,
    deduplicationId = None
  )

}
