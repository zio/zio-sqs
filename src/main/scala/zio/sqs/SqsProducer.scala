package zio.sqs

import zio.Task
import zio.clock.Clock
import zio.stream.{ Stream, ZSink, ZStream }

trait SqsProducer[T] {

  def produceE(e: SqsPublishEvent[T]): Task[SqsPublishErrorOrResult[T]]

  def produce(e: SqsPublishEvent[T]): Task[SqsPublishEvent[T]]

  def produceBatchE(es: Iterable[SqsPublishEvent[T]]): Task[List[SqsPublishErrorOrResult[T]]]

  def produceBatch(es: Iterable[SqsPublishEvent[T]]): Task[List[SqsPublishEvent[T]]]

  def sendStreamE: Stream[Throwable, SqsPublishEvent[T]] => ZStream[Clock, Throwable, SqsPublishErrorOrResult[T]]

  def sendStream: Stream[Throwable, SqsPublishEvent[T]] => ZStream[Clock, Throwable, SqsPublishEvent[T]]

  def sendSink: ZSink[Any, Throwable, Nothing, Iterable[SqsPublishEvent[T]], Unit]
}
