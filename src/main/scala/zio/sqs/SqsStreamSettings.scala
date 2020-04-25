package zio.sqs

case class SqsStreamSettings(
  attributeNames: List[String] = Nil,
  maxNumberOfMessages: Int = 1,
  messageAttributeNames: List[String] = Nil,
  visibilityTimeout: Option[Int] = Some(30),
  waitTimeSeconds: Option[Int] = Some(20),
  autoDelete: Boolean = true,
  stopWhenQueueEmpty: Boolean = false
)
