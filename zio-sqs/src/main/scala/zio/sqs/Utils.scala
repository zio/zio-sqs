package zio.sqs

import zio.RIO
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.CreateQueueRequest
import zio.aws.sqs.model.GetQueueUrlRequest
import zio.aws.sqs.model.QueueAttributeName

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
