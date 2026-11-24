package zio.sqs.producer
import zio.aws.sqs.model.BatchResultErrorEntry

/**
 * Encodes an error for the published message.
 * Its `getMessage` has the form `senderFault: <senderFault>, code: <code>, message: <message or empty>`.
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

/**
 * Factory and recoverability check for [[ProducerError]].
 */
object ProducerError {

  /**
   * The error codes that count as recoverable: `ServiceUnavailable` and `ThrottlingException`.
   */
  private val RecoverableCodes = Set(
    "ServiceUnavailable",
    "ThrottlingException"
  )

  /**
   * Creates a new `ProducerError` from the failed result entry and the event that was published.
   */
  def apply[T](entry: BatchResultErrorEntry.ReadOnly, event: ProducerEvent[T]): ProducerError[T] =
    ProducerError(
      senderFault = entry.senderFault,
      code = entry.code,
      message = entry.message.toOption,
      event = event
    )

  /**
   * Checks whether the provided error code is recoverable. Only `ServiceUnavailable` and `ThrottlingException` are.
   * @param code code to check.
   * @return true if the code is recoverable, otherwise false.
   */
  def isRecoverable(code: String): Boolean =
    RecoverableCodes.contains(code)

}
