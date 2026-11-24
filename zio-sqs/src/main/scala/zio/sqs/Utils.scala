package zio.sqs

import zio.RIO
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.CreateQueueRequest
import zio.aws.sqs.model.GetQueueUrlRequest
import zio.aws.sqs.model.QueueAttributeName

/**
 * Helpers for creating an SQS queue and finding its URL.
 */
object Utils {

  /**
   * Creates an SQS queue with the given name and attributes.
   * @param name is the name of the queue
   * @param attributes are the queue attributes to set when the queue is created
   */
  def createQueue(
    name: String,
    attributes: Map[QueueAttributeName, String] = Map()
  ): RIO[Sqs, Unit] =
    zio.aws.sqs.Sqs
      .createQueue(CreateQueueRequest(name, Some(attributes)))
      .mapError(_.toThrowable)
      .unit

  /**
   * Returns the URL of the SQS queue with the given name. Fails with the error from SQS when the request fails.
   * @param name is the name of the queue
   * @return the queue URL
   */
  def getQueueUrl(name: String): RIO[Sqs, String] =
    zio.aws.sqs.Sqs
      .getQueueUrl(GetQueueUrlRequest(name))
      .flatMap(_.getQueueUrl)
      .mapError(_.toThrowable)
}
