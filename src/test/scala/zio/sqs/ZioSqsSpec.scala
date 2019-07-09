package zio.sqs

import java.net.URI

import com.danielasfregola.randomdatagenerator.RandomDataGenerator
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import org.scalatest.{ FlatSpec, Matchers }
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import zio._

class ZioSqsSpec extends FlatSpec with Matchers with DefaultRuntime with RandomDataGenerator {
  private val queueName = "TestQueue"
  private val staticCredentialsProvider: StaticCredentialsProvider =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("key1", "key"))
  private val uri                   = new URI("http://localhost:9324")
  private val region: Region        = Region.AP_NORTHEAST_2
  private val messages: Seq[String] = random[String](10)

  private val serverResource = ZIO.effect(
    ZManaged.make(
      Task(SQSRestServerBuilder.start())
    ) { server =>
      UIO.effectTotal(server.stopAndWait())
    }
  )

  private val clientResource = ZIO.effect(
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

  "ZioSqsSpec" should "send messages" in {
    val settings: SqsStreamSettings = SqsStreamSettings(stopWhenQueueEmpty = true)

    val result = for {
      server <- serverResource
      list <- server.use { _ =>
               sendAndGet(messages, settings)
             }

    } yield {
      list.map(_.body()) shouldBe messages
    }

    unsafeRun(result)
  }

  it should "delete messages manually" in {
    val settings: SqsStreamSettings =
      SqsStreamSettings(stopWhenQueueEmpty = true, autoDelete = false, waitTimeSeconds = 1)

    val result = for {
      server <- serverResource
      list <- server.use { _ =>
               for {
                 messageFromQueue <- sendAndGet(messages, settings)
                 list             <- deleteAndGet(messageFromQueue, settings)
               } yield { list }
             }

    } yield {
      list shouldBe Nil
    }

    unsafeRun(result)
  }

  it should "delete messages automatically" in {
    val settings: SqsStreamSettings =
      SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 1)

    val result = for {
      server <- serverResource
      list <- server.use { _ =>
               for {
                 _    <- sendAndGet(messages, settings)
                 list <- get(settings)
               } yield { list }
             }

    } yield {
      list shouldBe Nil
    }

    unsafeRun(result)
  }

  private def sendAndGet(messages: Seq[String], settings: SqsStreamSettings): ZIO[Any, Throwable, List[Message]] =
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

  private def deleteAndGet(messages: Seq[Message], settings: SqsStreamSettings): ZIO[Any, Throwable, List[Message]] =
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

  private def get(settings: SqsStreamSettings): ZIO[Any, Throwable, List[Message]] =
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
