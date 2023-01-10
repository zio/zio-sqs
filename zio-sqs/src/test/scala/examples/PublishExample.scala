package examples

import zio.aws.sqs.Sqs
import zio.sqs._
import zio.sqs.producer._
import zio.sqs.serialization._
import zio.stream._
import zio.{ ExitCode, RIO, UIO, ZIO, ZLayer }

object PublishExample extends zio.ZIOAppDefault {

  val client: ZLayer[Any, Throwable, Sqs] =
    zio.aws.netty.NettyHttpClient.default >>>
      zio.aws.core.config.AwsConfig.default >>>
      zio.aws.sqs.Sqs.live

  val events                                     = List("message1", "message2").map(ProducerEvent(_))
  val queueName                                  = "TestQueue"
  val program: RIO[Sqs, Either[Throwable, Unit]] = for {
    queueUrl    <- Utils.getQueueUrl(queueName)
    producer     = Producer.make(queueUrl, Serializer.serializeString)
    errOrResult <- ZIO.scoped(producer.flatMap(p => p.sendStream(ZStream(events: _*)).runDrain.either))
  } yield errOrResult

  override def run: UIO[ExitCode] =
    program.provide(client).exitCode
}
