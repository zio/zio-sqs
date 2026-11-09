---
id: reference
title: "Reference"
---

This page lists the public API of ZIO SQS: its methods, settings and default values. The guides explain when to use each part, and the examples in them are compiled against the same source, so the signatures here match the code. The AWS client layers come from zio-aws and are covered in [Getting Started](./getting-started.md).

## Consuming API

The consuming API reads messages from a queue, deletes them, and changes their visibility timeout. Everything in it lives in the `zio.sqs.SqsStream` object and the two settings classes that configure it.

### SqsStream Methods

Each method in `SqsStream` is listed with its signature and a short description:

| Method                       | Signature                                                                                                                                                                                               | Description                                                                           |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| `SqsStream.apply`            | `(queueUrl: String, settings: SqsStreamSettings = SqsStreamSettings.default): ZStream[Sqs, Throwable, Message.ReadOnly]`                                                                                | Long-polls the queue and emits the received messages.                                 |
| `consumeChunkAtLeastOnce`    | `(queueUrl: String, settings: SqsStreamSettings, extensionSettings: SqsMessageLifetimeExtensionSettings, consumerParallelism: Int = 1)(process: Chunk[Message.ReadOnly] => Task[Unit]): RIO[Sqs, Unit]` | Processes each chunk and deletes it only after `process` succeeds.                    |
| `extendMessageLifetime`      | `(queueUrl: String, message: Message.ReadOnly, seconds: Int): RIO[Sqs, Unit]`                                                                                                                           | Sets the visibility timeout of one message to `seconds`.                              |
| `extendMessageLifetimeBatch` | `(queueUrl: String, messages: Chunk[Message.ReadOnly], seconds: Int, maximumRetries: Int = 8): RIO[Sqs, Chunk[Message.ReadOnly]]`                                                                       | Sets the visibility timeout of a list of messages, in batches of 10.                  |
| `deleteMessage`              | `(queueUrl: String, msg: Message.ReadOnly): RIO[Sqs, Unit]`                                                                                                                                             | Deletes one message.                                                                  |
| `deleteMessageBatch`         | `(queueUrl: String, msgs: Chunk[Message.ReadOnly], maximumRetries: Int = 8): RIO[Sqs, Chunk[Message.ReadOnly]]`                                                                                         | Deletes messages in batches of 10 and retries the entries that SQS reports as failed. |
| `deleteMessageBatchSink`     | `(queueUrl: String, maximumRetries: Int = 8): ZSink[Sqs, Throwable, Message.ReadOnly, Nothing, Unit]`                                                                                                   | A sink that calls `deleteMessageBatch` for each chunk it receives.                    |

### SqsStreamSettings

`SqsStreamSettings` is a case class. Its `default` value, `SqsStreamSettings.default`, has the values in the Default column. Each field has a builder method that returns a copy with the field changed.

| Field                   | Type                       | Default    | Meaning                                                                                         |
| ----------------------- | -------------------------- | ---------- | ----------------------------------------------------------------------------------------------- |
| `attributeNames`        | `List[QueueAttributeName]` | `Nil`      | Queue attributes to return with each message.                                                   |
| `maxNumberOfMessages`   | `Option[Int]`              | `None`     | Messages per receive call, from 1 to 10. `None` uses the SQS default of 1.                      |
| `messageAttributeNames` | `List[String]`             | `Nil`      | Message attributes to return with each message.                                                 |
| `visibilityTimeout`     | `Option[Int]`              | `None`     | Seconds that a received message stays hidden. `None` uses the queue's setting.                  |
| `waitTimeSeconds`       | `Option[Int]`              | `Some(20)` | Seconds that a receive call waits for messages (long polling). `None` uses the queue's setting. |
| `autoDelete`            | `Boolean`                  | `false`    | Deletes each received chunk before it is emitted.                                               |
| `stopWhenQueueEmpty`    | `Boolean`                  | `false`    | Ends the stream when a receive call returns no messages.                                        |

The builder methods are `withAttributeName`, `withAttributeNames`, `withMaxNumberOfMessages`, `withMessageAttributeName`, `withMessageAttributeNames`, `withVisibilityTimeout`, `withWaitTimeSeconds`, `withAutoDelete` and `withStopWhenQueueEmpty`. The name builders differ in how they change the list. `withAttributeName` and `withMessageAttributeName` add one name to the front of the list, `withAttributeNames` replaces the whole list, and `withMessageAttributeNames` appends to the end of it.

### SqsMessageLifetimeExtensionSettings

`SqsMessageLifetimeExtensionSettings` controls the background visibility extension of `consumeChunkAtLeastOnce`. Its `default` value, `SqsMessageLifetimeExtensionSettings.default`, enables the extension and retries failed visibility changes 16 times.

| Field                    | Type                              | Default | Meaning                                                                                                 |
| ------------------------ | --------------------------------- | ------- | ------------------------------------------------------------------------------------------------------- |
| `automaticExtension`     | `Boolean`                         | `true`  | Turns the background extension on or off.                                                               |
| `maximumRetries`         | `Int`                             | `16`    | Number of retries for a failed visibility change.                                                       |
| `overrideInitialDelay`   | `Option[Duration]`                | `None`  | Time before the first extension. `None` uses half the visibility timeout, or 15 seconds.                |
| `overrideRepeatSchedule` | `Option[Schedule[Any, Any, Any]]` | `None`  | Schedule that repeats the extension. `None` repeats every half visibility timeout, or every 15 seconds. |

