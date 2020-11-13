package examples

import io.github.vigoo.zioaws
import io.github.vigoo.zioaws.sqs.Sqs
import zio.sqs.producer.{ Producer, ProducerEvent }
import zio.sqs.serialization.Serializer
import zio.sqs.{ SqsStream, SqsStreamSettings, Utils }
import zio.{ ExitCode, UIO, URIO, ZLayer }

object TestApp extends zio.App {
  val queueName = "TestQueue"

  val client: ZLayer[Any, Throwable, Sqs] = zioaws.netty.default >>>
    zioaws.core.config.default >>>
    zioaws.sqs.live

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    (for {
      _        <- Utils.createQueue(queueName)
      queueUrl <- Utils.getQueueUrl(queueName)
      producer  = Producer.make(queueUrl, Serializer.serializeString)
      _        <- producer.use { p =>
                    p.produce(ProducerEvent("hello"))
                  }
      _        <- SqsStream(
                    queueUrl,
                    SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = Some(3))
                  ).foreach(msg => UIO(println(msg.body)))
    } yield 0).provideCustomLayer(client).exitCode
}
