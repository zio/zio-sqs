package zio.sqs.producer

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import zio.aws.core.{ aspects, AwsError }
import zio.aws.sqs.model._
import zio.aws.sqs.{ model, Sqs }
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import zio.durationInt
import zio.sqs.ZioSqsMockServer._
import zio.sqs.producer.Producer.{ DefaultProducer, SqsRequest, SqsRequestEntry, SqsResponseErrorEntry }
import zio.sqs.serialization.Serializer
import zio.sqs.{ Util, Utils }
import zio.stream.{ Sink, Stream, ZStream }
import zio.test.Assertion._
import zio.test._
import zio.test.{ Live, TestClock, TestEnvironment }
import zio.{ test => _, _ }

import scala.language.implicitConversions

object ProducerSpec extends DefaultRunnableSpec {
  implicit def batchResultErrorEntryAsReadOnly(e: BatchResultErrorEntry): BatchResultErrorEntry.ReadOnly          = BatchResultErrorEntry.wrap(e.buildAwsValue())
  implicit def sendMessageBatchResponseAsReadOnly(e: SendMessageBatchResponse): SendMessageBatchResponse.ReadOnly =
    SendMessageBatchResponse.wrap(e.buildAwsValue())

  def spec: ZSpec[TestEnvironment, Any] =
    suite("Producer")(
      test("nextPower2 can be calculated") {
        assert(Producer.nextPower2(0))(equalTo(0)) &&
        assert(Producer.nextPower2(1))(equalTo(1)) &&
        assert(Producer.nextPower2(2))(equalTo(2)) &&
        assert(Producer.nextPower2(9))(equalTo(16)) &&
        assert(Producer.nextPower2(129))(equalTo(256)) &&
        assert(Producer.nextPower2(257))(equalTo(512))
      },
      test("SqsRequestEntry can be created") {
        val attr = MessageAttributeValue(Some("Bob"), dataType = "String")

        val pe = ProducerEvent(
          data = "A",
          attributes = Map("Name" -> attr),
          groupId = Some("g1"),
          deduplicationId = Some("d1")
        )

        for {
          done        <- Promise.make[Throwable, ErrorOrEvent[String]]
          requestEntry = SqsRequestEntry(pe, done, 10)
          isDone      <- requestEntry.done.isDone
        } yield assert(requestEntry.event)(equalTo(pe)) &&
          assert(isDone)(isFalse) &&
          assert(requestEntry.retryCount)(equalTo(10))
      },
      test("SqsRequest can be created") {
        val attr = MessageAttributeValue(Some("Bob"), dataType = "String")

        val pe = ProducerEvent(
          data = "A",
          attributes = Map("Name" -> attr),
          groupId = Some("g1"),
          deduplicationId = Some("d1")
        )

        val batchRequestEntry = SendMessageBatchRequestEntry(id = "1", messageBody = "{}")

        val batchReq = SendMessageBatchRequest(queueUrl = "queueUrl", entries = List(batchRequestEntry))

        for {
          done        <- Promise.make[Throwable, ErrorOrEvent[String]]
          requestEntry = SqsRequestEntry(pe, done, 10)
          request      = SqsRequest(batchReq, List(requestEntry))
        } yield assert(request.inner)(equalTo(batchReq)) &&
          assert(request.entries)(equalTo(List(requestEntry)))
      },
      test("SqsResponseErrorEntry can be created") {
        val event    = ProducerEvent("e1")
        val errEntry = BatchResultErrorEntry(id = "id1", senderFault = true, code = "code2", message = Some("message3"))

        val eventError = ProducerError(errEntry, event)

        for {
          done    <- Promise.make[Throwable, ErrorOrEvent[String]]
          errEntry = SqsResponseErrorEntry(done, eventError)
          isDone  <- errEntry.done.isDone
        } yield assert(errEntry.error)(equalTo(eventError)) &&
          assert(isDone)(isFalse)
      },
      test("SendMessageBatchResponse can be partitioned") {
        val retryMaxCount = 10
        val rs            = Range(0, 4).toList
        val ids           = rs.map(_.toString)
        val bodies        = rs.map(_ + 'A').map(_.toChar.toString)
        val retries       = List(1, 2, retryMaxCount, 3)
        for {
          dones                          <- ZIO.foreach(Range(0, 4).toList)(_ => Promise.make[Throwable, ErrorOrEvent[String]])
          requestEntries                  = bodies.zip(dones).zip(retries).map { case ((a, b), c) => SqsRequestEntry(ProducerEvent(a), b, c) }
          m                               = ids.zip(requestEntries).toMap
          resultEntry0                    = SendMessageBatchResultEntry("0", "", "")
          errorEntry1                     = BatchResultErrorEntry(id = "1", senderFault = false, code = "ServiceUnavailable")
          errorEntry2                     = BatchResultErrorEntry(id = "2", senderFault = false, code = "ThrottlingException")
          errorEntry3                     = BatchResultErrorEntry(id = "3", senderFault = false, code = "AccessDeniedException")
          res                             = SendMessageBatchResponse(successful = Seq(resultEntry0), failed = Seq(errorEntry1, errorEntry2, errorEntry3))
          partitioner                     = Producer.partitionResponse(m, retryMaxCount) _
          (successful, retryable, errors) = partitioner(res)
        } yield assert(successful.size)(equalTo(1)) &&
          assert(retryable.size)(equalTo(1)) &&
          assert(errors.size)(equalTo(2))
      },
      test("SendMessageBatchResponse can be partitioned and mapped") {
        val retryMaxCount = 10
        val rs            = Range(0, 4).toList
        val ids           = rs.map(_.toString)
        val bodies        = rs.map(_ + 'A').map(_.toChar.toString)
        val retries       = List(1, 2, retryMaxCount, 3)
        for {
          dones         <- ZIO.foreach(Range(0, 4).toList)(_ => Promise.make[Throwable, ErrorOrEvent[String]])
          requestEntries = bodies.zip(dones).zip(retries).map { case ((a, b), c) => SqsRequestEntry(ProducerEvent(a), b, c) }
          m              = ids.zip(requestEntries).toMap

          resultEntry0                                         = SendMessageBatchResultEntry("0", "", "")
          errorEntry1                                          = BatchResultErrorEntry(id = "1", senderFault = false, code = "ServiceUnavailable")
          errorEntry2                                          = BatchResultErrorEntry(id = "2", senderFault = false, code = "ThrottlingException")
          errorEntry3                                          = BatchResultErrorEntry(id = "3", senderFault = false, code = "AccessDeniedException")
          res                                                  = SendMessageBatchResponse(successful = Seq(resultEntry0), failed = Seq(errorEntry1, errorEntry2, errorEntry3))
          partitioner                                          = Producer.partitionResponse(m, retryMaxCount) _
          (successful, retryable, errors)                      = partitioner(res)
          mapper                                               = Producer.mapResponse(m) _
          (successfulEntries, retryableEntries, errorsEntries) = mapper(successful, retryable, errors)
        } yield assert(successful.toList.size)(equalTo(1)) &&
          assert(retryable.size)(equalTo(1)) &&
          assert(errors.size)(equalTo(2)) &&
          assert(successfulEntries.toList.size)(equalTo(1)) &&
          assert(retryableEntries.toList.size)(equalTo(1)) &&
          assert(errorsEntries.toList.size)(equalTo(2)) &&
          assert(successfulEntries.toList.map(_.event.data))(hasSameElements(List("A"))) &&
          assert(retryableEntries.toList.map(_.event.data))(hasSameElements(List("B"))) &&
          assert(errorsEntries.toList.map(_.error.event.data))(hasSameElements(List("C", "D")))
      },
      test("buildSendMessageBatchRequest creates a new request") {
        val queueUrl = "sqs://queue"

        val attr = MessageAttributeValue(Some("Bob"), dataType = "String")

        val events = List(
          ProducerEvent(
            data = "A",
            attributes = Map("Name" -> attr),
            groupId = Some("g1"),
            deduplicationId = Some("d1")
          ),
          ProducerEvent(
            data = "B",
            attributes = Map.empty[String, MessageAttributeValue],
            groupId = Some("g2"),
            deduplicationId = Some("d2")
          )
        )

        for {
          reqEntries <- ZIO.foreach(events) { event =>
                          for {
                            done <- Promise.make[Throwable, ErrorOrEvent[String]]
                          } yield SqsRequestEntry[String](event, done, 0)
                        }
        } yield {
          val req = Producer.buildSendMessageBatchRequest[String](queueUrl, Serializer.serializeString)(reqEntries)

          val innerReq        = req.inner
          val innerReqEntries = req.inner.entries.toList

          assert(req.entries)(equalTo(reqEntries)) &&
          assert(innerReq.entries.nonEmpty)(isTrue) &&
          assert(innerReqEntries.size)(equalTo(2)) &&
          assert(innerReqEntries.head.id)(equalTo("0")) &&
          assert(innerReqEntries.head.messageBody)(equalTo("A")) &&
          assert(innerReqEntries.head.messageAttributes.getOrElse(Map.empty).size)(equalTo(1)) &&
          assert(innerReqEntries.head.messageAttributes.getOrElse(Map.empty).contains("Name"))(isTrue) &&
          assert(innerReqEntries.head.messageAttributes.getOrElse(Map.empty)("Name"))(equalTo(attr)) &&
          assert(innerReqEntries.head.messageGroupId)(isSome(equalTo("g1"))) &&
          assert(innerReqEntries.head.messageDeduplicationId)(isSome(equalTo("d1"))) &&
          assert(innerReqEntries(1).id)(equalTo("1")) &&
          assert(innerReqEntries(1).messageBody)(equalTo("B")) &&
          assert(innerReqEntries(1).messageAttributes.getOrElse(Map.empty).size)(equalTo(0)) &&
          assert(innerReqEntries(1).messageGroupId)(isSome(equalTo("g2"))) &&
          assert(innerReqEntries(1).messageDeduplicationId)(isSome(equalTo("d2")))
        }
      },
      test("runSendMessageBatchRequest can be executed") {
        val queueName                  = "runSendMessageBatchRequest-" + UUID.randomUUID().toString
        val settings: ProducerSettings = ProducerSettings()
        val eventCount                 = settings.batchSize
        for {
          events     <- Util.chunkOfStringsN(eventCount)
                          .sample
                          .map(_.get.value.map(ProducerEvent(_)))
                          .run(Sink.head[Chunk[ProducerEvent[String]]])
                          .someOrFailException
          retryQueue <- queueResource(16)
          dones      <- serverResource.useDiscard {
                          retryQueue.use {
                            q =>
                              for {
                                _          <- Utils.createQueue(queueName)
                                queueUrl   <- Utils.getQueueUrl(queueName)
                                reqEntries <- ZIO.foreach(events) { event =>
                                                for {
                                                  done <- Promise.make[Throwable, ErrorOrEvent[String]]
                                                } yield SqsRequestEntry[String](event, done, 0)
                                              }
                                req         = Producer.buildSendMessageBatchRequest[String](queueUrl, Serializer.serializeString)(reqEntries.toList)
                                retryDelay  = 1.millisecond
                                retryCount  = 1
                                reqSender   = Producer.runSendMessageBatchRequest(q, retryDelay, retryCount) _
                                _          <- reqSender(req)
                              } yield ZIO.foreach(reqEntries)(entry => entry.done.await)
                          }
                        }
          isAllRight <- dones.map(_.forall(_.isRight))
        } yield assert(isAllRight)(isTrue)
      },
      test("events can be published using sendStream and return the results") {
        val queueName                  = "sendStream-" + UUID.randomUUID().toString
        val settings: ProducerSettings = ProducerSettings()
        val eventCount                 = (settings.batchSize * 2) + 3

        for {
          events  <- Util
                       .chunkOfStringsN(eventCount)
                       .sample
                       .map(_.get.value.map(ProducerEvent(_)))
                       .run(Sink.head[Chunk[ProducerEvent[String]]])
                       .someOrFailException
          results <- serverResource.useDiscard {
                       for {
                         _           <- withFastClock.fork
                         _           <- Utils.createQueue(queueName)
                         queueUrl    <- Utils.getQueueUrl(queueName)
                         producer     = Producer.make(queueUrl, Serializer.serializeString, settings)
                         resultQueue <- Queue.unbounded[ErrorOrEvent[String]]
                         _           <- producer.use { p =>
                                          p.sendStreamE(Stream(events: _*))
                                            .foreach(resultQueue.offer) // replace with .via when ZIO > RC17 is released -- Sink.collectAll[SqsPublishErrorOrResult]
                                        }.fork
                         results     <- ZIO.collectAll(List.fill(eventCount)(resultQueue.take))
                       } yield results
                     }
        } yield assert(results.size)(equalTo(events.size)) &&
          assert(results.forall(_.isRight))(isTrue)
      },
      test("events can be published using sendStream and fail the task on error") {
        val queueName                  = "produce-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("P1").map(ProducerEvent(_))
        val client                     = failUnrecoverableClient

        for {
          _           <- withFastClock.fork
          producer     = Producer.make(queueUrl, Serializer.serializeString, settings).provideCustomLayer(client)
          errOrResult <- producer.use(p => p.sendStream(Stream(events: _*)).runDrain.either)
        } yield assert(errOrResult.isLeft)(isTrue)
      },
      test("events can be published using produce and return the results") {
        val queueName                  = "produce-" + UUID.randomUUID().toString
        val settings: ProducerSettings = ProducerSettings()
        val eventCount                 = settings.batchSize

        for {
          events  <- Util
                       .chunkOfStringsN(eventCount)
                       .sample
                       .map(_.get.value.map(ProducerEvent(_)))
                       .run(Sink.head[Chunk[ProducerEvent[String]]])
                       .someOrFailException
          results <- serverResource.useDiscard {
                       for {
                         _        <- withFastClock.fork
                         _        <- Utils.createQueue(queueName)
                         queueUrl <- Utils.getQueueUrl(queueName)
                         producer  = Producer.make(queueUrl, Serializer.serializeString, settings)
                         results  <- producer.use(p => ZIO.foreachPar(events)(event => p.asInstanceOf[DefaultProducer[String]].produceE(event)))
                       } yield results
                     }
        } yield assert(results.size)(equalTo(events.size)) &&
          assert(results.forall(_.isRight))(isTrue)
      },
      test("events can be pushed using produce and fail the task on error") {
        val queueName                  = "produce-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A").map(ProducerEvent(_))
        val client                     = failUnrecoverableClient

        for {
          _            <- withFastClock.fork
          producer      = Producer.make(queueUrl, Serializer.serializeString, settings).provideCustomLayer(client)
          errOrResults <- producer.use(p => ZIO.foreachPar(events)(event => p.produce(event))).either
        } yield assert(errOrResults.isLeft)(isTrue)
      },
      test("events can be published using produceBatch and return the results") {
        val queueName                  = "produceBatch-" + UUID.randomUUID().toString
        val settings: ProducerSettings = ProducerSettings()
        val eventCount                 = settings.batchSize * 2

        for {
          events  <- Util
                       .chunkOfStringsN(eventCount)
                       .sample
                       .map(_.get.value.map(ProducerEvent(_)))
                       .run(Sink.head[Chunk[ProducerEvent[String]]])
                       .someOrFailException
          results <- serverResource.useDiscard {
                       for {
                         _        <- withFastClock.fork
                         _        <- Utils.createQueue(queueName)
                         queueUrl <- Utils.getQueueUrl(queueName)
                         producer <- Task.succeed(Producer.make(queueUrl, Serializer.serializeString, settings))
                         results  <- producer.use(p => p.produceBatchE(events))
                       } yield results
                     }
        } yield assert(results.size)(equalTo(events.size)) &&
          assert(results.forall(_.isRight))(isTrue)
      },
      test("events can be published using produceBatch and fail the task on error") {
        val queueName                  = "produceBatch-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("B1").map(ProducerEvent(_))
        val client                     = failUnrecoverableClient

        for {
          _            <- withFastClock.fork
          producer      = Producer.make(queueUrl, Serializer.serializeString, settings).provideCustomLayer(client)
          errOrResults <- producer.use(p => p.produceBatch(events)).either
        } yield assert(errOrResults.isLeft)(isTrue)
      },
      test("events can be published using sendSink") {
        val queueName                  = "sendSink-" + UUID.randomUUID().toString
        val settings: ProducerSettings = ProducerSettings()
        val eventCount                 = settings.batchSize

        for {
          events  <- Util
                       .chunkOfStringsN(eventCount)
                       .sample
                       .map(_.get.value.map(ProducerEvent(_)))
                       .run(Sink.head[Chunk[ProducerEvent[String]]])
                       .someOrFailException
          results <- serverResource.useDiscard {
                       for {
                         _        <- withFastClock.fork
                         _        <- Utils.createQueue(queueName)
                         queueUrl <- Utils.getQueueUrl(queueName)
                         producer  = Producer.make(queueUrl, Serializer.serializeString, settings)
                         results  <- producer.use(p => Stream.succeed(events).run(p.sendSink))
                       } yield results
                     }
        } yield assert(results)(equalTo(()))
      },
      test("events that published using sendSink and generate an exception on send should fail the sink") {
        val queueName                  = "sendSink-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A").map(ProducerEvent(_))

