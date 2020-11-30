package zio.sqs.producer

import io.github.vigoo.zioaws
import io.github.vigoo.zioaws.sqs.Sqs
import io.github.vigoo.zioaws.sqs.model._
import zio._
import zio.clock.Clock
import zio.duration.Duration
import zio.sqs.serialization.Serializer
import zio.stream.{ Stream, ZSink, ZStream, ZTransducer }

import scala.util.control.NonFatal

/**
 * Producer that can be used to publish an event of type T to SQS queue
 * An instance of producer should be instantiated before publishing.
 * {{{
 * // when publishing strings with the provided `client` to the given `queueUrl`
 * producer = Producer.make(client, queueUrl, Serializer.serializeString)
 * }}}
 * @tparam T type of the event to publish
 */
trait Producer[T] {

  /**
   * Publishes a single event and fails the task.
   * @param e event to produce.
   * @return result of the operation.
   *         Task fails if the server returns an error.
   */
  def produce(e: ProducerEvent[T]): Task[ProducerEvent[T]]

  /**
   * Publishes a batch of events.
   * @param es events to publish.
   * @return result of publishing.
   *         The returned collection contains the same items that were published.
   *         Task fails if the server returns an error for any of the provided events.
   */
  def produceBatch(es: Iterable[ProducerEvent[T]]): Task[Iterable[ProducerEvent[T]]]

  /**
   * Stream that takes events to publish and produces a stream with published events.
   * Fails if the server returns an error for any of the published events.
   * {{{
   * // ZIO version = RC17
   * producer.use { p =>
   *   p.sendStream(Stream(events: _*))
   *     .foreach(_ => ...)
   * }
   * // ZIO version > RC17
   * producer.use { p =>
   *   Stream(events: _*)
   *     .via(p.sendStream)
   *     .foreach(_ => ...)
   * }
   * }}}
   *
   * @return stream with published events.
   */
  def sendStream: Stream[Throwable, ProducerEvent[T]] => ZStream[Any, Throwable, ProducerEvent[T]]

  /**
   * Sink that can be used to publish events.
   * Fails if the server returns an error for any of the published events.
   * @return sink for publishing.
   */
  def sendSink: ZSink[Any, Throwable, Iterable[ProducerEvent[T]], Nothing, Unit]

  /**
   * Publishes a batch of events.
   * @param es events to publish.
   * @return result of publishing.
   *         The returned collection contains [[zio.sqs.producer.ErrorOrEvent]]
   *         Doesn't fail the Task if the server returns an error for any of the provided events.
   *         Instead, the resulting collection contains either the error for the given event or the published event itself.
   *         Task completes when all input events were processed (published to the server or failed with an error).
   */
  def produceBatchE(es: Iterable[ProducerEvent[T]]): Task[Iterable[ErrorOrEvent[T]]]

  /**
   * Stream that takes the events and produces a stream with the results.
   * @return stream with published events or errors [[zio.sqs.producer.ErrorOrEvent]].
   *         Task completes when all input events were processed (published to the server or failed with an error).
   */
  def sendStreamE: Stream[Throwable, ProducerEvent[T]] => ZStream[Any, Throwable, ErrorOrEvent[T]]
}

object Producer {

  /**
   * Instantiates a new producer.
   * @param queueUrl url of the queue to publish events.
   *                 A queue can be obtained using {{{Utils.getQueueUrl(client, queueName)}}}
   * @param serializer Serializer for the published event.
   *                   If the published event is a string, [[zio.sqs.serialization.Serializer]] can be used.
   * @param settings parameters used to instantiate the producer.
   * @tparam R zio environment
   * @tparam T type of the event to publish
   * @return managed producer for publishing events.
   */
  def make[R, T](
    queueUrl: String,
    serializer: Serializer[T],
    settings: ProducerSettings = ProducerSettings()
  ): ZManaged[R with Clock with Sqs, Throwable, Producer[T]] = {
    val eventQueueSize = nextPower2(settings.batchSize * settings.parallelism)
    for {
      eventQueue <- Queue.bounded[SqsRequestEntry[T]](eventQueueSize).toManaged(_.shutdown)
      failQueue  <- Queue.bounded[SqsRequestEntry[T]](eventQueueSize).toManaged(_.shutdown)
      reqRunner   = runSendMessageBatchRequest[R, T](failQueue, settings.retryDelay, settings.retryMaxCount) _
      reqBuilder  = buildSendMessageBatchRequest(queueUrl, serializer) _
      stream      = ZStream.fromQueue(failQueue)
                      .merge(ZStream.fromQueue(eventQueue))
                      .aggregateAsyncWithin(
                        ZTransducer.collectAllN[SqsRequestEntry[T]](settings.batchSize),
                        Schedule.spaced(settings.duration)
                      )
                      .map(chunks => reqBuilder(chunks.toList))
                      .mapMPar(settings.parallelism)(reqRunner) // TODO: replace all `mapMPar` in this file with `mapMParUnordered` when zio/zio#2547 is fixed
      _ <- stream.runDrain.toManaged_.fork
    } yield new DefaultProducer[T](eventQueue, settings)
  }

