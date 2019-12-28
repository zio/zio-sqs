package zio.sqs

import java.util.function.BiFunction

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._
import zio._
import zio.clock.Clock
import zio.duration.Duration
import zio.sqs.serialization.Serializer
import zio.stream.{ Sink, Stream, ZStream }

import scala.jdk.CollectionConverters._

object SqsPublisherStream {

  def producer[R, T](
    client: SqsAsyncClient,
    queueUrl: String,
    serializer: Serializer[T],
    settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
  ): ZManaged[R with Clock, Throwable, SqsProducer[T]] = {
    val eventQueueSize = nextPower2(settings.batchSize * settings.parallelism)
    for {
      eventQueue <- Queue.bounded[SqsRequestEntry[T]](eventQueueSize).toManaged(_.shutdown)
      failQueue  <- Queue.bounded[SqsRequestEntry[T]](eventQueueSize).toManaged(_.shutdown)
      reqRunner  = runSendMessageBatchRequest[R, T](client, failQueue, settings.retryDelay, settings.retryMaxCount) _
      reqBuilder = buildSendMessageBatchRequest(queueUrl, serializer) _
      stream = (ZStream
        .fromQueue(failQueue)
        .merge(ZStream.fromQueue(eventQueue)))
        .aggregateAsyncWithin(
          Sink.collectAllN[SqsRequestEntry[T]](settings.batchSize.toLong),
          Schedule.spaced(settings.duration)
        )
        .map(reqBuilder)
        .mapMParUnordered(settings.parallelism)(reqRunner)
      _ <- stream.runDrain.toManaged_.fork
    } yield new SqsProducer[T] {

      override def produce(e: SqsPublishEvent[T]): Task[SqsPublishErrorOrResult[T]] =
        for {
          done     <- Promise.make[Throwable, SqsPublishErrorOrResult[T]]
          _        <- eventQueue.offer(SqsRequestEntry[T](e, done, 0))
          response <- done.await
        } yield response

      override def produceBatch(es: Iterable[SqsPublishEvent[T]]): Task[List[SqsPublishErrorOrResult[T]]] =
        ZIO
          .traverse(es) { e =>
            for {
              done <- Promise.make[Throwable, SqsPublishErrorOrResult[T]]
            } yield SqsRequestEntry(e, done, 0)
          }
          .flatMap(es => eventQueue.offerAll(es) *> ZIO.collectAllPar(es.map(_.done.await)))

      override def sendStream: Stream[Throwable, SqsPublishEvent[T]] => ZStream[Any, Throwable, SqsPublishErrorOrResult[T]] =
        es => es.mapMParUnordered(settings.batchSize)(produce)
    }
  }

  private[sqs] def buildSendMessageBatchRequest[T](queueUrl: String, serializer: Serializer[T])(entries: List[SqsRequestEntry[T]]): SqsRequest[T] = {
    val reqEntries = entries.zipWithIndex.map {
      case (e: SqsRequestEntry[T], index: Int) =>
        SendMessageBatchRequestEntry
          .builder()
          .id(index.toString)
          .messageBody(serializer(e.event.data))
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

  private[sqs] def runSendMessageBatchRequest[R, T](client: SqsAsyncClient, failedQueue: Queue[SqsRequestEntry[T]], retryDelay: Duration, retryMaxCount: Int)(
    req: SqsRequest[T]
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
                  _ <- ZIO.traverse(successful)(entry => entry.done.succeed(Right(entry.event): SqsPublishErrorOrResult[T]))
                  _ <- ZIO.traverse(errors)(entry => entry.done.succeed(Left(entry.error): SqsPublishErrorOrResult[T]))
                } yield ()

                cb(ret)
              case ex =>
                val ret = ZIO.foreach_(req.entries.map(_.done))(_.fail(ex)) *> RIO.fail(ex)
                cb(ret)
            }
        })
      ()
    })

  private[sqs] def partitionResponse[T](m: Map[String, SqsRequestEntry[T]], retryMaxCount: Int)(res: SendMessageBatchResponse) = {
    val successful = res.successful().asScala
    val failed     = res.failed().asScala

    val (recoverable, unrecoverable) = failed.partition(it => SqsPublishEventError.isRecoverable(it.code()))
    val (retryable, unretryable)     = recoverable.partition(it => m(it.id()).retryCount < retryMaxCount)

    (successful, retryable, unrecoverable ++ unretryable)
  }

  private[sqs] def mapResponse[T](
    m: Map[String, SqsRequestEntry[T]]
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

  private[sqs] final case class SqsRequestEntry[T](
    event: SqsPublishEvent[T],
    done: Promise[Throwable, SqsPublishErrorOrResult[T]],
    retryCount: Int
  )

  private[sqs] final case class SqsResponseErrorEntry[T](
    done: Promise[Throwable, SqsPublishErrorOrResult[T]],
    error: SqsPublishEventError[T]
  )

  private[sqs] final case class SqsRequest[T](
    inner: SendMessageBatchRequest,
    entries: List[SqsRequestEntry[T]]
  )

}
