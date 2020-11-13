package zio.sqs

import io.github.vigoo.zioaws
import io.github.vigoo.zioaws.sqs.Sqs
import io.github.vigoo.zioaws.sqs.model.{ CreateQueueRequest, GetQueueUrlRequest, QueueAttributeName }
import zio.ZIO

object Utils {
  def createQueue(
    name: String,
    attributes: Map[QueueAttributeName, String] = Map()
  ): ZIO[Sqs, Throwable, Unit] =
    zioaws.sqs
      .createQueue(CreateQueueRequest(name, Some(attributes)))
      .mapError(_.toThrowable)
      .unit

  def getQueueUrl(name: String): ZIO[Sqs, Throwable, String] =
    zioaws.sqs
      .getQueueUrl(GetQueueUrlRequest(name))
      .flatMap(_.queueUrl)
      .mapError(_.toThrowable)
}
