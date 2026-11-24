package zio.sqs.producer

import zio.Duration
import zio._
import zio.aws.sqs.Sqs
import zio.aws.sqs.model._
import zio.sqs.serialization.Serializer
import zio.stream.Stream
import zio.stream.ZSink
import zio.stream.ZStream

import scala.util.control.NonFatal

/**
 * Publishes events of type `T` to an SQS queue.
 * Create an instance with [[Producer.make]] inside a `Scope`, then publish with the methods below.
 * {{{
 * import zio._
 * import zio.aws.sqs.Sqs
 * import zio.sqs.producer._
 * import zio.sqs.serialization.Serializer
 *
 * def publish(queueUrl: String): ZIO[Sqs, Throwable, Unit] =
 *   ZIO.scoped {
 *     for {
 *       producer <- Producer.make(queueUrl, Serializer.serializeString)
 *       _        <- producer.produce(ProducerEvent("hello"))
 *     } yield ()
 *   }
 * }}}
 * @tparam T type of the event to publish
 */
trait Producer[T] {

  /**
   * Publishes one event.
   * @param e event to produce.
   * @return the published event.
   *         Fails with a [[ProducerError]] when SQS reports an error for the event that is not retried:
   *         either the error is not recoverable (see [[ProducerError.isRecoverable]]), or the event has used up its `retryMaxCount` retries.
   *         Fails with the request's error when the batch request to SQS fails as a whole.
   */
  def produce(e: ProducerEvent[T]): Task[ProducerEvent[T]]

  /**
   * Publishes a batch of events.
   * @param es events to publish.
   * @return the published events, in the order they were given.
   *         Fails if any of the events fails to publish.
   */
  def produceBatch(es: Iterable[ProducerEvent[T]]): Task[Iterable[ProducerEvent[T]]]

  /**
   * A pipeline that publishes each event of the input stream and emits the published event.
   * Fails if an event fails to publish.
   * {{{
   * import zio._
   * import zio.stream._
   * import zio.sqs.producer._
   *
   * def publishAll(p: Producer[String], events: List[ProducerEvent[String]]): Task[Unit] =
   *   p.sendStream(ZStream.fromIterable(events)).runDrain
   * }}}
   * @return a pipeline from the events to the published events.
   */
  def sendStream: Stream[Throwable, ProducerEvent[T]] => ZStream[Any, Throwable, ProducerEvent[T]]

  /**
   * A sink that publishes each incoming collection of events with [[produceBatch]].
   * Fails if an event fails to publish.
   * @return a sink for publishing.
   */
  def sendSink: ZSink[Any, Throwable, Iterable[ProducerEvent[T]], Nothing, Unit]

  /**
   * Publishes a batch of events and reports each result separately.
   * An error that SQS reports for an event does not fail the returned task. Each such event is returned as a [[ProducerError]] on the left.
   * Completes once every input event has either been published or has failed with an error that SQS reports for it.
   * Fails when a batch request to SQS fails as a whole, for example on a network error.
   * @param es events to publish.
   * @return one result per input event: the published event on the right, or a [[ProducerError]] on the left.
   */
  def produceBatchE(es: Iterable[ProducerEvent[T]]): Task[Iterable[ErrorOrEvent[T]]]

  /**
   * A pipeline like [[sendStream]] that emits an error that SQS reports for an event as a [[ProducerError]] on the left, instead of failing.
   * Each input event produces one [[ErrorOrEvent]]: the published event on the right, or a [[ProducerError]] on the left.
   * The stream still fails when a batch request to SQS fails as a whole.
   * @return a pipeline from the events to their results.
   */
  def sendStreamE: Stream[Throwable, ProducerEvent[T]] => ZStream[Any, Throwable, ErrorOrEvent[T]]
}

/**
 * Factory for [[Producer]].
 */
object Producer {

