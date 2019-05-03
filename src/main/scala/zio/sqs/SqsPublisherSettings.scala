package zio.sqs

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

case class SqsPublisherSettings(
  delaySeconds: Option[Int] = None,
  messageAttributes: Map[String, MessageAttributeValue] = Map(),
  messageDeduplicationId: String = "",
  messageGroupId: String = ""
)
