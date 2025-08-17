package examples

import zio._
import zio.sqs.producer._
import zio.sqs.serialization.Serializer
import zio.aws.netty.NettyHttpClient
import zio.aws.sqs.Sqs
import zio.aws.core.config._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.auth.credentials._
import zio.sqs._
import zio.aws.sqs.model.Message

object AtLeastOnceExample extends ZIOAppDefault {
  val queueUrl = "https://sqs.us-east-1.amazonaws.com/00000/calq.fifo"

  val producerLayer: RLayer[Sqs, Producer[String]] =
    ZLayer.scoped(
      Producer.make(
        queueUrl = queueUrl,
        serializer = Serializer.serializeString,
        settings = ProducerSettings(parallelism = 1)
      )
    )

  val producerExample =
    ZIO.serviceWithZIO[Producer[String]] { producer =>
      producer.produceBatch(
        (0 to 200).map(i =>
          ProducerEvent(
            data = s"Message $i",
            attributes = Map.empty,
            groupId = Some(
              if (i % 2 == 0) "even"
              else "odd"
            ),
            deduplicationId = None
          )
        )
      )
    }

  val consumerExample =
    SqsStream.consumeChunkAtLeastOnce(
      queueUrl = queueUrl,
      settings = SqsStreamSettings.default
        .withMaxNumberOfMessages(10)
        .withVisibilityTimeout(5)
        .withWaitTimeSeconds(20),
      extensionSettings = SqsMessageLifetimeExtensionSettings.default,
      consumerParallelism = 10
    ) { (messages: Chunk[Message.ReadOnly]) =>
      ZIO.debug(messages.map(_.body.getOrElse(""))) *> ZIO.sleep(14.seconds)
    }

  val consumerStreamExample =
    SqsStream(
      queueUrl = queueUrl,
      settings = SqsStreamSettings.default.withAutoDelete(false)
    ).tap(message => ZIO.debug(message.body.getOrElse("")))
      .run(SqsStream.deleteMessageBatchSink(queueUrl))

  override val run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] =
    (producerExample *> consumerExample)
      .provide(
        producerLayer,
        Sqs.live,
        NettyHttpClient.default,
        AwsConfig.configured(),
        ZLayer.succeed(
          CommonAwsConfig(
            region = Option(Region.US_EAST_1),
            credentialsProvider = StaticCredentialsProvider.create(
              AwsBasicCredentials.create("key", "secret")
            ),
            endpointOverride = None,
            commonClientConfig = None
          )
        )
      )
}
