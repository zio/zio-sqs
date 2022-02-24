package zio.sqs

import zio.aws.sqs.Sqs
import zio.aws.sqs.model.Message
import zio._
import zio.Clock
import zio.durationInt
import zio.Random
import zio.sqs.ZioSqsMockServer._
import zio.sqs.producer.{ Producer, ProducerEvent }
import zio.sqs.serialization.Serializer
import zio.test.Assertion._
import zio.test._
import zio.test.{ Live, TestClock, TestEnvironment }

object ZioSqsSpec extends DefaultRunnableSpec {

  def spec: ZSpec[TestEnvironment, Any] =
    suite("ZioSqsSpec")(
      test("send messages") {
        val settings: SqsStreamSettings = SqsStreamSettings(stopWhenQueueEmpty = true)

        for {
          messages <- gen.sample.map(_.map(_.value)).runHead.map(_.flatten).someOrFailException
          list     <- serverResource.use(_ => sendAndGet(messages, settings))

        } yield assert(list.map(_.body.getOrElse("")))(equalTo(messages))
      },
      test("delete messages manually") {
        val settings: SqsStreamSettings =
          SqsStreamSettings(stopWhenQueueEmpty = true, autoDelete = false, waitTimeSeconds = Some(1))

        for {
          messages <- gen.sample.map(_.map(_.value)).runHead.map(_.flatten).someOrFailException
          list     <- serverResource.use { _ =>
                        for {
                          messageFromQueue <- sendAndGet(messages, settings)
                          list             <- deleteAndGet(messageFromQueue, settings)
                        } yield list
                      }

        } yield assert(list)(isEmpty)
      },
      test("delete messages automatically") {
        val settings: SqsStreamSettings = SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = Some(1))

        for {
          messages <- gen.sample.map(_.map(_.value)).runHead.map(_.flatten).someOrFailException
          list     <- serverResource.use { _ =>
                        for {
                          _    <- sendAndGet(messages, settings)
                          list <- get(settings)
                        } yield list
                      }
        } yield assert(list)(isEmpty)
      }
    ).provideCustomLayerShared((zio.aws.netty.NettyHttpClient.default >>> zio.aws.core.config.AwsConfig.default >>> clientResource).orDie)

  override def aspects: List[TestAspect[Nothing, TestEnvironment, Nothing, Any]] =
    List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))

  private val queueName = "TestQueue"

  val gen: Gen[Random with Sized, Chunk[String]] = Util.chunkOfStringsN(10)

  def withFastClock: ZIO[TestClock with Live, Nothing, Long] =
    Live.withLive(TestClock.adjust(1.seconds))(_.repeat[ZEnv, Long](Schedule.spaced(10.millis)))

  def sendAndGet(messages: Seq[String], settings: SqsStreamSettings): ZIO[TestClock with Live with Clock with Sqs, Throwable, Chunk[Message.ReadOnly]] =
    for {
      _                 <- withFastClock.fork
      _                 <- Utils.createQueue(queueName)
      queueUrl          <- Utils.getQueueUrl(queueName)
      producer           = Producer.make(queueUrl, Serializer.serializeString)
      _                 <- producer.use(p => ZIO.foreach(messages)(it => p.produce(ProducerEvent(it))))
      messagesFromQueue <- SqsStream(queueUrl, settings).runCollect
    } yield messagesFromQueue

  def deleteAndGet(messages: Seq[Message.ReadOnly], settings: SqsStreamSettings): ZIO[Sqs, Throwable, Chunk[Message.ReadOnly]] =
    for {
      queueUrl <- Utils.getQueueUrl(queueName)
      _        <- ZIO.foreachDiscard(messages)(SqsStream.deleteMessage(queueUrl, _))
      list     <- SqsStream(queueUrl, settings).runCollect
    } yield list

  def get(settings: SqsStreamSettings): ZIO[Sqs, Throwable, Chunk[Message.ReadOnly]] =
    for {
      queueUrl <- Utils.getQueueUrl(queueName)
      list     <- SqsStream(queueUrl, settings).runCollect
    } yield list
}
