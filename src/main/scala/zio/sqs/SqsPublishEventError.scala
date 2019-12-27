package zio.sqs

import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry

final case class SqsPublishEventError(
  senderFault: Boolean,
  code: String,
  message: Option[String],
  event: SqsPublishEvent
)

object SqsPublishEventError {

  private val RecoverableCodes = Set(
    "ServiceUnavailable",
    "ThrottlingException"
  )

  def apply(entry: BatchResultErrorEntry, event: SqsPublishEvent): SqsPublishEventError = SqsPublishEventError(
    senderFault = entry.senderFault(),
    code = entry.code(),
    message = Option(entry.message()),
    event = event
  )

  def isRecoverable(code: String): Boolean =
    RecoverableCodes.contains(code)

}
