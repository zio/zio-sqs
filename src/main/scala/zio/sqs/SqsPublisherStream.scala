package zio.sqs

import java.util.function.BiFunction

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.{ Sink, Stream, ZStream }
import zio._

import scala.jdk.CollectionConverters._

object SqsPublisherStream {

  def producer[R](
    client: SqsAsyncClient,
    queueUrl: String,
    settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
  ): ZManaged[R with Clock, Throwable, SqsProducer] = {
    val eventQueueSize = nextPower2(settings.batchSize * settings.parallelism)
    for {
      eventQueue <- Queue.bounded[SqsRequestEntry](eventQueueSize).toManaged(_.shutdown)
      failQueue  <- Queue.bounded[SqsRequestEntry](eventQueueSize).toManaged(_.shutdown)
      reqRunner  = runSendMessageBatchRequest(client, failQueue, settings.retryDelay, settings.retryMaxCount) _
      stream = (ZStream
        .fromQueue(failQueue)
        .merge(ZStream.fromQueue(eventQueue)))
        .aggregateAsyncWithin(
          Sink.collectAllN[SqsRequestEntry](settings.batchSize.toLong),
          Schedule.spaced(settings.duration)
        )
        .map(buildSendMessageBatchRequest(queueUrl, _))
        .mapMParUnordered(settings.parallelism)(req => reqRunner(req))
      _ <- stream.runDrain.toManaged_.fork
    } yield new SqsProducer {

      override def produce(e: SqsPublishEvent): Task[SqsPublishErrorOrResult] =
        for {
          done     <- Promise.make[Throwable, SqsPublishErrorOrResult]
          _        <- eventQueue.offer(SqsRequestEntry(e, done, 0))
          response <- done.await
        } yield response

      override def produceBatch(es: Iterable[SqsPublishEvent]): Task[List[SqsPublishErrorOrResult]] =
        ZIO
          .traverse(es) { e =>
            for {
              done <- Promise.make[Throwable, SqsPublishErrorOrResult]
            } yield SqsRequestEntry(e, done, 0)
          }
          .flatMap(es => eventQueue.offerAll(es) *> ZIO.collectAllPar(es.map(_.done.await)))

      override def sendStream: Stream[Throwable, SqsPublishEvent] => ZStream[Any, Throwable, SqsPublishErrorOrResult] =
        es => es.mapMParUnordered(settings.batchSize)(produce)
    }
  }

  private[sqs] def buildSendMessageBatchRequest(queueUrl: String, entries: List[SqsRequestEntry]): SqsRequest = {
    val reqEntries = entries.zipWithIndex.map {
      case (e: SqsRequestEntry, index: Int) =>
        SendMessageBatchRequestEntry
          .builder()
          .id(index.toString)
          .messageBody(e.event.body)
          .messageAttributes(e.event.attributes.asJava)
          .messageGroupId(e.event.groupId.orNull)
          .messageDeduplicationId(e.event.deduplicationId.orNull)
          .build()
    }

    val req = SendMessageBatchRequest
      .builder()
      .queueUrl(queueUrl)
      .entries(reqEntries.asJava)
      .build()

    SqsRequest(req, entries)
  }

  private[sqs] def runSendMessageBatchRequest[R](client: SqsAsyncClient, failedQueue: Queue[SqsRequestEntry], retryDelay: Duration, retryMaxCount: Int)(
    req: SqsRequest
  ): RIO[R with Clock, Unit] =
    RIO.effectAsync[R with Clock, Unit]({ cb =>
      client
        .sendMessageBatch(req.inner)
        .handleAsync[Unit](new BiFunction[SendMessageBatchResponse, Throwable, Unit] {
          override def apply(res: SendMessageBatchResponse, err: Throwable): Unit =
            err match {
              case null =>
                val m = req.entries.zipWithIndex.map(it => (it._2.toString, it._1)).toMap

                val responseParitioner = partitionResponse(m, retryMaxCount) _
                val responseMapper     = mapResponse(m) _

                val (successful, retryable, errors) = responseMapper.tupled(responseParitioner(res))

                val ret = for {
                  _ <- failedQueue.offerAll(retryable.map(it => it.copy(retryCount = it.retryCount + 1))).delay(retryDelay).fork
                  _ <- ZIO.traverse(successful)(entry => entry.done.succeed(Right(entry.event): SqsPublishErrorOrResult))
                  _ <- ZIO.traverse(errors)(entry => entry.done.succeed(Left(entry.error): SqsPublishErrorOrResult))
                } yield ()

                cb(ret)
              case ex =>
                val ret = ZIO.foreach_(req.entries.map(_.done))(_.fail(ex)) *> RIO.fail(ex)
                cb(ret)
            }
        })
      ()
    })

  private[sqs] def partitionResponse(m: Map[String, SqsRequestEntry], retryMaxCount: Int)(res: SendMessageBatchResponse) = {
    val successful = res.successful().asScala
    val failed     = res.failed().asScala

    val (recoverable, unrecoverable) = failed.partition(it => SqsPublishEventError.isRecoverable(it.code()))
    val (retryable, unretryable)     = recoverable.partition(it => m(it.id()).retryCount < retryMaxCount)

    (successful, retryable, unrecoverable ++ unretryable)
  }

  private[sqs] def mapResponse(
    m: Map[String, SqsRequestEntry]
  )(successful: Iterable[SendMessageBatchResultEntry], retryable: Iterable[BatchResultErrorEntry], errors: Iterable[BatchResultErrorEntry]) = {
    val successfulEntries = successful.map(res => m(res.id()))
    val retryableEntries  = retryable.map(res => m(res.id()))
    val errorEntries = errors.map { err =>
      val entry = m(err.id())
      SqsResponseErrorEntry(entry.done, SqsPublishEventError(err, entry.event))
    }

    (successfulEntries, retryableEntries, errorEntries)
  }

  private[sqs] def nextPower2(n: Int): Int = {
    var m: Int = n
    m -= 1
    m |= m >> 1
    m |= m >> 2
    m |= m >> 4
    m |= m >> 8
    m |= m >> 16
    m += 1
    m
  }

  private[sqs] final case class SqsRequestEntry(
    event: SqsPublishEvent,
    done: Promise[Throwable, SqsPublishErrorOrResult],
    retryCount: Int
  )

  private[sqs] final case class SqsResponseErrorEntry(
    done: Promise[Throwable, SqsPublishErrorOrResult],
    error: SqsPublishEventError
  )

  private[sqs] final case class SqsRequest(
    inner: SendMessageBatchRequest,
    entries: List[SqsRequestEntry]
  )

}
