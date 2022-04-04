package examples

import zio.aws.core.config.CommonAwsConfig
import zio.aws.sqs.Sqs
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import zio.Clock
import zio.sqs.producer.{ Producer, ProducerEvent }
import zio.sqs.serialization.Serializer
import zio.sqs.{ SqsStream, SqsStreamSettings, Utils }
import zio._

object TestApp extends zio.ZIOAppDefault {
  val queueName = "TestQueue"

  val client: ZLayer[Any, Throwable, Sqs] =
    zio.aws.netty.NettyHttpClient.default ++
      ZLayer.succeed(
        CommonAwsConfig(
          region = Some(Region.of("ap-northeast-2")),
          credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key")),
          endpointOverride = None,
          commonClientConfig = None
        )
      ) >>>
      zio.aws.core.config.AwsConfig.configured() >>>
      zio.aws.sqs.Sqs.live

  val program: RIO[Sqs with Clock, Unit] = for {
    _        <- Utils.createQueue(queueName)
    queueUrl <- Utils.getQueueUrl(queueName)
    producer  = Producer.make(queueUrl, Serializer.serializeString)
    _        <- ZIO.scoped {
                  producer.flatMap { p =>
                    p.produce(ProducerEvent("hello"))
                  }
                }
    _        <- SqsStream(
                  queueUrl,
                  SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = Some(3))
                ).foreach(msg => ZIO.succeed(println(msg.body)))
  } yield ()

  override def run: URIO[zio.ZEnv, ExitCode] =
    program.provideCustomLayer(client).exitCode
}
