package zio.sqs.producer

import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry

final case class ProducerError[T](
  senderFault: Boolean,
  code: String,
  message: Option[String],
  event: ProducerEvent[T]
) extends RuntimeException(s"senderFault: $senderFault, code: $code, message: ${message.getOrElse("")}")

object ProducerError {

  private val RecoverableCodes = Set(
    "ServiceUnavailable",
    "ThrottlingException"
  )

  def apply[T](entry: BatchResultErrorEntry, event: ProducerEvent[T]): ProducerError[T] = ProducerError(
    senderFault = entry.senderFault(),
    code = entry.code(),
    message = Option(entry.message()),
    event = event
  )

  def isRecoverable(code: String): Boolean =
    RecoverableCodes.contains(code)

}
