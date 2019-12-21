package zio.sqs

import java.util.function.BiFunction

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{SendMessageBatchRequest, SendMessageBatchRequestEntry, SendMessageBatchResponse}
import zio.clock.Clock
import zio.stream.{Sink, Stream, ZStream}
import zio.{IO, Schedule, Task}

import scala.jdk.CollectionConverters._

object SqsPublisherStream {

  def sendStream(
    client: SqsAsyncClient,
    queueUrl: String,
    settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
  )(
    es: Stream[Throwable, SqsPublishEvent]
  ): ZStream[Clock, Throwable, SqsPublishErrorOrEvent] =
    es.aggregateAsyncWithin(
        Sink.collectAllN[SqsPublishEvent](settings.batchSize.toLong),
        Schedule.spaced(settings.duration)
      )
      .map(buildSendMessageBatchRequest(queueUrl, _))
      .mapMParUnordered(settings.parallelism)(it => runSendMessageBatchRequest(client, it._1, it._2))
      .mapConcat(identity)

  private[sqs] def buildSendMessageBatchRequest(
    queueUrl: String,
    es: List[SqsPublishEvent]
  ): (SendMessageBatchRequest, List[(SqsPublishEvent, Int)]) = {
    val indexedMessages = es.zipWithIndex
    val entries = indexedMessages.map {
      case (m: SqsPublishEvent, id: Int) =>
        SendMessageBatchRequestEntry
          .builder()
          .id(id.toString)
          .messageBody(m.body)
          .messageAttributes(m.attributes.asJava)
          .messageGroupId(m.groupId.orNull)
          .messageDeduplicationId(m.deduplicationId.orNull)
          .build()
    }

    val req = SendMessageBatchRequest
      .builder()
      .queueUrl(queueUrl)
      .entries(entries.asJava)
      .build()

    (req, indexedMessages)
  }

  private[sqs] def runSendMessageBatchRequest(
    client: SqsAsyncClient,
    req: SendMessageBatchRequest,
    indexedMessages: List[(SqsPublishEvent, Int)]
  ): Task[List[SqsPublishErrorOrEvent]] =
    Task.effectAsync[List[SqsPublishErrorOrEvent]]({ cb =>
      client
        .sendMessageBatch(req)
        .handleAsync[Unit](new BiFunction[SendMessageBatchResponse, Throwable, Unit] {
          override def apply(res: SendMessageBatchResponse, err: Throwable): Unit =
            err match {
              case null =>
                val m = indexedMessages.map(it => (it._2.toString, it._1)).toMap

                val ss = res
                  .successful()
                  .asScala
                  .map(res => Right(SqsPublishEventResult(res, m(res.id()))): SqsPublishErrorOrEvent)
                  .toList

                val es = res
                  .failed()
                  .asScala
                  .map(err => Left(SqsPublishEventError(err, m(err.id()))): SqsPublishErrorOrEvent)
                  .toList

                cb(IO.succeed(ss ++ es))
              case ex =>
                cb(IO.fail(ex))
            }
        })
      ()
    })
}
