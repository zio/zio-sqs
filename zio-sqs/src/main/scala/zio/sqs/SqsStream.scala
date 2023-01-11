package zio.sqs

import zio.aws.sqs._
import zio.aws.sqs.model._
import zio.{ RIO, ZIO }
import zio.stream.ZStream
import zio.aws.sqs.model.primitives.MessageAttributeName

object SqsStream {

  def apply(
    queueUrl: String,
    settings: SqsStreamSettings = SqsStreamSettings()
  ): ZStream[Sqs, Throwable, Message.ReadOnly] = {

    val request = ReceiveMessageRequest(
      queueUrl = queueUrl,
      attributeNames = Some(settings.attributeNames),
      messageAttributeNames = Some(settings.messageAttributeNames.map(MessageAttributeName.apply(_))),
      maxNumberOfMessages = Some(settings.maxNumberOfMessages),
      visibilityTimeout = Some(settings.visibilityTimeout.getOrElse(30)),
      waitTimeSeconds = Some(settings.waitTimeSeconds.getOrElse(20))
    )

    ZStream
      .repeatZIO(
        zio.aws.sqs.Sqs
          .receiveMessage(request)
          .mapError(_.toThrowable)
      )
      .map(_.messages.getOrElse(List.empty))
      .takeWhile(_.nonEmpty || !settings.stopWhenQueueEmpty)
      .mapConcat(identity)
      .mapZIO(msg => ZIO.when(settings.autoDelete)(deleteMessage(queueUrl, msg)).as(msg))
  }

  def deleteMessage(queueUrl: String, msg: Message.ReadOnly): RIO[Sqs, Unit] =
    zio.aws.sqs.Sqs.deleteMessage(DeleteMessageRequest(queueUrl, msg.receiptHandle.getOrElse(""))).mapError(_.toThrowable)
}
