package zio.sqs

import java.net.URI

import org.elasticmq.rest.sqs.SQSRestServerBuilder
import org.scalatest.{FlatSpec, Matchers}
import scalaz.zio.{DefaultRuntime, Task}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message

class ZioSqsSpec extends FlatSpec with Matchers {
  "ZioSqsSpec" should "send a message" in {
    val result = for {
      server <- Task(
                 SQSRestServerBuilder.start()
               )
      client <- Task {
                 SqsAsyncClient
                   .builder()
                   .region(Region.AP_NORTHEAST_2)
                   .credentialsProvider(
                     StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
                   )
                   .endpointOverride(new URI("http://localhost:9324"))
                   .build()
               }
      queueName = "TestQueue"
      _         <- Utils.createQueue(client, queueName)
      queueUrl  <- Utils.getQueueUrl(client, queueName)
      _         <- SqsPublisher.send(client, queueUrl, "hello")
      message <- SqsStream(
                  client,
                  queueUrl,
                  SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3)
                ).map(_.body()).run(scalaz.zio.stream.Sink.await[String])
      _ <- Task(server.stopAndWait())
    } yield {
      message shouldBe "hello"
    }

    val runtime = new DefaultRuntime {}
    runtime.unsafeRun(result)
  }

  it should "send messages by order" in {
    val result = for {
      server <- Task(
                 SQSRestServerBuilder.start()
               )
      client <- Task {
                 SqsAsyncClient
                   .builder()
                   .region(Region.AP_NORTHEAST_2)
                   .credentialsProvider(
                     StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
                   )
                   .endpointOverride(new URI("http://localhost:9324"))
                   .build()
               }
      queueName = "TestQueue"
      _         <- Utils.createQueue(client, queueName)
      queueUrl  <- Utils.getQueueUrl(client, queueName)
      _         <- SqsPublisher.send(client, queueUrl, "hello")
      _         <- SqsPublisher.send(client, queueUrl, "hello1")
      _         <- SqsPublisher.send(client, queueUrl, "hello2")
      message <- SqsStream(
                  client,
                  queueUrl,
                  SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3)
                ).map(_.body()).run(scalaz.zio.stream.Sink.await[String])
      message1 <- SqsStream(
                   client,
                   queueUrl,
                   SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3)
                 ).map(_.body()).run(scalaz.zio.stream.Sink.await[String])
      message2 <- SqsStream(
                   client,
                   queueUrl,
                   SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3)
                 ).map(_.body()).run(scalaz.zio.stream.Sink.await[String])
      _ <- Task(server.stopAndWait())
    } yield {
      message shouldBe "hello"
      message1 shouldBe "hello1"
      message2 shouldBe "hello2"
    }

    val runtime = new DefaultRuntime {}
    runtime.unsafeRun(result)
  }

  it should "delete a message manually" in {
    val result = for {
      server <- Task(
                 SQSRestServerBuilder.start()
               )
      client <- Task {
                 SqsAsyncClient
                   .builder()
                   .region(Region.AP_NORTHEAST_2)
                   .credentialsProvider(
                     StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
                   )
                   .endpointOverride(new URI("http://localhost:9324"))
                   .build()
               }
      queueName = "TestQueue"
      _         <- Utils.createQueue(client, queueName)
      queueUrl  <- Utils.getQueueUrl(client, queueName)
      _         <- SqsPublisher.send(client, queueUrl, "hello")
      message <- SqsStream(
                  client,
                  queueUrl,
                  SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3, autoDelete = false)
                ).run(scalaz.zio.stream.Sink.await[Message])
      _ <- SqsStream.deleteMessage(
            client,
            queueUrl,
            message
          )
      list <- SqsStream(
               client,
               queueUrl,
               SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3)
             ).runCollect
      _ <- Task(server.stopAndWait())
    } yield {
      list shouldBe Nil
    }

    val runtime = new DefaultRuntime {}
    runtime.unsafeRun(result)
  }

  it should "delete messages manually" in {
    val result = for {
      server <- Task(
                 SQSRestServerBuilder.start()
               )
      client <- Task {
                 SqsAsyncClient
                   .builder()
                   .region(Region.AP_NORTHEAST_2)
                   .credentialsProvider(
                     StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
                   )
                   .endpointOverride(new URI("http://localhost:9324"))
                   .build()
               }
      queueName = "TestQueue"
      _         <- Utils.createQueue(client, queueName)
      queueUrl  <- Utils.getQueueUrl(client, queueName)
      _         <- SqsPublisher.send(client, queueUrl, "hello")
      _         <- SqsPublisher.send(client, queueUrl, "hello1")
      _         <- SqsPublisher.send(client, queueUrl, "hello2")
      message <- SqsStream(
                  client,
                  queueUrl,
                  SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3, autoDelete = false)
                ).run(scalaz.zio.stream.Sink.await[Message])
      message1 <- SqsStream(
                   client,
                   queueUrl,
                   SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3, autoDelete = false)
                 ).run(scalaz.zio.stream.Sink.await[Message])
      message2 <- SqsStream(
                   client,
                   queueUrl,
                   SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3, autoDelete = false)
                 ).run(scalaz.zio.stream.Sink.await[Message])
      _ <- SqsStream.deleteMessage(
            client,
            queueUrl,
            message
          )
      _ <- SqsStream.deleteMessage(
            client,
            queueUrl,
            message1
          )
      _ <- SqsStream.deleteMessage(
            client,
            queueUrl,
            message2
          )
      list <- SqsStream(
               client,
               queueUrl,
               SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3)
             ).runCollect
      _ <- Task(server.stopAndWait())
    } yield {
      list shouldBe Nil
    }

    val runtime = new DefaultRuntime {}
    runtime.unsafeRun(result)
  }
}
