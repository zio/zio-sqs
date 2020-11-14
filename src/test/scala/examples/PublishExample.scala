package examples

import io.github.vigoo.zioaws
import io.github.vigoo.zioaws.sqs.Sqs
import zio.clock.Clock
import zio.sqs._
import zio.sqs.producer._
import zio.sqs.serialization._
import zio.stream._
import zio.{ ExitCode, RIO, URIO, ZLayer }

object PublishExample extends zio.App {

  val client: ZLayer[Any, Throwable, Sqs] = zioaws.netty.default >>>
    zioaws.core.config.default >>>
    zioaws.sqs.live

  val events                                                = List("message1", "message2").map(ProducerEvent(_))
  val queueName                                             = "TestQueue"
  val program: RIO[Clock with Sqs, Either[Throwable, Unit]] = for {
    queueUrl    <- Utils.getQueueUrl(queueName)
    producer     = Producer.make(queueUrl, Serializer.serializeString)
    errOrResult <- producer.use(p => p.sendStream(ZStream(events: _*)).runDrain.either)
  } yield errOrResult

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    program.provideCustomLayer(client).exitCode
}
