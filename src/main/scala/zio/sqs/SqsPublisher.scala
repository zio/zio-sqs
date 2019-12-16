package zio.sqs

import java.util.function.BiFunction

import scala.jdk.CollectionConverters._
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._
import zio.clock.Clock
import zio.stream.{Sink, Stream, ZStream}
import zio.{IO, Schedule, Task}

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
      }.handle[Unit]((_, err) => {
        err match {
          case null => cb(IO.unit)
          case ex   => cb(IO.fail(ex))
        }
      })
      ()
    }

  trait Event {
    def body: String
    def attributes: Map[String, MessageAttributeValue]
    def groupId: Option[String]
    def deduplicationId: Option[String]
  }

  final case class StringEvent(body: String) extends Event {
    override def attributes: Map[String, MessageAttributeValue] = Map.empty[String, MessageAttributeValue]
    override def groupId: Option[String]                        = None
    override def deduplicationId: Option[String]                = None
  }

  type ErrorOrEvent = Either[(BatchResultErrorEntry, Event), Event]

  def stringStream(
    client: SqsAsyncClient,
    queueUrl: String,
    settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
  )(
    ms: Stream[Throwable, String]
  ): ZStream[Clock, Throwable, ErrorOrEvent] =
    eventStream(client, queueUrl, settings)(ms.map(StringEvent))

  def eventStream(
    client: SqsAsyncClient,
    queueUrl: String,
    settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
  )(
    ms: Stream[Throwable, Event]
  ): ZStream[Clock, Throwable, ErrorOrEvent] =
    ms.aggregateAsyncWithin(Sink.collectAllN[Event](settings.batchSize.toLong), Schedule.spaced(settings.duration))
      .map(buildSendMessageBatchRequest(queueUrl, _))
      .mapMParUnordered(settings.parallelism)(it => runSendMessageBatchRequest(client, it._1, it._2))
      .mapConcat(identity)

  private def buildSendMessageBatchRequest(
    queueUrl: String,
    ms: List[Event]
  ): (SendMessageBatchRequest, List[(Event, Int)]) = {
    val indexedMessages = ms.zipWithIndex
    val entries = indexedMessages.map {
      case (m: Event, id: Int) =>
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

  def runSendMessageBatchRequest(
    client: SqsAsyncClient,
    req: SendMessageBatchRequest,
    indexedMessages: List[(Event, Int)]
  ): Task[List[ErrorOrEvent]] =
    Task.effectAsync[List[ErrorOrEvent]]({ cb =>
      client
        .sendMessageBatch(req)
        .handleAsync[Unit](new BiFunction[SendMessageBatchResponse, Throwable, Unit] {
          override def apply(res: SendMessageBatchResponse, err: Throwable): Unit = {
            err match {
              case null =>
                val m  = indexedMessages.map(it => (it._2.toString, it._1)).toMap
                val ss = res.successful().asScala.map(it => m(it.id())).map(Right(_): ErrorOrEvent).toList
                val es = res.failed().asScala.map(err => (err, m(err.id()))).map(Left(_): ErrorOrEvent).toList
                cb(IO.succeed(ss ++ es))
              case ex =>
                cb(IO.fail(ex))
            }
          }
        })
      ()
    })
}
