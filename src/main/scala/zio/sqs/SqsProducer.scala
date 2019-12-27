package zio.sqs

import zio.Task
import zio.clock.Clock
import zio.stream.{ Stream, ZSink, ZStream }

trait SqsProducer {

  def produce(e: SqsPublishEvent): Task[SqsPublishErrorOrResult]

  def produceBatch(es: Iterable[SqsPublishEvent]): Task[List[SqsPublishErrorOrResult]]

  def sendStream: Stream[Throwable, SqsPublishEvent] => ZStream[Clock, Throwable, SqsPublishErrorOrResult]

  def sendSink: ZSink[Any, Throwable, Nothing, Iterable[SqsPublishEvent], Unit] =
    ZSink.drain.contramapM(es => produceBatch(es))
}
