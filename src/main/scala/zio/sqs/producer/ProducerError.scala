package zio.sqs.producer

import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry

/**
 * Encodes an error for the published message
 * Fields `senderFault`, `code`, `message` correspond to the respective fields in [[https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_BatchResultErrorEntry.html BatchResultErrorEntry]]
 * @param senderFault whether the error happened due to the caller of the batch API action.
 * @param code an error code representing why the action failed on this entry.
 * @param message a message explaining why the action failed on this entry.
 * @param event event that caused this failure.
 * @tparam T type of the event.
 */
final case class ProducerError[T](
  senderFault: Boolean,
  code: String,
  message: Option[String],
  event: ProducerEvent[T]
) extends RuntimeException(s"senderFault: $senderFault, code: $code, message: ${message.getOrElse("")}")

object ProducerError {

  /**
   * The collection if errors that considered recoverable.
   */
  private val RecoverableCodes = Set(
    "ServiceUnavailable",
    "ThrottlingException"
  )

  /**
   * Creates a new `ProducerError` out of the result entry and the event that was published.
   */
  def apply[T](entry: BatchResultErrorEntry, event: ProducerEvent[T]): ProducerError[T] =
    ProducerError(
      senderFault = entry.senderFault(),
      code = entry.code(),
      message = Option(entry.message()),
      event = event
    )

  /**
   * Checks whether the provided error code considered to be recoverable or not.
   * @param code code to check.
   * @return true if the code considered recoverable, otherwise - false.
   */
  def isRecoverable(code: String): Boolean =
    RecoverableCodes.contains(code)

}
