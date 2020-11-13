package examples

object PublishExample {

  import io.github.vigoo.zioaws
  import io.github.vigoo.zioaws.sqs.Sqs
  import software.amazon.awssdk.auth.credentials._
  import software.amazon.awssdk.regions.Region
  import zio.ZLayer
  import zio.sqs._
  import zio.sqs.producer._
  import zio.sqs.serialization._
  import zio.stream._

  val client: ZLayer[Any, Throwable, Sqs] = zioaws.netty.default >>>
    zioaws.core.config.default >>>
    zioaws.sqs.customized(builder =>
      builder
        .region(Region.of("ap-northeast-2"))
        .credentialsProvider(
          StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
        )
    )
  val events                              = List("message1", "message2").map(ProducerEvent(_))
  val queueName                           = "TestQueue"
  val program                             = for {
    queueUrl    <- Utils.getQueueUrl(queueName)
    producer     = Producer.make(queueUrl, Serializer.serializeString)
    errOrResult <- producer.use(p => p.sendStream(ZStream(events: _*)).runDrain.either)
  } yield errOrResult

  program.provideCustomLayer(client)
}
