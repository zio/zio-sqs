package zio.sqs

import zio.aws.sqs._
import zio.aws.sqs.model._
import zio.{ Chunk, Exit, RIO, Task, ZIO, ZIOAspect }
import zio.stream.ZStream
import zio.aws.sqs.model.primitives.MessageAttributeName
import zio.aws.core.AwsError
import zio.aws.core.GenericAwsError

object SqsStream {

  def apply(
    queueUrl: String,
    settings: SqsStreamSettings = SqsStreamSettings()
  ): ZStream[Sqs, Throwable, Message.ReadOnly] = {

    val request = ReceiveMessageRequest(
      queueUrl = queueUrl,
      attributeNames = Some(settings.attributeNames),
      messageAttributeNames = Some(settings.messageAttributeNames.map(MessageAttributeName.apply(_))),
      maxNumberOfMessages = Some(settings.maxNumberOfMessages),
      visibilityTimeout = Some(settings.visibilityTimeout.getOrElse(30)),
      waitTimeSeconds = Some(settings.waitTimeSeconds.getOrElse(20))
    )

    ZStream
      .repeatZIO(
        zio.aws.sqs.Sqs
          .receiveMessage(request)
          .mapError(_.toThrowable)
      )
      .map(_.messages.fold(Chunk.empty[Message.ReadOnly])(Chunk.from))
      .takeWhile(chunk => chunk.nonEmpty || !settings.stopWhenQueueEmpty)
      .flattenChunks
      .mapChunksZIO { messages =>
        // NOTE: At-most-once semantics (when autoDelete=true)
        if (settings.autoDelete) deleteMessageBatch(queueUrl, messages)
        else Exit.succeed(messages)
      }
  }

  /**
   * Consumes a batch of messages from the queue and deletes them after successful processing so users can focus on processing messages.
   * This will ignore autoDelete since messages are deleted only after successful processing.
   * This will also respect the stopWhenQueueEmpty setting.
   *
   * @param queueUrl
   * @param settings
   */
  def consumeChunkAtLeastOnce(
    queueUrl: String,
    settings: SqsStreamSettings,
    extensionSettings: SqsMessageLifetimeExtensionSettings
  )(process: Chunk[Message.ReadOnly] => Task[Unit]): RIO[Sqs, Unit] = {
    val request = ReceiveMessageRequest(
      queueUrl = queueUrl,
      attributeNames = Option(settings.attributeNames),
      messageAttributeNames = Option(settings.messageAttributeNames.map(MessageAttributeName.apply(_))),
      maxNumberOfMessages = Option(settings.maxNumberOfMessages),
      visibilityTimeout = settings.visibilityTimeout,
      waitTimeSeconds = settings.waitTimeSeconds
    )

    val extensionSchedule     = extensionSettings.schedule(settings)
    val extensionInitialDelay = extensionSettings.initialDelay(settings)

    val pull: RIO[Sqs, Chunk[Message.ReadOnly]] = zio.aws.sqs.Sqs
      .receiveMessage(request)
      .mapError(_.toThrowable)
      .flatMap { response =>
        response.messages
          .filter(_.nonEmpty)
          .fold[RIO[Sqs, Chunk[Message.ReadOnly]]](ifEmpty = Exit.succeed(Chunk.empty[Message.ReadOnly])) { underlying =>
            val messages         = Chunk.from(underlying)
            val extensionProcess =
              ZIO.sleep(extensionInitialDelay) *> ZIO
                .when(extensionSettings.automaticExtension)(
                  extendMessageLifetimeBatch(queueUrl, messages, extensionSettings.maximumRetries).ignoreLogged
                )
                .repeat(extensionSchedule)

            // Note: Avoided using race in case the user supplies a finite extension schedule
            for {
              extensionFiber <- extensionProcess.fork
              _              <- process(messages).onExit(_ => extensionFiber.interrupt)
              result         <- deleteMessageBatch(queueUrl, messages)
            } yield result
          }
      }

    pull.repeatWhile(_.nonEmpty || !settings.stopWhenQueueEmpty).unit
  }

  /**
   * Extends the visibility timeout of a message to the specified number of seconds.
   * This is useful when you need to process a message and the time it takes to process it is longer than the visibility timeout.
   *
   * @param queueUrl
   * @param message
   * @param seconds
   */
  def extendMessageLifetime(queueUrl: String, message: Message.ReadOnly, seconds: Int): RIO[Sqs, Unit] =
    zio.aws.sqs.Sqs
      .changeMessageVisibility(
        ChangeMessageVisibilityRequest(
          queueUrl = queueUrl,
          receiptHandle = message.receiptHandle.getOrElse(""),
          visibilityTimeout = seconds
        )
      )
      .mapError(_.toThrowable)

