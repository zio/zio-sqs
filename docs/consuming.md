---
id: consuming
title: "Consuming Messages"
---

`SqsStream.apply` reads messages from a queue as a ZIO stream. Each element is a `Message.ReadOnly`, and the stream keeps long-polling SQS until the program stops reading from it. Two delivery models are available. With at-most-once delivery, the library deletes each message when it is received. With at-least-once delivery, the message is deleted only after the program has finished processing it.

## Reading a Stream of Messages

`SqsStream.apply` takes a queue URL and a `SqsStreamSettings` value. The default settings enable long polling, which means each receive call waits up to 20 seconds for a message to arrive:

```scala mdoc:compile-only
import zio._
import zio.aws.sqs.Sqs
import zio.sqs.{SqsStream, SqsStreamSettings}

val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue"

val printBodies: ZIO[Sqs, Throwable, Unit] =
  SqsStream(queueUrl, SqsStreamSettings.default).foreach(msg => Console.printLine(msg.body.getOrElse("")))
```

The stream never ends on its own unless `stopWhenQueueEmpty` is enabled, as described below. Any operator from ZIO Streams can transform the messages before they reach the final step.

## Choosing When Messages Are Deleted

A message that SQS delivers stays hidden from other consumers until its visibility timeout expires. Deleting the message removes it for good. The `autoDelete` setting controls when the library deletes messages. It defaults to `false`.

With `autoDelete` set to `false`, the program deletes each message itself after it has finished processing it. `SqsStream.deleteMessage` deletes one message:

```scala mdoc:compile-only
import zio._
import zio.aws.sqs.Sqs
import zio.sqs.{SqsStream, SqsStreamSettings}

val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue"

val processThenDelete: ZIO[Sqs, Throwable, Unit] =
  SqsStream(queueUrl, SqsStreamSettings.default.withAutoDelete(false)).foreach { msg =>
    Console.printLine(msg.body.getOrElse("")) *> SqsStream.deleteMessage(queueUrl, msg)
  }
```

Deleting messages one at a time makes one request per message. `SqsStream.deleteMessageBatchSink` deletes the messages of each chunk in batches of up to 10 instead. Chunks reach the sink only after the stages before it have processed them, so this sink gives at-least-once delivery when `autoDelete` is `false`:

```scala mdoc:compile-only
import zio._
import zio.aws.sqs.Sqs
import zio.sqs.{SqsStream, SqsStreamSettings}

val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue"

val processInBatches: ZIO[Sqs, Throwable, Unit] =
  SqsStream(queueUrl, SqsStreamSettings.default.withAutoDelete(false))
    .tap(msg => Console.printLine(msg.body.getOrElse("")))
    .run(SqsStream.deleteMessageBatchSink(queueUrl))
```

With `autoDelete` set to `true`, the library deletes each chunk in a batch before the chunk reaches the program. A message that fails during processing is already gone, so this mode gives at-most-once delivery.

## Stopping When the Queue Is Empty

By default, the stream waits for new messages forever. With `stopWhenQueueEmpty` set to `true`, the stream ends as soon as a receive call returns no messages. This makes it possible to drain a queue into a list:

```scala mdoc:compile-only
import zio._
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.Message
import zio.sqs.{SqsStream, SqsStreamSettings}

val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue"

val drain: ZIO[Sqs, Throwable, Chunk[Message.ReadOnly]] =
  SqsStream(queueUrl, SqsStreamSettings.default.withStopWhenQueueEmpty(true).withWaitTimeSeconds(3)).runCollect
```

Setting `waitTimeSeconds` to a small value, such as 3, makes the empty check return sooner. Long polling still applies, so the stream waits up to that time before it decides that the queue is empty.

## At-Least-Once Processing

`SqsStream.consumeChunkAtLeastOnce` receives a chunk of messages, passes the whole chunk to a `process` function, and deletes the chunk only after `process` succeeds. If `process` fails, the messages stay in the queue. They become visible again when the visibility timeout expires, and another receive call picks them up.

`SqsStream.consumeChunkAtLeastOnce` takes the queue URL, the stream settings, the extension settings, the number of parallel consumers, and the processing function:

```scala mdoc:compile-only
import zio._
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.Message
import zio.sqs.{SqsMessageLifetimeExtensionSettings, SqsStream, SqsStreamSettings}

val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue"

def process(messages: Chunk[Message.ReadOnly]): Task[Unit] =
  ZIO.debug(messages.map(_.body.getOrElse(""))) *> ZIO.sleep(14.seconds)

val atLeastOnce: RIO[Sqs, Unit] =
  SqsStream.consumeChunkAtLeastOnce(
    queueUrl = queueUrl,
    settings = SqsStreamSettings.default
      .withMaxNumberOfMessages(10)
      .withVisibilityTimeout(30)
      .withWaitTimeSeconds(20),
    extensionSettings = SqsMessageLifetimeExtensionSettings.default,
    consumerParallelism = 2
  )(process)
```

The `consumerParallelism` argument starts that many independent receive-and-process loops, each one working on its own chunk. The default is `1`. The `maxNumberOfMessages` setting controls how many messages each receive call returns, up to 10.

The `autoDelete` setting has no effect on `SqsStream.consumeChunkAtLeastOnce`, because this function always deletes after processing.

## Extending the Visibility Timeout

Processing a chunk can take longer than the visibility timeout. When that happens, SQS would make the messages visible to other consumers while the first consumer is still working on them. To prevent this, `SqsStream.consumeChunkAtLeastOnce` extends the visibility timeout of the chunk in the background while `process` runs. The extension is controlled by `SqsMessageLifetimeExtensionSettings`:

- `automaticExtension` turns the background extension on or off. It defaults to `true`.
- `maximumRetries` sets how many times a failed visibility change is retried. It defaults to `16`.
- `overrideInitialDelay` sets the time before the first extension. By default, the first extension happens at half of the visibility timeout, or after 15 seconds when no visibility timeout is set.
- `overrideRepeatSchedule` replaces the schedule that repeats the extension. By default, the extension repeats every half visibility timeout.

Each extension request sets the visibility timeout of the chunk again, so the messages stay hidden for as long as processing takes. The extension stops as soon as `process` finishes, whether it succeeded or failed.

For a single long-running message, `SqsStream.extendMessageLifetime` sets the visibility timeout of that message to a value chosen by the program. The following example processes each message for 90 seconds, which is longer than the visibility timeout of 30 seconds, so the message is extended to 120 seconds before the work starts:

```scala mdoc:compile-only
import zio._
import zio.aws.sqs.Sqs
import zio.stream.ZStream
import zio.sqs.{SqsStream, SqsStreamSettings}

val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue"

val longRunning: ZStream[Sqs, Throwable, Unit] =
  SqsStream(queueUrl, SqsStreamSettings.default.withVisibilityTimeout(30)).mapZIO { msg =>
    SqsStream.extendMessageLifetime(queueUrl, msg, 120) *>
      ZIO.sleep(90.seconds) *>
      SqsStream.deleteMessage(queueUrl, msg)
  }
```

## Handling Errors

Errors from SQS fail the stream with a `Throwable`. The stream stops at that point, so a program that must keep running should restart it or handle the error with the usual ZIO operators, such as `retry` or `catchAll`.

Batch deletions retry on their own. When SQS reports a message as failed in a batch delete, the library retries that message up to 8 times. If it still fails, the deletion fails with `Failed to delete messages after retrying`.