The builder methods are `withAutomaticExtension`, `withMaximumRetries` and `withOverrideInitialDelay`. There is no builder for `overrideRepeatSchedule`, so set it through the constructor.

## Producing API

The producing API sends events to a queue. It consists of the `Producer` trait with its companion method `Producer.make`, the settings that tune batching and retries, and the event and error types.

### Producer Methods

`Producer[T]` is a trait for publishing events of type `T`. Its methods are listed here. The `T` type parameter is the payload type given to `Producer.make`.

| Method          | Signature                                                                          | Description                                                                                             |
| --------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `produce`       | `(e: ProducerEvent[T]): Task[ProducerEvent[T]]`                                    | Publishes one event. Fails if SQS rejects it.                                                           |
| `produceBatch`  | `(es: Iterable[ProducerEvent[T]]): Task[Iterable[ProducerEvent[T]]]`               | Publishes the events. Fails if SQS rejects any of them.                                                 |
| `produceBatchE` | `(es: Iterable[ProducerEvent[T]]): Task[Iterable[ErrorOrEvent[T]]]`                | Publishes the events and returns one `ErrorOrEvent` per input. Does not fail for SQS errors.            |
| `sendStream`    | `Stream[Throwable, ProducerEvent[T]] => ZStream[Any, Throwable, ProducerEvent[T]]` | Publishes each event of the input stream and emits the published events. Fails if SQS rejects an event. |
| `sendStreamE`   | `Stream[Throwable, ProducerEvent[T]] => ZStream[Any, Throwable, ErrorOrEvent[T]]`  | Publishes each event of the input stream and emits one `ErrorOrEvent` per event.                        |
| `sendSink`      | `ZSink[Any, Throwable, Iterable[ProducerEvent[T]], Nothing, Unit]`                 | A sink that publishes each batch of events it receives. Fails if SQS rejects an event.                  |

`Producer.make` creates a producer. Its signature is `[T](queueUrl: String, serializer: Serializer[T], settings: ProducerSettings = ProducerSettings()): ZIO[Sqs & Scope, Throwable, Producer[T]]`. The producer runs in the scope, so `ZIO.scoped` must wrap its use.

### ProducerSettings

`ProducerSettings` is a case class with five fields, all of which have defaults. Its constructor fails with an `IllegalArgumentException` when `batchSize` is greater than 10.

| Field           | Type       | Default           | Meaning                                                     |
| --------------- | ---------- | ----------------- | ----------------------------------------------------------- |
| `batchSize`     | `Int`      | `10`              | Events per SQS request, at most 10.                         |
| `duration`      | `Duration` | `500.millisecond` | Longest time to wait for a batch to fill before it is sent. |
| `parallelism`   | `Int`      | `16`              | Batch requests in flight at once.                           |
| `retryDelay`    | `Duration` | `250.millisecond` | Time to wait before a recoverable failure is retried.       |
| `retryMaxCount` | `Int`      | `10`              | Number of retries for an event with a recoverable failure.  |

### ProducerEvent and ProducerError

`ProducerEvent[T]` is a case class for one event. Its constructor takes `data`, `attributes`, `groupId` and `deduplicationId` without defaults, and `delay` with the default `None`. The companion `ProducerEvent.apply(body: String): ProducerEvent[String]` creates an event with empty settings.

`ProducerError[T]` is a case class that extends `RuntimeException`. It has four fields: `senderFault: Boolean`, `code: String`, `message: Option[String]` and `event: ProducerEvent[T]`. The companion `ProducerError.isRecoverable(code: String): Boolean` returns `true` for `ServiceUnavailable` and `ThrottlingException`.

`ErrorOrEvent[T]` is a type alias for `Either[ProducerError[T], ProducerEvent[T]]`, and it is the element type of the `E` methods.

## Serialization and Utils

The serialization API converts events into message bodies. The `Utils` object looks up and creates queues.

### Serializer

`Serializer[A]` is a trait with one abstract method. Its companion object provides `Serializer.serializeString`.

| Member                       | Signature                       | Description                                                   |
| ---------------------------- | ------------------------------- | ------------------------------------------------------------- |
| `Serializer#apply`           | `(a: A): String`                | Converts a value to a message body. The only abstract method. |
| `contramap`                  | `[B](f: B => A): Serializer[B]` | Builds a serializer for `B` by applying `f` first.            |
| `Serializer.serializeString` | `Serializer[String]`            | The identity serializer for strings.                          |

### Utils

`Utils` has two methods for working with queues by name.

| Method        | Signature                                                                             | Description                                               |
| ------------- | ------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| `createQueue` | `(name: String, attributes: Map[QueueAttributeName, String] = Map()): RIO[Sqs, Unit]` | Creates a queue with the given name and attributes.       |
| `getQueueUrl` | `(name: String): RIO[Sqs, String]`                                                    | Returns the URL of an existing queue with the given name. |
