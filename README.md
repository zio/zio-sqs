# ZIO Connector for AWS SQS

[![CircleCI](https://circleci.com/gh/zio/zio-sqs/tree/master.svg?style=svg)](https://circleci.com/gh/zio/zio-sqs/tree/master)

This library is a [ZIO](https://github.com/zio/zio)-powered client for AWS SQS. It is built on top of the [AWS SDK for Java 2.0](https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/basics.html).

## Add the dependency

To use `zio-sqs`, add the following line in your `build.sbt` file:

```
libraryDependencies += "dev.zio" %% "zio-sqs" % "0.1.12"
```

## How to use

In order to use the connector, you need a `SqsAsyncClient`. Refer to the [AWS SDK Documentation](https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/creating-clients.html) if you need help.

### Publish messages

Use `SqsPublisher.send` to publish messages to a queue.

```scala
def send(
    client: SqsAsyncClient,
    queueUrl: String,
    msg: String,
    settings: SqsPublisherSettings = SqsPublisherSettings()
  ): Task[Unit]
```

`SqsPublisherSettings` allows your to configure a number of things:

- `delaySeconds`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-message-timers.html) (default `None`). This parameter should be `None` for a FIFO queue.
- `messageDeduplicationId`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagededuplicationid-property.html)
- `messageGroupId`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagegroupid-property.html)
- `messageAttributes`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-message-attributes.html)

```scala
import zio.sqs.SqsPublisher

SqsPublisher.send(client, queueUrl, msg)
```

#### Publish with streaming

To publish a collection of messages, `stringStream` or `eventStream` can be used.

`stringStream` allows to publish a one or more of strings. When publishing plain strings, no `attributes`, `groupId` or `deduplicationId` is assigned to published messages.

```scala
def stringStream(
  client: SqsAsyncClient,
  queueUrl: String,
  settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
)(
  ms: Stream[Throwable, String]
): ZStream[Clock, Throwable, ErrorOrEvent]
```

If one need to provide a custom `attributes`, `groupId` or `deduplicationId`, an `eventStream`-method can be used:

```scala
def eventStream(
  client: SqsAsyncClient,
  queueUrl: String,
  settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
)(
  ms: Stream[Throwable, Event]
): ZStream[Clock, Throwable, ErrorOrEvent]
```

`eventStream` uses the `Event`-trait to publish messages. `Event` has the following structure:

```scala
trait Event {
  def body: String
  def attributes: Map[String, MessageAttributeValue]
  def groupId: Option[String]
  def deduplicationId: Option[String]
}
```

Here:

- `body` - a message to publish to SQS.
- `attributes` - a map of [attributes](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-message-attributes.html) to set.
- `groupId` - assigns a specific [message group](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagegroupid-property.html) to the message.
- `deduplicationId` - token used for [deduplication](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagededuplicationid-property.html) of sent messages.

When `stringStream` or `eventStream` are used, they map a stream of strings(events) to the stream of results, `ErrorOrEvent`.
`ErrorOrEvent` is defined as: `Either[(BatchResultErrorEntry, Event), Event]`, which could be one of:

- `(BatchResultErrorEntry, Event)` - a pair, represending an error result from SQS and an original Event.
  `BatchResultErrorEntry` - is a detailed description of the [result](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_BatchResultErrorEntry.html).
- `Event` - the original event. If strings were streamed, `Event#body` will contain each of them.

`SqsPublisherStreamSettings` a collection of settings to tune the publishing parameters:

- `batchSize: Int` The size of the batch to use, [1-10] (default: 10).
- `duration: Duration` - Time to wait for the batch to be full (default: 1 second).
- `parallelism: Int` - the number of concurrent requests to make to SQS (default: 16).

**Example:**

```scala
val sendStringStream = SqsPublisher.stringStream(client, queueUrl, settings)

Stream("a", "b", "c")
  .via(sendStringStream)
  .mapM(ZIO.fromEither)
  .run(Sink.drain)
  .foldM(
    e => ZIO.effectTotal(logger.error("Application failed", e)) *> ZIO.succeed(1),
    _ => ZIO.succeed(0)
  )
```

### Consume messages

Use `SqsStream.apply` to get a stream of messages from a queue. It returns a ZIO `Stream` that you can consume with all the operators available.

```scala
def apply(
  client: SqsAsyncClient,
  queueUrl: String,
  settings: SqsStreamSettings = SqsStreamSettings()
): Stream[Throwable, Message]
```

`SqsStreamSettings` allows your to configure a number of things:

- `autoDelete`: if `true`, messages will be automatically deleted from the queue when they're consumed by the stream, if `false` you have to delete them explicitly by calling `SqsStream.deleteMessage` (default `true`)
- `stopWhenQueueEmpty`: if `true` the stream will close when there the queue is empty, if `false` the stream will go on forever (default `false`)
- `attributeNames`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html)
- `maxNumberOfMessages`: number of messages to query at once from SQS (default `1`)
- `messageAttributeNames`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html)
- `visibilityTimeout`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html) (default `30`)
- `waitTimeSeconds`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-long-polling.html) (default `20`),

**Example:**

```scala
import zio.sqs.{SqsStream, SqsStreamSettings}

SqsStream(
  client,
  queueUrl,
  SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3)
).foreach(msg => UIO(println(msg.body)))
```

### Helpers

The `zio.sqs.Utils` object provides a couple helpful functions to create a queue and find a queue URL from its name.

```scala
def createQueue(
  client: SqsAsyncClient,
  name: String,
  attributes: Map[QueueAttributeName, String] = Map()
): Task[Unit]

def getQueueUrl(
  client: SqsAsyncClient,
  name: String
): Task[String]
```

### Full example

```scala
import java.net.URI
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import zio.{ App, IO, Task, UIO, ZIO }
import zio.sqs.{ SqsPublisher, SqsStream, SqsStreamSettings, Utils }

object TestApp extends App {

  override def run(args: List[String]): ZIO[Environment, Nothing, Int] =
    (for {
      client <- Task {
                 SqsAsyncClient
                   .builder()
                   .region(Region.of("ap-northeast-2"))
                   .credentialsProvider(
                     StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
                   )
                   .build()
               }
      queueName = "TestQueue"
      _         <- Utils.createQueue(client, queueName)
      queueUrl  <- Utils.getQueueUrl(client, queueName)
      _         <- SqsPublisher.send(client, queueUrl, "hello")
      _         <- SqsStream(
                     client,
                     queueUrl,
                     SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3)
                   ).foreach(msg => UIO(println(msg.body)))
    } yield 0).foldM(e => UIO(println(e.toString())).as(1), IO.succeed)
}
```
