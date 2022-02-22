package zio.sqs

import zio.aws.sqs.Sqs
import zio.aws.sqs.model.{ CreateQueueRequest, GetQueueUrlRequest, QueueAttributeName }
import zio.RIO

object Utils {
  def createQueue(
    name: String,
    attributes: Map[QueueAttributeName, String] = Map()
  ): RIO[Sqs, Unit] =
    zio.aws.sqs.Sqs
      .createQueue(CreateQueueRequest(name, Some(attributes)))
      .mapError(_.toThrowable)
      .unit

  def getQueueUrl(name: String): RIO[Sqs, String] =
    zio.aws.sqs.Sqs
      .getQueueUrl(GetQueueUrlRequest(name))
      .flatMap(_.getQueueUrl)
      .mapError(_.toThrowable)
}
