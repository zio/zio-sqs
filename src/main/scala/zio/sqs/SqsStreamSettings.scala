package zio.sqs

case class SqsStreamSettings(
  attributeNames: List[String] = Nil,
  maxNumberOfMessages: Int = 1,
  messageAttributeNames: List[String] = Nil,
  visibilityTimeout: Option[Int] = None,
  waitTimeSeconds: Option[Int] = None,
  autoDelete: Boolean = true,
  stopWhenQueueEmpty: Boolean = false
)
