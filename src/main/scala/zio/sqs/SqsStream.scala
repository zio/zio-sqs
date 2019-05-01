package zio.sqs

import scala.collection.JavaConverters._
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._
import scalaz.zio.{ IO, Task }
import scalaz.zio.stream.Stream

object SqsStream {

  def apply(
    client: SqsAsyncClient,
    queueUrl: String,
    settings: SqsStreamSettings = SqsStreamSettings()
  ): Stream[Throwable, Message] = {

    val request = ReceiveMessageRequest.builder
      .queueUrl(queueUrl)
      .attributeNamesWithStrings(settings.attributeNames.asJava)
      .messageAttributeNames(settings.messageAttributeNames.asJava)
      .maxNumberOfMessages(settings.maxNumberOfMessages)
      .visibilityTimeout(settings.visibilityTimeout)
      .waitTimeSeconds(settings.waitTimeSeconds)
      .build

    Stream.fromEffect {
      IO.effectAsync[Throwable, List[Message]] { cb =>
        client
          .receiveMessage(request)
          .handle[Unit]((result, err) => {
            err match {
              case null => cb(IO.succeed(result.messages.asScala.toList))
              case ex   => cb(IO.fail(ex))
            }
          })
        ()
      }
    }.forever
      .takeWhile(_.nonEmpty || !settings.stopWhenQueueEmpty)
      .flatMap[Any, Throwable, Message](Stream.fromIterable)
      .mapM(msg => IO.when(settings.autoDelete)(deleteMessage(client, queueUrl, msg)).const(msg))
  }

  def deleteMessage(client: SqsAsyncClient, queueUrl: String, msg: Message): Task[Unit] =
    IO.effectAsync[Throwable, Unit] { cb =>
      client
        .deleteMessage(
          DeleteMessageRequest
            .builder()
            .queueUrl(queueUrl)
            .receiptHandle(msg.receiptHandle())
            .build()
        )
        .handle[Unit]((_, err) => {
          err match {
            case null => cb(IO.unit)
            case ex   => cb(IO.fail(ex))
          }
        })
      ()
    }
}
