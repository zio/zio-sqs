package zio.sqs

import zio.Task
import zio.clock.Clock
import zio.stream.{ Stream, ZSink, ZStream }

trait SqsProducer[T] {

  // allow to fail the Task on failures
  // 1. rename methods to the ones with E suffix
  // 2. check that tests are running
  // 3. create new methods without suffix that use methods with suffix and throw an error
  // 4. add tests for the new methods

  def produce(e: SqsPublishEvent[T]): Task[SqsPublishErrorOrResult[T]]

  def produceBatch(es: Iterable[SqsPublishEvent[T]]): Task[List[SqsPublishErrorOrResult[T]]]

  def sendStream: Stream[Throwable, SqsPublishEvent[T]] => ZStream[Clock, Throwable, SqsPublishErrorOrResult[T]]

  def sendSink: ZSink[Any, Throwable, Nothing, Iterable[SqsPublishEvent[T]], Unit] =
    ZSink.drain.contramapM(es => produceBatch(es))
}
