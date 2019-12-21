package zio.sqs

import software.amazon.awssdk.services.sqs.model.Message
import zio._
import zio.random.Random
import zio.sqs.ZioSqsMockServer._
import zio.sqs.ZioSqsSpecUtil._
import zio.stream.Sink
import zio.test.Assertion._
import zio.test._

object ZioSqsSpec
    extends DefaultRunnableSpec(
      suite("ZioSqsSpec")(
        testM("send messages") {
          val settings: SqsStreamSettings = SqsStreamSettings(stopWhenQueueEmpty = true)

          for {
            messages <- gen.sample.map(_.value).run(Sink.await[List[String]])
            server   <- serverResource
            list <- server.use { _ =>
                     sendAndGet(messages, settings)
                   }

          } yield {
            assert(list.map(_.body()), equalTo(messages))
          }
        },
        testM("delete messages manually") {
          val settings: SqsStreamSettings =
            SqsStreamSettings(stopWhenQueueEmpty = true, autoDelete = false, waitTimeSeconds = 1)

          for {
            messages <- gen.sample.map(_.value).run(Sink.await[List[String]])
            server   <- serverResource
            list <- server.use { _ =>
                     for {
                       messageFromQueue <- sendAndGet(messages, settings)
                       list             <- deleteAndGet(messageFromQueue, settings)
                     } yield { list }
                   }

          } yield {
            assert(list, isEmpty)
          }
        },
        testM("delete messages automatically") {
          val settings: SqsStreamSettings =
            SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 1)

          for {
            messages <- gen.sample.map(_.value).run(Sink.await[List[String]])
            server   <- serverResource
            list <- server.use { _ =>
                     for {
                       _    <- sendAndGet(messages, settings)
                       list <- get(settings)
                     } yield { list }
                   }

          } yield {
            assert(list, isEmpty)
          }
        }
      ),
      List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
    )

object ZioSqsSpecUtil {
  private val queueName = "TestQueue"

  val gen: Gen[Random with Sized, List[String]] = Util.stringGen(10)

  def sendAndGet(messages: Seq[String], settings: SqsStreamSettings): ZIO[Any, Throwable, List[Message]] =
    for {
      client <- clientResource
      messagesFromQueue <- client.use { c =>
                            for {
                              _        <- Utils.createQueue(c, queueName)
                              queueUrl <- Utils.getQueueUrl(c, queueName)
                              _        <- ZIO.foreach(messages)(SqsPublisher.send(c, queueUrl, _))
                              messagesFromQueue <- SqsStream(
                                                    c,
                                                    queueUrl,
                                                    settings
                                                  ).runCollect
                            } yield { messagesFromQueue }
                          }

    } yield {
      messagesFromQueue
    }

  def deleteAndGet(messages: Seq[Message], settings: SqsStreamSettings): ZIO[Any, Throwable, List[Message]] =
    for {
      client <- clientResource
      list <- client.use { c =>
               for {
                 queueUrl <- Utils.getQueueUrl(c, queueName)
                 _ <- ZIO.foreach(messages)(
                       SqsStream.deleteMessage(
                         c,
                         queueUrl,
                         _
                       )
                     )
                 list <- SqsStream(
                          c,
                          queueUrl,
                          settings
                        ).runCollect
               } yield { list }
             }
    } yield {
      list
    }

  def get(settings: SqsStreamSettings): ZIO[Any, Throwable, List[Message]] =
    for {
      client <- clientResource
      list <- client.use { c =>
               for {
                 queueUrl <- Utils.getQueueUrl(c, queueName)
                 list <- SqsStream(
                          c,
                          queueUrl,
                          settings
                        ).runCollect
               } yield { list }
             }
    } yield {
      list
    }
}
