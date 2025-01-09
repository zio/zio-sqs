package zio.sqs

import zio.aws.sqs.Sqs
import zio.aws.sqs.model.Message
import zio._
import zio.sqs.ZioSqsMockServer._
import zio.sqs.producer.{ Producer, ProducerEvent }
import zio.sqs.serialization.Serializer
import zio.test.Assertion._
import zio.test._
import zio.test.{ Live, TestEnvironment }
import testing._
import zio.sqs.SqsStream.consumeChunkAtLeastOnce

object ZioSqsSpec extends ZIOSpecDefault {

  override def spec =
    suite("ZioSqsSpec")(
      test("send messages") {
        val settings: SqsStreamSettings = SqsStreamSettings.default.withStopWhenQueueEmpty(true)

        for {
          messages <- gen.runHead.someOrFailException
          list     <- ZIO.scoped(serverResource *> (sendAndGet(messages, settings)))

        } yield assert(list.map(_.body.getOrElse("")))(equalTo(messages))
      },
      test("delete messages manually") {
        val settings: SqsStreamSettings =
          SqsStreamSettings.default
            .withStopWhenQueueEmpty(true)
            .withAutoDelete(false)
            .withWaitTimeSeconds(1)

        for {
          messages <- gen.runHead.someOrFailException
          list     <- ZIO.scoped {
                        serverResource *> {
                          for {
                            messageFromQueue <- sendAndGet(messages, settings)
                            list             <- deleteAndGet(messageFromQueue, settings)
                          } yield list
                        }
                      }

        } yield assert(list)(isEmpty)
      },
      test("delete messages automatically") {
        val settings: SqsStreamSettings = SqsStreamSettings.default.withStopWhenQueueEmpty(true).withWaitTimeSeconds(1)

        for {
          messages <- gen.runHead.someOrFailException
          list     <- ZIO.scoped {
                        serverResource *> {
                          for {
                            _    <- sendAndGet(messages, settings)
                            list <- get(settings)
                          } yield list
                        }
                      }
        } yield assert(list)(isEmpty)
      },
      test("deleteMessageBatchSink deletes messages from the queue") {
        val settings: SqsStreamSettings = SqsStreamSettings.default.withStopWhenQueueEmpty(true).withWaitTimeSeconds(1)
        val program                     =
          for {
            _                 <- serverResource
            _                 <- Utils.createQueue(queueName)
            queueUrl          <- Utils.getQueueUrl(queueName)
            messages          <- gen.runHead.someOrFailException
            _                 <- ZIO.scoped {
                                   Producer.make(queueUrl, Serializer.serializeString)
                                     .flatMap(_.produceBatch(messages.map(ProducerEvent(_))))
                                 }
            messageQueue      <- Queue.unbounded[Message.ReadOnly]
            _                 <- SqsStream(queueUrl, settings)
                                   .mapChunksZIO(chunk => messageQueue.offerAll(chunk).as(chunk))
                                   .run(SqsStream.deleteMessageBatchSink(queueUrl))
            list              <- SqsStream(queueUrl, settings).runCollect
            messagesFromQueue <- messageQueue.takeAll
          } yield assert(list)(isEmpty) && assert(messagesFromQueue.map(_.body.getOrElse("")))(hasSameElements(messages))

        ZIO.scoped(program)
      } @@ TestAspect.withLiveClock,
      test("consumeChunkAtLeastOnce will not delete messages if there is an error encountered when processing") {
        val settings = SqsStreamSettings.default
          .withStopWhenQueueEmpty(true)
          .withWaitTimeSeconds(1)
          .withVisibilityTimeout(2)
          .withMaxNumberOfMessages(10000)

        val program =
          for {
            _              <- serverResource
            messages       <- gen.runHead.someOrFailException
            _              <- Utils.createQueue(queueName)
            queueUrl       <- Utils.getQueueUrl(queueName)
            producer        = Producer.make(queueUrl, Serializer.serializeString)
            _              <- ZIO.scoped(producer.flatMap(p => ZIO.foreach(messages)(msg => p.produce(ProducerEvent(msg)))))
            messagePromise <- Promise.make[Nothing, Chunk[Message.ReadOnly]]
            _              <- consumeChunkAtLeastOnce(queueUrl, settings, SqsMessageLifetimeExtensionSettings.default) { _ =>
                                ZIO.fail(new RuntimeException("Purposefully KABOOM"))
                              }.exit
            // messages won't reappear immediately due to the visibility timeout so we repeatedly poll until they are back
            _              <- consumeChunkAtLeastOnce(queueUrl, settings, SqsMessageLifetimeExtensionSettings.default) { msgs =>
                                messagePromise.succeed(msgs).unit
                              }.repeatWhileZIO(_ => messagePromise.poll.map(_.isEmpty))
            actual         <- messagePromise.await
            actualMessages  = actual.map(_.body.getOrElse(""))
          } yield assert(actualMessages)(hasSameElements(messages))
        ZIO.scoped(program)
      } @@ TestAspect.withLiveClock,
      test("consumeChunkAtLeastOnce will automatically extend message lifetime and delete messages after successful processing") {
        val settings = SqsStreamSettings.default
          .withStopWhenQueueEmpty(true)
          .withWaitTimeSeconds(1)
          .withVisibilityTimeout(2)
          .withMaxNumberOfMessages(10000)

        val program = for {
          _              <- serverResource
          messages       <- gen.runHead.someOrFailException
          _              <- Utils.createQueue(queueName)
          queueUrl       <- Utils.getQueueUrl(queueName)
          producer        = Producer.make(queueUrl, Serializer.serializeString)
          _              <- ZIO.scoped(producer.flatMap(p => ZIO.foreach(messages)(msg => p.produce(ProducerEvent(msg)))))
          messagePromise <- Promise.make[Nothing, Chunk[Message.ReadOnly]]
          _              <- consumeChunkAtLeastOnce(queueUrl, settings, SqsMessageLifetimeExtensionSettings.default) { msgs =>
                              ZIO.sleep(2500.millis) *> messagePromise.succeed(msgs).unit
                            }
          _              <- consumeChunkAtLeastOnce(queueUrl, settings, SqsMessageLifetimeExtensionSettings.default) { msgs =>
                              ZIO.fail(new RuntimeException(s"Should not be called because the previous call should have processed all messages ($msgs)"))
                            }
          actual         <- messagePromise.await
        } yield assert(actual.map(_.body.getOrElse("")))(equalTo(messages))

        ZIO.scoped(program)
      } @@ TestAspect.withLiveClock
    ).provideSomeLayerShared[TestEnvironment]((zio.aws.netty.NettyHttpClient.default >>> zio.aws.core.config.AwsConfig.default >>> clientResource).orDie)

  override def aspects: Chunk[TestAspect[Nothing, TestEnvironment, Nothing, Any]] =
    Chunk(TestAspect.executionStrategy(ExecutionStrategy.Sequential))

  private val queueName = "TestQueue"

  val gen: Gen[Sized, Chunk[String]] = chunkOfStringsN(10)

  def sendAndGet(messages: Seq[String], settings: SqsStreamSettings): ZIO[Live with Sqs, Throwable, Chunk[Message.ReadOnly]] =
    for {
      _                 <- withFastClock.fork
      _                 <- Utils.createQueue(queueName)
      queueUrl          <- Utils.getQueueUrl(queueName)
      producer           = Producer.make(queueUrl, Serializer.serializeString)
      _                 <- ZIO.scoped(producer.flatMap(p => ZIO.foreach(messages)(it => p.produce(ProducerEvent(it)))))
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
