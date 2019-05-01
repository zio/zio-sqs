package com.github.ghostdogpr.zio.sqs

case class SqsStreamSettings(
  attributeNames: List[String] = Nil,
  maxNumberOfMessages: Int = 1,
  messageAttributeNames: List[String] = Nil,
  visibilityTimeout: Int = 30,
  waitTimeSeconds: Int = 20,
  autoDelete: Boolean = true,
  stopWhenQueueEmpty: Boolean = false
)
