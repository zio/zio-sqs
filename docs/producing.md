---
id: producing
title: "Producing Messages"
---

`Producer` publishes events of type `T` to a queue. Each event is turned into a string by a `Serializer`, collected into batches, and sent to SQS in the background. The producer does the batching, so the program can publish one event at a time and still send 10 events per request.

## Creating a Producer

`Producer.make` takes a queue URL, a serializer, and optional `ProducerSettings`. It returns the producer inside a `Scope`, because the producer starts a background fiber that must be stopped when the scope closes. `ZIO.scoped` provides the scope and closes it at the end of the program.

The examples below use this shared setup. The queue URL is a placeholder, and every example needs a real AWS account, so they are compiled but not run:

```scala mdoc:silent
import zio._
import zio.aws.sqs.Sqs
import zio.stream.ZStream
import zio.sqs.Utils
import zio.sqs.producer.{ErrorOrEvent, Producer, ProducerEvent, ProducerSettings}
import zio.sqs.serialization.Serializer

val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue"

val events: List[ProducerEvent[String]] = List("order created", "order paid", "order shipped").map(ProducerEvent(_))
```

To publish a single event with `Producer#produce`, create the producer and call the method inside the same scope:

```scala mdoc:compile-only
val publishOne: ZIO[Sqs, Throwable, ProducerEvent[String]] =
  ZIO.scoped {
    Producer.make(queueUrl, Serializer.serializeString).flatMap(_.produce(ProducerEvent("first event")))
  }
```

## Publishing Batches

`Producer#produceBatch` publishes a collection of events and waits for SQS to answer each event. The collection it gives back contains each event from the input, in the same order. The task fails if SQS rejects any of the events:

```scala mdoc:compile-only
val publishBatch: ZIO[Sqs, Throwable, Iterable[ProducerEvent[String]]] =
  ZIO.scoped {
    Producer.make(queueUrl, Serializer.serializeString).flatMap(_.produceBatch(events))
  }
```

## Streaming Events

`Producer#sendStream` takes a stream of events and returns a stream of the published events. Each event is sent as it flows through, and the stream fails if SQS rejects an event. To publish a stream of events and wait for the end of it:

```scala mdoc:compile-only
val publishStream: ZIO[Sqs, Throwable, Unit] =
  ZIO.scoped {
    Producer.make(queueUrl, Serializer.serializeString).flatMap { producer =>
      producer.sendStream(ZStream.fromIterable(events)).runDrain
    }
  }
```

`Producer#sendSink` is a sink for the same purpose. It accepts a stream of batches, where each element is an `Iterable` of events, and publishes every batch it receives:

```scala mdoc:compile-only
val publishSink: ZIO[Sqs, Throwable, Unit] =
  ZIO.scoped {
    Producer.make(queueUrl, Serializer.serializeString).flatMap { producer =>
      ZStream.succeed(events).run(producer.sendSink)
    }
  }
```

## Handling Errors Without Failing

The methods above fail as soon as SQS rejects an event. The `E` variants, `Producer#produceBatchE` and `Producer#sendStreamE`, do not fail for that. Each event produces an `ErrorOrEvent[T]`, which is an `Either` of a `ProducerError` on the left or the published event on the right. The program can then decide what to do with the failures:

```scala mdoc:compile-only
val retryFailed: ZIO[Sqs, Throwable, Iterable[ProducerEvent[String]]] =
  ZIO.scoped {
    Producer.make(queueUrl, Serializer.serializeString).flatMap { producer =>
      producer.produceBatchE(events).flatMap { results: Iterable[ErrorOrEvent[String]] =>
        val failed = results.collect { case Left(error) => error.event }
        producer.produceBatch(failed)
      }
    }
  }
```

The example above publishes the failed events again with `Producer#produceBatch`. A real program would usually limit the number of attempts and log the codes of the errors first.

`ProducerError` is a `RuntimeException` with four fields. `senderFault` tells whether the caller caused the error. `code` is the SQS error code, and `message` is the optional message from SQS. `event` is the event that failed, so it can be retried or stored.

## Retries

Some SQS errors are temporary. The producer treats `ServiceUnavailable` and `ThrottlingException` as recoverable and resends those events after `retryDelay`. Each event is retried at most `retryMaxCount` times. When the limit is reached, the event fails with its last `ProducerError`. Every other error code fails the event immediately, without a retry.

If the request itself throws an exception, every event in that batch fails with the exception.

## Producer Settings

`ProducerSettings` controls batching, concurrency, and retries. All fields have defaults, so the producer works with `ProducerSettings()`:

```scala mdoc:compile-only
val tunedSettings: ProducerSettings = ProducerSettings(
  batchSize = 5,
  duration = 200.millis,
  parallelism = 4,
  retryDelay = 1.second,
  retryMaxCount = 3
)

val tunedProducer: ZIO[Sqs & Scope, Throwable, Producer[String]] =
  Producer.make(queueUrl, Serializer.serializeString, tunedSettings)
```

`batchSize` sets how many events go into one SQS request. SQS accepts at most 10 events per request, so a larger value makes `ProducerSettings` throw an `IllegalArgumentException` on construction. `duration` sets how long the producer waits for a batch to fill up. When the time passes first, the producer sends the events it has, even if the batch is not full. `parallelism` sets how many batch requests are in flight at once. `retryDelay` and `retryMaxCount` control the retries described above.

`Producer#sendStream` publishes `batchSize` events at a time, so its concurrency follows `batchSize`, not `parallelism`.

Setting `batchSize` to `1` turns off batching: each event is sent in its own request.

## Event Attributes, Groups, and Deduplication

`ProducerEvent` carries the payload and the SQS settings that go with it. Besides `data`, it has four optional settings:

- `attributes` is a map of SQS message attributes. Consumers read them when they request the attribute names in their stream settings.
- `groupId` puts the message in a [message group](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagegroupid-property.html), which is required for FIFO queues.
- `deduplicationId` is a [deduplication token](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagededuplicationid-property.html) that SQS uses to drop repeated messages.
- `delay` postpones delivery of the message. The value must be between 0 and 15 minutes. The producer sends it as whole seconds.

The following event sets the attributes, group, deduplication token, and delay at once:

```scala mdoc:compile-only
import zio.aws.sqs.model.MessageAttributeValue

val fifoEvent: ProducerEvent[String] = ProducerEvent[String](
  data = "order 42 paid",
  attributes = Map("eventType" -> MessageAttributeValue(dataType = "String", stringValue = Some("payment"))),
  groupId = Some("orders"),
  deduplicationId = Some("order-42-paid"),
  delay = Some(5.seconds)
)
```

For FIFO queues, SQS requires a `groupId` on every event. The producer sends whatever the event holds, so an event without a group that is published to a FIFO queue fails with the `ProducerError` that SQS returns.
