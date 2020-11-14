package examples

import io.github.vigoo.zioaws
import io.github.vigoo.zioaws.core.config.CommonAwsConfig
import io.github.vigoo.zioaws.sqs.Sqs
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import zio.clock.Clock
import zio.sqs.producer.{ Producer, ProducerEvent }
import zio.sqs.serialization.Serializer
import zio.sqs.{ SqsStream, SqsStreamSettings, Utils }
import zio._

object TestApp extends zio.App {
  val queueName = "TestQueue"

  val client: ZLayer[Any, Throwable, Sqs] = zioaws.netty.default ++
    ZLayer.succeed(
      CommonAwsConfig(
        region = Some(Region.of("ap-northeast-2")),
        credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key")),
        endpointOverride = None,
        commonClientConfig = None
      )
    ) >>>
    zioaws.core.config.configured() >>>
    zioaws.sqs.live

  val program: ZIO[Sqs with Clock, Throwable, Unit] = for {
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
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    program.provideCustomLayer(client).exitCode
}
