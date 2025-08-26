package examples

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import zio._
import zio.aws.core.config.CommonAwsConfig
import zio.aws.sqs.Sqs
import zio.sqs.SqsStream
import zio.sqs.SqsStreamSettings
import zio.sqs.Utils
import zio.sqs.producer.Producer
import zio.sqs.producer.ProducerEvent
import zio.sqs.serialization.Serializer

object AtMostOnceExample extends zio.ZIOAppDefault {
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

  val program: RIO[Sqs, Unit] = for {
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
                  SqsStreamSettings.default.withStopWhenQueueEmpty(true).withWaitTimeSeconds(3).withAutoDelete(true)
                ).foreach(msg => ZIO.succeed(println(msg.body)))
  } yield ()

  override def run: Task[Unit] =
    program.provide(client)
}
