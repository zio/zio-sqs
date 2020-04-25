package zio.sqs

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._
import zio.stream.Stream
import zio.{ IO, Task }

import scala.jdk.CollectionConverters._

object SqsStream {

  def apply(
    client: SqsAsyncClient,
    queueUrl: String,
    settings: SqsStreamSettings = SqsStreamSettings()
  ): Stream[Throwable, Message] = {

    val builder = ReceiveMessageRequest.builder
      .queueUrl(queueUrl)
      .attributeNamesWithStrings(settings.attributeNames.asJava)
      .messageAttributeNames(settings.messageAttributeNames.asJava)
      .maxNumberOfMessages(settings.maxNumberOfMessages)

    settings.visibilityTimeout.foreach(builder.visibilityTimeout(_))
    settings.waitTimeSeconds.foreach(builder.waitTimeSeconds(_))

    val request = builder.build

    Stream.fromEffect {
      Task.effectAsync[List[Message]] { cb =>
        client
          .receiveMessage(request)
          .handle[Unit] { (result, err) =>
            err match {
              case null => cb(IO.succeed(result.messages.asScala.toList))
              case ex   => cb(IO.fail(ex))
            }
          }
        ()
      }
    }.forever
      .takeWhile(_.nonEmpty || !settings.stopWhenQueueEmpty)
      .flatMap[Any, Throwable, Message](Stream.fromIterable(_))
      .mapM(msg => IO.when(settings.autoDelete)(deleteMessage(client, queueUrl, msg)).as(msg))
  }

  def deleteMessage(client: SqsAsyncClient, queueUrl: String, msg: Message): Task[Unit] =
    Task.effectAsync[Unit] { cb =>
      client
        .deleteMessage(
          DeleteMessageRequest
            .builder()
            .queueUrl(queueUrl)
            .receiptHandle(msg.receiptHandle())
            .build()
        )
        .handle[Unit] { (_, err) =>
          err match {
            case null => cb(IO.unit)
            case ex   => cb(IO.fail(ex))
          }
        }
      ()
    }
}
