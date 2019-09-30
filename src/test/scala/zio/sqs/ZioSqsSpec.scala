package zio.sqs

import java.net.URI

import org.elasticmq.rest.sqs.SQSRestServerBuilder
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import zio._
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
  private val staticCredentialsProvider: StaticCredentialsProvider =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
  private val uri            = new URI("http://localhost:9324")
  private val region: Region = Region.AP_NORTHEAST_2

  val gen = Gen.listOfN(10)(Gen.string(Gen.printableChar))

  val serverResource = ZIO.effect(
    ZManaged.make(
      Task(SQSRestServerBuilder.start())
    ) { server =>
      UIO.effectTotal(server.stopAndWait())
    }
  )

  val clientResource = ZIO.effect(
    ZManaged.make(
      Task {
        SqsAsyncClient
          .builder()
          .region(region)
          .credentialsProvider(
            staticCredentialsProvider
          )
          .endpointOverride(uri)
          .build()
      }
    ) { client =>
      UIO.effectTotal(client.close())
    }
  )

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