  /**
   * Default producer implementation
   * @param eventQueue event queue that accumulates events to publish.
   *                   When publishing, events are taken from the queue in batches and sent to SQS.
   * @param settings producer settings.
   * @tparam T type of the event to publish
   */
  private[sqs] class DefaultProducer[T](eventQueue: Queue[SqsRequestEntry[T]], settings: ProducerSettings) extends Producer[T] {
    override def produce(e: ProducerEvent[T]): Task[ProducerEvent[T]] =
      produceE(e).flatMap(e => ZIO.fromEither(e))

    override def produceBatchE(es: Iterable[ProducerEvent[T]]): Task[Iterable[ErrorOrEvent[T]]] =
      ZIO
        .foreach(es) { e =>
          for {
            done <- Promise.make[Throwable, ErrorOrEvent[T]]
          } yield SqsRequestEntry(e, done, 0)
        }
        .flatMap(es => eventQueue.offerAll(es) *> ZIO.foreachPar(es)(_.done.await))

    override def produceBatch(es: Iterable[ProducerEvent[T]]): Task[Iterable[ProducerEvent[T]]] =
      produceBatchE(es).flatMap(rs => ZIO.foreach(rs)(r => ZIO.fromEither(r)))

    override def sendStreamE: Stream[Throwable, ProducerEvent[T]] => ZStream[Any, Throwable, ErrorOrEvent[T]] =
      es => es.mapMPar(settings.batchSize)(produceE)

    override def sendStream: Stream[Throwable, ProducerEvent[T]] => ZStream[Any, Throwable, ProducerEvent[T]] =
      es => es.mapMPar(settings.batchSize)(produce)

    override def sendSink: ZSink[Any, Throwable, Iterable[ProducerEvent[T]], Nothing, Unit] =
      ZSink.drain.contramapM(es => produceBatch(es))

    private[sqs] def produceE(e: ProducerEvent[T]): Task[ErrorOrEvent[T]] =
      for {
        done     <- Promise.make[Throwable, ErrorOrEvent[T]]
        _        <- eventQueue.offer(SqsRequestEntry[T](e, done, 0))
        response <- done.await
      } yield response
  }

  /**
   * Creates a batch request to be sent to SQS
   * @param queueUrl url of the SQS queue
   * @param serializer serializer used to convert the provided event of type T to string.
   * @param entries a collection of entries that used to publish events.
   * @tparam T type of the published events. specified when the Producer is instantiated.
   * @return request to publish to SQS.
   */
  private[sqs] def buildSendMessageBatchRequest[T](queueUrl: String, serializer: Serializer[T])(entries: List[SqsRequestEntry[T]]): SqsRequest[T] = {
    val reqEntries = entries.zipWithIndex.map {
      case (e: SqsRequestEntry[T], index: Int) =>
        SendMessageBatchRequestEntry(
          id = index.toString,
          messageBody = serializer(e.event.data),
          delaySeconds = e.event.delay.map(_.getSeconds.toInt),
          messageAttributes = Some(e.event.attributes),
          messageSystemAttributes = None,
          messageDeduplicationId = e.event.deduplicationId,
          messageGroupId = e.event.groupId
        )
    }

    val req = SendMessageBatchRequest(queueUrl, reqEntries)
    SqsRequest(req, entries)
  }