        val client: ULayer[Sqs] = ZLayer.succeed {
          new StubSqsService {
            override def sendMessageBatch(
              request: SendMessageBatchRequest
            ): IO[AwsError, SendMessageBatchResponse.ReadOnly] =
              IO.fail(AwsError.fromThrowable(new RuntimeException("network failure")))
          }
        }

        for {
          _            <- withFastClock.fork
          producer      = Producer.make(queueUrl, Serializer.serializeString, settings).provideCustomLayer(client)
          errOrResults <- producer.use(p => Stream.succeed(events).run(p.sendSink)).either
        } yield assert(errOrResults.isLeft)(isTrue)
      },
      test("events that published using sendSink and return an unrecoverable error should fail the sink on error") {
        val queueName                  = "sendSink-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A").map(ProducerEvent(_))
        val client                     = failUnrecoverableClient

        for {
          _            <- withFastClock.fork
          producer      = Producer.make(queueUrl, Serializer.serializeString, settings).provideCustomLayer(client)
          errOrResults <- producer.use(p => Stream.succeed(events).run(p.sendSink)).either
        } yield assert(errOrResults.isLeft)(isTrue)
      },
      test("submitted events can succeed and fail if there are unrecoverable errors") {
        val queueName                  = "success-and-unrecoverable-failures-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A", "B", "C").map(ProducerEvent(_))

        val client: ULayer[Sqs] = ZLayer.succeed {
          new StubSqsService {
            override def sendMessageBatch(
              request: SendMessageBatchRequest
            ): IO[AwsError, SendMessageBatchResponse.ReadOnly] = {

              val batchRequestEntries                                       = request.entries
              val (batchRequestEntriesToSucceed, batchRequestEntriesToFail) = batchRequestEntries.partition(_.messageBody == "A")

              val resultEntries = batchRequestEntriesToSucceed.map(entry => SendMessageBatchResultEntry(id = entry.id, messageId = "", md5OfMessageBody = ""))

              val errorEntries = batchRequestEntriesToFail.map { entry =>
                BatchResultErrorEntry(id = entry.id, senderFault = false, code = "AccessDeniedException")
              }.toList

              val res = SendMessageBatchResponse(successful = resultEntries, failed = errorEntries)
              IO.succeed(res)
            }
          }
        }

        for {
          _       <- withFastClock.fork
          producer = Producer.make(queueUrl, Serializer.serializeString, settings).provideCustomLayer(client)
          results <- producer.use(p => p.produceBatchE(events))
        } yield {
          val successes = results.filter(_.isRight).collect {
            case Right(x) => x.data
          }

          val failures = results.filter(_.isLeft).collect {
            case Left(x) => x.event.data
          }

          assert(results.size)(equalTo(events.size)) &&
          assert(successes)(hasSameElements(List("A"))) &&
          assert(failures)(hasSameElements(List("B", "C")))
        }
      },
      test("submitted events can be republished if there are recoverable errors") {
        val queueName                  = "success-and-recoverable-failures-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A", "B", "C").map(ProducerEvent(_))

        val invokeCount         = new AtomicInteger(0)
        val client: ULayer[Sqs] = ZLayer.succeed {
          new StubSqsService {
            override def sendMessageBatch(
              request: SendMessageBatchRequest
            ): IO[AwsError, SendMessageBatchResponse.ReadOnly] =
              IO.succeed {
                invokeCount.incrementAndGet()

                val batchRequestEntries                                       = request.entries
                val (batchRequestEntriesToSucceed, batchRequestEntriesToFail) = batchRequestEntries.splitAt(2)

                val resultEntries = batchRequestEntriesToSucceed.map(entry => SendMessageBatchResultEntry(entry.id, "", ""))

                val errorEntries = batchRequestEntriesToFail.map { entry =>
                  BatchResultErrorEntry(id = entry.id, senderFault = false, code = "ServiceUnavailable")
                }.toList

                SendMessageBatchResponse(resultEntries, errorEntries)
              }
          }
        }

        for {
          _       <- withFastClock.fork
          producer = Producer.make(queueUrl, Serializer.serializeString, settings).provideCustomLayer(client)
          results <- producer.use(p => p.produceBatchE(events))
        } yield {
          val successes = results.filter(_.isRight).collect {
            case Right(x) => x.data
          }

          assert(results.size)(equalTo(events.size)) &&
          assert(successes)(hasSameElements(List("A", "B", "C"))) &&
          assert(invokeCount.get())(isGreaterThanEqualTo(2))
        }
      },
      test("if the number of recoverable retries exceeds the limit, messages fail") {
        val queueName                  = "fail-when-retry-limit-reached-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A", "B", "C").map(ProducerEvent(_))

        val invokeCount = new AtomicInteger(0)

        val client: ULayer[Sqs] = ZLayer.succeed {
          new StubSqsService {
            override def sendMessageBatch(request: SendMessageBatchRequest): IO[AwsError, SendMessageBatchResponse.ReadOnly] =
              ZIO.succeed {
                val batchRequestEntriesToFail = request.entries

                invokeCount.addAndGet(batchRequestEntriesToFail.size)

                val errorEntries = batchRequestEntriesToFail.map { entry =>
                  BatchResultErrorEntry(id = entry.id, senderFault = false, code = "ServiceUnavailable")
                }.toList

                sendMessageBatchResponseAsReadOnly(
                  SendMessageBatchResponse(successful = Seq.empty, failed = errorEntries)
                )
              }
          }
        }

        for {
          _       <- withFastClock.fork
          producer = Producer.make(queueUrl, Serializer.serializeString, settings).provideCustomLayer(client)
          results <- producer.use(p => p.produceBatchE(events))
        } yield {
          val failures = results.filter(_.isLeft).collect {
            case Left(x) => x.event.data
          }

          assert(results.size)(equalTo(events.size)) &&
          assert(failures)(hasSameElements(List("A", "B", "C"))) &&
          assert(invokeCount.get())(equalTo((settings.retryMaxCount + 1) * events.size))
        }
      },
      test("a SendMessageBatchRequest failed with an exception should fail") {
        val queueName                  = "fail-with-exception-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A").map(ProducerEvent(_))

        val invokeCount         = new AtomicInteger(0)
        val client: ULayer[Sqs] = ZLayer.succeed {
          new StubSqsService {
            override def sendMessageBatch(request: SendMessageBatchRequest): IO[AwsError, SendMessageBatchResponse.ReadOnly] =
              ZIO.succeed(invokeCount.addAndGet(1)) *>
                ZIO.fail(AwsError.fromThrowable(new RuntimeException("unexpected failure")))
          }
        }

        for {
          _            <- withFastClock.fork
          producer      = Producer.make(queueUrl, Serializer.serializeString, settings).provideCustomLayer(client)
          errOrResults <- producer.use(p => p.produceBatchE(events)).either
        } yield assert(errOrResults.isLeft)(isTrue)
      },
      test("several SendMessageBatchRequest failed with an exception should not stop the producer (#456)") {
        val queueName                  = "fail-with-exception-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings(batchSize = 1, parallelism = 1, duration = 1.milliseconds)
        val events                     = List("A", "B", "C", "D", "E").map(ProducerEvent(_))

        val client: ULayer[Sqs] = ZLayer.succeed {
          new StubSqsService {
            override def sendMessageBatch(request: SendMessageBatchRequest): IO[AwsError, SendMessageBatchResponse.ReadOnly] =
              if (request.entries.isEmpty || List("C", "E").contains(request.entries.head.messageBody))
                ZIO.succeed {
                  val batchRequestEntries = request.entries
                  val resultEntries       = batchRequestEntries.map(entry => SendMessageBatchResultEntry(entry.id, "", ""))

                  SendMessageBatchResponse(resultEntries, Seq.empty)
                }
              else
                ZIO.fail(AwsError.fromThrowable(new RuntimeException("unexpected failure")))
          }
        }

        for {
          _       <- withFastClock.fork
          producer = Producer.make(queueUrl, Serializer.serializeString, settings).provideCustomLayer(client)
          results <- producer.use(p => ZIO.foreach(events)(e => ZIO.sleep(100.milliseconds) *> p.produce(e).either))
        } yield {
          val (failures, successes) = results.partition(_.isLeft)
          assert(successes.collect({ case Right(x) => x.data }))(hasSameElements(List("C", "E"))) &&
          assert(failures.size)(equalTo(3))
        }
      }
    ).provideCustomLayerShared((zio.aws.netty.NettyHttpClient.default >>> zio.aws.core.config.AwsConfig.default >>> clientResource).orDie)

  override def aspects: List[TestAspect[Nothing, TestEnvironment, Nothing, Any]] =
    List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))

  def queueResource(capacity: Int): Task[ZManaged[Any, Throwable, Queue[SqsRequestEntry[String]]]] =
    Task.succeed {
      Queue.bounded[SqsRequestEntry[String]](capacity).toManagedWith(_.shutdown)
    }

  def withFastClock: ZIO[TestClock with Live, Nothing, Long] =
    Live.withLive(TestClock.adjust(1.seconds))(_.repeat[ZEnv, Long](Schedule.spaced(10.millis)))

  /**
   * A client that fails all incoming messages in the batch with unrecoverable error.
   */
  val failUnrecoverableClient: ZLayer[Any, Throwable, Sqs] = ZLayer.succeed {
    new StubSqsService {
      override def sendMessageBatch(request: SendMessageBatchRequest): IO[AwsError, SendMessageBatchResponse.ReadOnly] =
        ZIO.succeed {
          val batchRequestEntries = request.entries
          val errorEntries        = batchRequestEntries.map { entry =>
            BatchResultErrorEntry(id = entry.id, senderFault = false, code = "AccessDeniedException")
          }.toList

          SendMessageBatchResponse(successful = Seq.empty, failed = errorEntries)
        }
    }
  }
}

