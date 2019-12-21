package zio.sqs

import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry

final case class SqsPublishEventError(
  id: String,
  senderFault: Boolean,
  code: String,
  message: Option[String],
  event: SqsPublishEvent
)

object SqsPublishEventError {

  def apply(entry: BatchResultErrorEntry, event: SqsPublishEvent): SqsPublishEventError = SqsPublishEventError(
    id = entry.id(),
    senderFault = entry.senderFault(),
    code = entry.code(),
    message = Option(entry.message()),
    event = event
  )

}
