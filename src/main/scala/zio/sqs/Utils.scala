package zio.sqs

import io.github.vigoo.zioaws
import io.github.vigoo.zioaws.sqs.Sqs
import io.github.vigoo.zioaws.sqs.model.{ CreateQueueRequest, GetQueueUrlRequest, QueueAttributeName }
import zio.RIO

object Utils {
  def createQueue(
    name: String,
    attributes: Map[QueueAttributeName, String] = Map()
  ): RIO[Sqs, Unit] =
    zioaws.sqs
      .createQueue(CreateQueueRequest(name, Some(attributes)))
      .mapError(_.toThrowable)
      .unit

  def getQueueUrl(name: String): RIO[Sqs, String] =
    zioaws.sqs
      .getQueueUrl(GetQueueUrlRequest(name))
      .flatMap(_.queueUrl)
      .mapError(_.toThrowable)
}
