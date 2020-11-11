package zio.sqs

import io.github.vigoo.zioaws.sqs
import io.github.vigoo.zioaws.sqs._
import io.github.vigoo.zioaws.sqs.model._
import zio.ZIO
import zio.stream.ZStream

object SqsStream {

  def apply(
    queueUrl: String,
    settings: SqsStreamSettings = SqsStreamSettings()
  ): ZStream[Sqs, Throwable, Message.ReadOnly] = {

    val request = ReceiveMessageRequest(
      queueUrl,
      Some(settings.attributeNames),
      Some(settings.messageAttributeNames),
      Some(settings.maxNumberOfMessages),
      settings.visibilityTimeout.map(_.toInt),
      settings.waitTimeSeconds.map(_.toInt)
    )

    ZStream
      .repeatEffect(
        sqs
          .receiveMessage(request)
          .mapError(_.toThrowable)
      )
      .map(_.messagesValue.getOrElse(List.empty))
      .takeWhile(_.nonEmpty || !settings.stopWhenQueueEmpty)
      .mapConcat(identity)
      .mapM(msg => ZIO.when(settings.autoDelete)(deleteMessage(queueUrl, msg)).as(msg))
  }

  def deleteMessage(queueUrl: String, msg: Message.ReadOnly): ZIO[Sqs, Throwable, Unit] =
    sqs.deleteMessage(DeleteMessageRequest(queueUrl, msg.receiptHandleValue.getOrElse(""))).mapError(_.toThrowable)
}