class StubSqsService extends Sqs {
  override lazy val api: SqsAsyncClient                                                                                                                      = ???
  override def getQueueAttributes(request: model.GetQueueAttributesRequest): IO[AwsError, GetQueueAttributesResponse.ReadOnly]                               = ???
  override def sendMessage(request: model.SendMessageRequest): IO[AwsError, SendMessageResponse.ReadOnly]                                                    = ???
  override def listQueues(request: model.ListQueuesRequest): ZStream[Any, AwsError, String]                                                                  = ???
  override def listQueuesPaginated(request: model.ListQueuesRequest): ZIO[Any, AwsError, ListQueuesResponse.ReadOnly]                                        = ???
  override def untagQueue(request: model.UntagQueueRequest): IO[AwsError, Unit]                                                                              = ???
  override def tagQueue(request: model.TagQueueRequest): IO[AwsError, Unit]                                                                                  = ???
  override def deleteMessage(request: model.DeleteMessageRequest): IO[AwsError, Unit]                                                                        = ???
  override def deleteMessageBatch(request: model.DeleteMessageBatchRequest): IO[AwsError, DeleteMessageBatchResponse.ReadOnly]                               = ???
  override def purgeQueue(request: model.PurgeQueueRequest): IO[AwsError, Unit]                                                                              = ???
  override def addPermission(request: model.AddPermissionRequest): IO[AwsError, Unit]                                                                        = ???
  override def listQueueTags(request: model.ListQueueTagsRequest): IO[AwsError, ListQueueTagsResponse.ReadOnly]                                              = ???
  override def createQueue(request: CreateQueueRequest): IO[AwsError, CreateQueueResponse.ReadOnly]                                                          = ???
  override def listDeadLetterSourceQueues(request: model.ListDeadLetterSourceQueuesRequest): ZStream[Any, AwsError, String]                                  = ???
  override def listDeadLetterSourceQueuesPaginated(
    request: model.ListDeadLetterSourceQueuesRequest
  ): ZIO[Any, AwsError, ListDeadLetterSourceQueuesResponse.ReadOnly]                                                                                         = ???
  override def getQueueUrl(request: GetQueueUrlRequest): IO[AwsError, GetQueueUrlResponse.ReadOnly]                                                          = ???
  override def removePermission(request: model.RemovePermissionRequest): IO[AwsError, Unit]                                                                  = ???
  override def receiveMessage(request: model.ReceiveMessageRequest): IO[AwsError, ReceiveMessageResponse.ReadOnly]                                           = ???
  override def setQueueAttributes(request: model.SetQueueAttributesRequest): IO[AwsError, Unit]                                                              = ???
  override def deleteQueue(request: model.DeleteQueueRequest): IO[AwsError, Unit]                                                                            = ???
  override def sendMessageBatch(request: SendMessageBatchRequest): IO[AwsError, SendMessageBatchResponse.ReadOnly]                                           = ???
  override def changeMessageVisibilityBatch(request: model.ChangeMessageVisibilityBatchRequest): IO[AwsError, ChangeMessageVisibilityBatchResponse.ReadOnly] =
    ???
  override def changeMessageVisibility(request: model.ChangeMessageVisibilityRequest): IO[AwsError, Unit]                                                    = ???
  override def withAspect[R](newAspect: aspects.AwsCallAspect[R], r: ZEnvironment[R]): Sqs                                                                   = ???
}
