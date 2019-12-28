package zio.sqs

import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry

final case class SqsPublishEventError[T](
  senderFault: Boolean,
  code: String,
  message: Option[String],
  event: SqsPublishEvent[T]
) extends RuntimeException(s"senderFault: ${senderFault}, code: $code, message: ${message.getOrElse("")}")

object SqsPublishEventError {

  private val RecoverableCodes = Set(
    "ServiceUnavailable",
    "ThrottlingException"
  )

  def apply[T](entry: BatchResultErrorEntry, event: SqsPublishEvent[T]): SqsPublishEventError[T] = SqsPublishEventError(
    senderFault = entry.senderFault(),
    code = entry.code(),
    message = Option(entry.message()),
    event = event
  )

  def isRecoverable(code: String): Boolean =
    RecoverableCodes.contains(code)

}