  def extendMessageLifetimeBatch(
    queueUrl: String,
    messages: Chunk[Message.ReadOnly],
    seconds: Int,
    maximumRetries: Int = 8
  ): RIO[Sqs, Chunk[Message.ReadOnly]] = {
    val idToMessageMap = messages.zipWithIndex.map { case (msg, id) => id.toString -> msg }.toMap

    def go(entries: Chunk[ChangeMessageVisibilityBatchRequestEntry], retriesRemaining: Int): ZIO[Sqs, AwsError, Chunk[Message.ReadOnly]] =
      zio.aws.sqs.Sqs
        .changeMessageVisibilityBatch(
          ChangeMessageVisibilityBatchRequest(
            queueUrl = queueUrl,
            entries = entries
          )
        )
        .flatMap { response =>
          if (response.failed.nonEmpty) {
            // Since we only get the ids back of messages that failed, we use the map to obtain the original message to retry
            val failedIds       = response.failed.map(_.id)
            val messagesToRetry =
              failedIds.map(id => ChangeMessageVisibilityBatchRequestEntry(id, idToMessageMap(id).receiptHandle.getOrElse(""), Option(seconds)))

            val errorMessage = ZIO.logWarning("Failed to change message visibility") @@ ZIOAspect.annotated("ids", failedIds.mkString("[", ", ", "]"))
            val retry        =
              if (retriesRemaining > 0) go(Chunk.from(messagesToRetry), retriesRemaining - 1)
              else ZIO.fail(GenericAwsError(new RuntimeException("Failed to change message visibility after retrying")))

            errorMessage *> retry
          } else Exit.succeed(Chunk.from(response.successful.map(each => idToMessageMap(each.id))))
        }

    ZStream
      .from(Chunk.from(idToMessageMap))
      .map { case (id, msg) => ChangeMessageVisibilityBatchRequestEntry(id, msg.receiptHandle.getOrElse(""), Option(seconds)) }
      .rechunk(10) // max batch size for changeMessageVisibilityBatch is 10
      .mapChunksZIO(go(_, maximumRetries))
      .mapError(_.toThrowable)
      .runCollect
  }

  def deleteMessage(queueUrl: String, msg: Message.ReadOnly): RIO[Sqs, Unit] =
    zio.aws.sqs.Sqs.deleteMessage(DeleteMessageRequest(queueUrl, msg.receiptHandle.getOrElse(""))).mapError(_.toThrowable)

  /**
   * Deletes a batch of messages from the queue. This method retries deleting messages that fail and internally uses the message IDs to retry.
   * It also chunks up the messages into batches of 10 before deleting them to avoid hitting the SQS limit of 10 messages per deleteMessageBatch call.
   *
   * @param queueUrl
   * @param msgs
   */
  def deleteMessageBatch(queueUrl: String, msgs: Chunk[Message.ReadOnly], maximumRetries: Int = 8): RIO[Sqs, Chunk[Message.ReadOnly]] = {
    // We need to keep track of the original message IDs to retry later
    // IDs are unique per batch, we use the index of the messages in the batch to generate a unique ID for each message
    val idMessageMap = msgs.zipWithIndex.map { case (msg, id) => id.toString -> msg }.toMap

    def go(entries: Chunk[DeleteMessageBatchRequestEntry], retriesRemaining: Int): ZIO[Sqs, AwsError, Chunk[Message.ReadOnly]] =
      zio.aws.sqs.Sqs
        .deleteMessageBatch(
          DeleteMessageBatchRequest(
            queueUrl = queueUrl,
            entries = entries
          )
        )
        .flatMap { response =>
          if (response.failed.nonEmpty) {
            // Since we only get the ids back of messages that failed, we use the map to obtain the original message to retry
            val failedIds       = response.failed.map(_.id)
            val messagesToRetry = failedIds.map(id => DeleteMessageBatchRequestEntry(id, idMessageMap(id).receiptHandle.getOrElse("")))
            val errorMessage    = ZIO.logWarning("Failed to delete messages, retrying") @@ ZIOAspect.annotated("ids", failedIds.mkString("[", ", ", "]"))
            val retry           =
              if (retriesRemaining > 0) go(Chunk.from(messagesToRetry), retriesRemaining - 1)
              else ZIO.fail(GenericAwsError(new RuntimeException("Failed to delete messages after retrying")))

            errorMessage *> retry
          } else
            Exit.succeed(
              Chunk.from(response.successful.map(each => idMessageMap(each.id)))
            )
        }

    ZStream
      .from(Chunk.from(idMessageMap))
      .map { case (id, msg) => DeleteMessageBatchRequestEntry(id, msg.receiptHandle.getOrElse("")) }
      .rechunk(10) // SQS Limit for deleteMessageBatch is 10
      .mapChunksZIO(go(_, maximumRetries))
      .mapError(_.toThrowable)
      .runCollect
  }
}
