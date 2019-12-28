package zio.sqs

import zio.Task
import zio.clock.Clock
import zio.stream.{ Stream, ZSink, ZStream }

trait SqsProducer[T] {

  def produce(e: SqsPublishEvent[T]): Task[SqsPublishErrorOrResult[T]]

  def produceBatch(es: Iterable[SqsPublishEvent[T]]): Task[List[SqsPublishErrorOrResult[T]]]

  def sendStream: Stream[Throwable, SqsPublishEvent[T]] => ZStream[Clock, Throwable, SqsPublishErrorOrResult[T]]

  def sendSink: ZSink[Any, Throwable, Nothing, Iterable[SqsPublishEvent[T]], Unit] =
    ZSink.drain.contramapM(es => produceBatch(es))
}
