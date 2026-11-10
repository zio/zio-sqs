---
id: getting-started
title: "Getting Started"
---

ZIO SQS needs an SQS client before it can send or receive messages. The client is provided as a ZIO layer of type `ZLayer[Any, Throwable, Sqs]`, so the rest of the program only asks for `Sqs` in its environment.

## Adding the Dependency

Add `zio-sqs` to the `libraryDependencies` in `build.sbt`:

```scala
libraryDependencies += "dev.zio" %% "zio-sqs" % "@VERSION@"
```

## Creating the Client Layer

The SQS client needs two inputs: an HTTP client and an AWS configuration. `NettyHttpClient.default` provides the HTTP client, `AwsConfig.default` provides the configuration, and `Sqs.live` builds the client. Each layer feeds the next one through `>>>`:

```scala mdoc:silent
import zio._
import zio.aws.core.config.AwsConfig
import zio.aws.netty.NettyHttpClient
import zio.aws.sqs.Sqs

val sqsLayer: ZLayer[Any, Throwable, Sqs] =
  NettyHttpClient.default >>> AwsConfig.default >>> Sqs.live
```

With `AwsConfig.default`, the region and credentials are left to the AWS SDK defaults. Those are read from environment variables such as `AWS_REGION`, `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, then from the `~/.aws` profile files, then from the instance or container role. The [AWS SDK for Java documentation](https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/credentials.html) describes the full lookup order.

To set the region in code instead, pass a customization function to `Sqs.customized`. This layer uses `us-east-1` regardless of the environment:

```scala mdoc:silent
import software.amazon.awssdk.regions.Region

val sqsInVirginia: ZLayer[Any, Throwable, Sqs] =
  NettyHttpClient.default >>> AwsConfig.default >>> Sqs.customized(_.region(Region.US_EAST_1))
```

## Creating a Queue and Sending a Message

The following program creates a queue, sends one message to it, and reads the message back. It needs a real AWS account, so the block is compiled but not run:

```scala mdoc:compile-only
import zio.sqs.producer.{Producer, ProducerEvent}
import zio.sqs.serialization.Serializer
import zio.sqs.{SqsStream, SqsStreamSettings, Utils}

object GettingStarted extends ZIOAppDefault {
  val queueName = "GettingStartedQueue"

  val program: ZIO[Sqs, Throwable, Unit] = for {
    _        <- Utils.createQueue(queueName)
    queueUrl <- Utils.getQueueUrl(queueName)
    _        <- ZIO.scoped {
                  Producer.make(queueUrl, Serializer.serializeString).flatMap(_.produce(ProducerEvent("hello, SQS")))
                }
    _        <- SqsStream(
                  queueUrl,
                  SqsStreamSettings.default.withStopWhenQueueEmpty(true).withWaitTimeSeconds(3).withAutoDelete(true)
                ).foreach(msg => Console.printLine(msg.body.getOrElse("")))
  } yield ()

  override def run: Task[Unit] = program.provide(sqsLayer)
}
```

`Utils.createQueue` creates the queue when it does not exist. `Utils.getQueueUrl` returns the URL that the producer and the stream need. `Producer.make` returns a producer that lives inside a `Scope`, so the program wraps it in `ZIO.scoped`. The `withAutoDelete(true)` setting deletes each message as soon as it is received. The consuming guide explains the other delivery option.