  /**
   * Instantiates a new producer.
   * @param queueUrl url of the queue to publish events.
   *                 A queue URL can be obtained with [[zio.sqs.Utils.getQueueUrl]].
   * @param serializer Serializer for the published event.
   *                   If the published event is a string, [[zio.sqs.serialization.Serializer.serializeString]] can be used.
   * @param settings parameters used to instantiate the producer.
   * @tparam T type of the event to publish
   * @return producer for publishing events. The producer's main background fiber is tied to the enclosing `Scope`. Retries run in daemon fibers.
   */
  def make[T](
    queueUrl: String,
    serializer: Serializer[T],
    settings: ProducerSettings = ProducerSettings()
  ): ZIO[Sqs & Scope, Throwable, Producer[T]] = {
    val eventQueueSize = nextPower2(settings.batchSize * settings.parallelism)
    for {
      eventQueue <- ZIO.acquireRelease(Queue.bounded[SqsRequestEntry[T]](eventQueueSize))(_.shutdown)
      failQueue  <- ZIO.acquireRelease(Queue.bounded[SqsRequestEntry[T]](eventQueueSize))(_.shutdown)
      reqRunner   = runSendMessageBatchRequest[T](failQueue, settings.retryDelay, settings.retryMaxCount) _
      reqBuilder  = buildSendMessageBatchRequest(queueUrl, serializer) _
      stream      = ZStream.fromQueue(failQueue)
                      .merge(ZStream.fromQueue(eventQueue))
                      .aggregateAsyncWithin(
                        ZSink.collectAllN[SqsRequestEntry[T]](settings.batchSize),
                        Schedule.spaced(settings.duration)
                      )
                      .map(chunks => reqBuilder(chunks.toList))
                      .mapZIOParUnordered(settings.parallelism)(reqRunner)
      _          <- stream.runDrain.forkScoped
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
      es => es.mapZIOPar(settings.batchSize)(produceE)

    override def sendStream: Stream[Throwable, ProducerEvent[T]] => ZStream[Any, Throwable, ProducerEvent[T]] =
      es => es.mapZIOPar(settings.batchSize)(produce)

    override def sendSink: ZSink[Any, Throwable, Iterable[ProducerEvent[T]], Nothing, Unit] =
      ZSink.drain.contramapZIO(es => produceBatch(es))

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
          delaySeconds = e.event.delay.map(d => d.getSeconds.toInt),
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
   * Sends one batch request to SQS, then completes the promise of each entry, queues recoverable failures for retry,
   * or fails every promise if the request itself fails.
   * @param failedQueue a queue to put events for retry in case of ''recoverable'' failures.
   * @param retryDelay delay to wait inserting events to the failedQueue.
   * @param retryMaxCount max allowed number of retries per event.
   * @param req batch-request to send to SQS.
   * @tparam T type of the event to publish.
   * @return result of the operation.
   */
  private[sqs] def runSendMessageBatchRequest[T](failedQueue: Queue[SqsRequestEntry[T]], retryDelay: Duration, retryMaxCount: Int)(
    req: SqsRequest[T]
  ): RIO[Sqs, Unit] =
    zio.aws.sqs.Sqs
      .sendMessageBatch(req.inner)
      .mapError(_.toThrowable)
      .flatMap { res =>
        val m = req.entries.zipWithIndex.map(it => (it._2.toString, it._1)).toMap

        val responsePartitioner = partitionResponse(m, retryMaxCount) _
        val responseMapper      = mapResponse(m) _

        val (successful, retryable, errors) = responseMapper.tupled(responsePartitioner(res))

        for {
          _ <- ZIO.when(retryable.nonEmpty) {
                 failedQueue
                   .offerAll(retryable.map(it => it.copy(retryCount = it.retryCount + 1)))
                   .delay(retryDelay)
                   .forkDaemon
               }
          _ <- ZIO.foreachDiscard(successful)(entry => entry.done.succeed(Right(entry.event): ErrorOrEvent[T]))
          _ <- ZIO.foreachDiscard(errors)(entry => entry.done.succeed(Left(entry.error): ErrorOrEvent[T]))
        } yield ()
      }
      .catchSome { case NonFatal(e) => ZIO.foreachDiscard(req.entries.map(_.done))(_.fail(e)) }

  /**
   * Partitions the response into a collections of: successful, retryable and non-retryable events.
   * @param m map that maps request id to the request entry that was sent to the server.
   * @param retryMaxCount max retry count that can be done of one event.
   *                      Once an event has been retried `retryMaxCount` times, its next recoverable failure is treated as unrecoverable.
   * @param res response returned from SQS that should be processed.
   * @tparam T type of the published event.
   * @return tuple with successful, retryable and non-retryable events.
   */
  private[sqs] def partitionResponse[T](m: Map[String, SqsRequestEntry[T]], retryMaxCount: Int)(res: SendMessageBatchResponse.ReadOnly) = {
    val successful = res.successful
    val failed     = res.failed

    val (recoverable, unrecoverable) = failed.partition(it => ProducerError.isRecoverable(it.code))
    val (retryable, unretryable)     = recoverable.partition(it => m(it.id).retryCount < retryMaxCount)

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
    val successfulEntries = successful.map(res => m(res.id))
    val retryableEntries  = retryable.map(res => m(res.id))
    val errorEntries      = errors.map { err =>
      val entry = m(err.id)
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
