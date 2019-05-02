# ZIO Connector for AWS SQS

[![CircleCI](https://circleci.com/gh/zio/zio-sqs/tree/master.svg?style=svg)](https://circleci.com/gh/zio/zio-sqs/tree/master)

This library is a [ZIO](https://github.com/scalaz/scalaz-zio)-powered client for AWS SQS. It is built on top of the [AWS SDK for Java 2.0](https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/basics.html).

## Add the dependency

To use `zio-sqs`, add the following line in your `build.sbt` file:

```
libraryDependencies += "dev.zio" %% "zio-sqs" % "0.1.0"
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

- `delaySeconds`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-message-timers.html) (default `0`)
- `messageDeduplicationId`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagededuplicationid-property.html)
- `messageGroupId`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagegroupid-property.html)
- `messageAttributes`: see the [related page on AWS docs](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-message-attributes.html)

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
SqsStream(
  client,
  queueUrl,
  SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = 3)
).foreach(msg => UIO(println(msg.body)))
```

### Helpers

The `Utils` object provides a couple helpful functions to create a queue and find a queue URL from its name.

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
                   .endpointOverride(new URI("http://localhost:4576")) // point to localstack
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
    } yield 0).foldM(e => UIO(println(e.toString())).const(1), IO.succeed)
}
```
