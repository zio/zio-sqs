package zio.sqs.producer

import zio.Duration
import zio.durationInt

/**
 * Settings for the producer.
 * Creating the settings with a `batchSize` above 10 throws a `java.lang.IllegalArgumentException`.
 * @param batchSize size of the batch to use. Up to 10 messages can be buffered and sent as a batch request.
 * @param duration time to wait for the batch to be full (have the specified batchSize).
 * @param parallelism number of concurrent requests to make to SQS.
 * @param retryDelay time to wait before retrying event republishing if it failed with a recoverable error.
 * @param retryMaxCount the maximum number of retries for an event that fails with a recoverable error (see [[ProducerError.isRecoverable]]).
 */
final case class ProducerSettings(
  batchSize: Int = 10,
  duration: Duration = 500.millisecond,
  parallelism: Int = 16,
  retryDelay: Duration = 250.millisecond,
  retryMaxCount: Int = 10
) {
  require(batchSize <= 10, "up to 10 messages can be buffered and sent as a batch request")
}