  /**
   * Publishes the provided event to SQS.
   * @param failedQueue a queue to put events for retry in case of ''recoverable'' failures.
   * @param retryDelay delay to wait inserting events to the failedQueue.
   * @param retryMaxCount max allowed number of retries per event.
   * @param req batch-request to send to SQS.
   * @tparam R zio environment.
   * @tparam T type of the event to publish.
   * @return result of the operation.
   */
  private[sqs] def runSendMessageBatchRequest[R, T](failedQueue: Queue[SqsRequestEntry[T]], retryDelay: Duration, retryMaxCount: Int)(
    req: SqsRequest[T]
  ): RIO[R with Clock with Sqs, Unit] =
    zioaws.sqs
      .sendMessageBatch(req.inner)
      .mapError(_.toThrowable)
      .flatMap { res =>
        val m = req.entries.zipWithIndex.map(it => (it._2.toString, it._1)).toMap

        val responsePartitioner = partitionResponse(m, retryMaxCount) _
        val responseMapper      = mapResponse(m) _

        val (successful, retryable, errors) = responseMapper.tupled(responsePartitioner(res))

        for {
          _ <- URIO.when(retryable.nonEmpty) {
                 failedQueue
                   .offerAll(retryable.map(it => it.copy(retryCount = it.retryCount + 1)))
                   .delay(retryDelay)
                   .forkDaemon
               }
          _ <- ZIO.foreach_(successful)(entry => entry.done.succeed(Right(entry.event): ErrorOrEvent[T]))
          _ <- ZIO.foreach_(errors)(entry => entry.done.succeed(Left(entry.error): ErrorOrEvent[T]))
        } yield ()
      }
      .catchSome { case NonFatal(e) => ZIO.foreach_(req.entries.map(_.done))(_.fail(e)) }

  /**
   * Partitions the response into a collections of: successful, retryable and non-retryable events.
   * @param m map that maps request id to the request entry that was sent to the server.
   * @param retryMaxCount max retry count that can be done of one event.
   *                      If the recoverable event fails for more than or equal to `retryMaxCount` times, it is considered unrecoverable.
   * @param res response returned from SQS that should be processed.
   * @tparam T type of the published event.
   * @return tuple with successful, retryable and non-retryable events.
   */
  private[sqs] def partitionResponse[T](m: Map[String, SqsRequestEntry[T]], retryMaxCount: Int)(res: SendMessageBatchResponse.ReadOnly) = {
    val successful = res.successfulValue
    val failed     = res.failedValue

    val (recoverable, unrecoverable) = failed.partition(it => ProducerError.isRecoverable(it.codeValue))
    val (retryable, unretryable)     = recoverable.partition(it => m(it.idValue).retryCount < retryMaxCount)

    (successful, retryable, unrecoverable ++ unretryable)
  }

  /**
   * Maps successful, retryable, unrecoverable batch result entries to the internal data type used in zio-sqs.
   */
  private[sqs] def mapResponse[T](
    m: Map[String, SqsRequestEntry[T]]
  )(
    successful: Iterable[SendMessageBatchResultEntry.ReadOnly],
    retryable: Iterable[BatchResultErrorEntry.ReadOnly],
    errors: Iterable[BatchResultErrorEntry.ReadOnly]
  ) = {
    val successfulEntries = successful.map(res => m(res.idValue))
    val retryableEntries  = retryable.map(res => m(res.idValue))
    val errorEntries      = errors.map { err =>
      val entry = m(err.idValue)
      SqsResponseErrorEntry(entry.done, ProducerError(err, entry.event))
    }

    (successfulEntries, retryableEntries, errorEntries)
  }

  /**
   * Calculates the next power of 2 for the given number.
   * Used to specify the size of internal event queues.
   * When the size is a power of 2, the queues are more performant.
   */
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

  /**
   * Request entry with bookkeeping information alongside of the published event.
   * @param event event to be published
   * @param done promise that tracks publishing completion.
   * @param retryCount the number of retries that were made for this event.
   * @tparam T type of the event to publish.
   */
  private[sqs] final case class SqsRequestEntry[T](
    event: ProducerEvent[T],
    done: Promise[Throwable, ErrorOrEvent[T]],
    retryCount: Int
  )

  /**
   * Response entry with bookkeeping information.
   * @param done promise that tracks publishing completion.
   * @param error error that occurred during publishing.
   * @tparam T type of the event to publish.
   */
  private[sqs] final case class SqsResponseErrorEntry[T](
    done: Promise[Throwable, ErrorOrEvent[T]],
    error: ProducerError[T]
  )

  /**
   * Request that wraps internal SQS request and the corresponding entries to publish.
   */
  private[sqs] final case class SqsRequest[T](
    inner: SendMessageBatchRequest,
    entries: List[SqsRequestEntry[T]]
  )

}
