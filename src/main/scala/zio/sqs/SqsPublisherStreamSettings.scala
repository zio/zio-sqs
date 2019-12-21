package zio.sqs

import zio.duration.{Duration, _}

final case class SqsPublisherStreamSettings(
  batchSize: Int = 10,
  duration: Duration = 1.second,
  parallelism: Int = 16
)
