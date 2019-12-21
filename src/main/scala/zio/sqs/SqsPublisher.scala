package zio.sqs

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._
import zio.{IO, Task}

import scala.jdk.CollectionConverters._

object SqsPublisher {

  def send(
    client: SqsAsyncClient,
    queueUrl: String,
    msg: String,
    settings: SqsPublisherSettings = SqsPublisherSettings()
  ): Task[Unit] =
    Task.effectAsync[Unit] { cb =>
      client.sendMessage {
        val b1 = SendMessageRequest
          .builder()
          .queueUrl(queueUrl)
          .messageBody(msg)
          .messageAttributes(settings.messageAttributes.asJava)
        val b2 = if (settings.messageGroupId.nonEmpty) b1.messageGroupId(settings.messageGroupId) else b1
        val b3 =
          if (settings.messageDeduplicationId.nonEmpty) b2.messageDeduplicationId(settings.messageDeduplicationId)
          else b2
        val b4 = settings.delaySeconds.fold(b3)(b3.delaySeconds(_))
        b4.build
      }.handle[Unit] { (_, err) =>
        err match {
          case null => cb(IO.unit)
          case ex   => cb(IO.fail(ex))
        }
      }
      ()
    }

}
