package zio.sqs

import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry

final case class SqsPublishEventResult(
  id: String,
  md5OfMessageAttributes: Option[String],
  md5OfMessageBody: String,
  md5OfMessageSystemAttributes: Option[String],
  messageId: String,
  sequenceNumber: Option[String],
  event: SqsPublishEvent
)

object SqsPublishEventResult {

  def apply(res: SendMessageBatchResultEntry, event: SqsPublishEvent): SqsPublishEventResult = SqsPublishEventResult(
    id = res.id(),
    md5OfMessageAttributes = Option(res.md5OfMessageAttributes()),
    md5OfMessageBody = res.md5OfMessageBody(),
    md5OfMessageSystemAttributes = Option(res.md5OfMessageSystemAttributes()),
    messageId = res.messageId(),
    sequenceNumber = Option(res.sequenceNumber()),
    event = event
  )

}
