package zio.sqs.producer

import scala.jdk.CollectionConverters._
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._
import zio.duration._
import zio.sqs.ZioSqsMockServer._
import zio.sqs.producer.Producer.{ DefaultProducer, SqsRequest, SqsRequestEntry, SqsResponseErrorEntry }
import zio.sqs.serialization.Serializer
import zio.sqs.{ Util, Utils }
import zio.stream.{ Sink, Stream }
import zio.test.Assertion._
import zio.test._
import zio.test.environment.{ Live, TestClock, TestEnvironment }
import zio.{ test => _, _ }

object ProducerSpec extends DefaultRunnableSpec {

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
      testM("SqsRequestEntry can be created") {
        val attr = MessageAttributeValue
          .builder()
          .dataType("String")
          .stringValue("Bob")
          .build()

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
      testM("SqsRequest can be created") {
        val attr = MessageAttributeValue
          .builder()
          .dataType("String")
          .stringValue("Bob")
          .build()

        val pe = ProducerEvent(
          data = "A",
          attributes = Map("Name" -> attr),
          groupId = Some("g1"),
          deduplicationId = Some("d1")
        )

        val batchRequestEntry = SendMessageBatchRequestEntry
          .builder()
          .id("1")
          .messageBody("{}")
          .build()

        val batchReq = SendMessageBatchRequest
          .builder()
          .queueUrl("queueUrl")
          .entries(List(batchRequestEntry).asJava)
          .build()

        for {
          done        <- Promise.make[Throwable, ErrorOrEvent[String]]
          requestEntry = SqsRequestEntry(pe, done, 10)
          request      = SqsRequest(batchReq, List(requestEntry))
        } yield assert(request.inner)(equalTo(batchReq)) &&
          assert(request.entries)(equalTo(List(requestEntry)))
      },
      testM("SqsResponseErrorEntry can be created") {
        val event    = ProducerEvent("e1")
        val errEntry =
          BatchResultErrorEntry.builder().id("id1").code("code2").message("message3").senderFault(true).build()

        val eventError = ProducerError(errEntry, event)

        for {
          done    <- Promise.make[Throwable, ErrorOrEvent[String]]
          errEntry = SqsResponseErrorEntry(done, eventError)
          isDone  <- errEntry.done.isDone
        } yield assert(errEntry.error)(equalTo(eventError)) &&
          assert(isDone)(isFalse)
      },
      testM("SendMessageBatchResponse can be partitioned") {
        val retryMaxCount = 10
        val rs            = Range(0, 4).toList
        val ids           = rs.map(_.toString)
        val bodies        = rs.map(_ + 'A').map(_.toChar.toString)
        val retries       = List(1, 2, retryMaxCount, 3)
        for {
          dones                          <- ZIO.foreach(Range(0, 4))(_ => Promise.make[Throwable, ErrorOrEvent[String]])
          requestEntries                  = bodies.zip(dones).zip(retries).map { case ((a, b), c) => SqsRequestEntry(ProducerEvent(a), b, c) }
          m                               = ids.zip(requestEntries).toMap
          resultEntry0                    = SendMessageBatchResultEntry.builder().id("0").build()
          errorEntry1                     = BatchResultErrorEntry.builder().id("1").code("ServiceUnavailable").senderFault(false).build()
          errorEntry2                     = BatchResultErrorEntry.builder().id("2").code("ThrottlingException").senderFault(false).build()
          errorEntry3                     = BatchResultErrorEntry.builder().id("3").code("AccessDeniedException").senderFault(false).build()
          res                             = SendMessageBatchResponse.builder()
                                              .successful(resultEntry0)
                                              .failed(errorEntry1, errorEntry2, errorEntry3)
                                              .build()
          partitioner                     = Producer.partitionResponse(m, retryMaxCount) _
          (successful, retryable, errors) = partitioner(res)
        } yield assert(successful.toList.size)(equalTo(1)) &&
          assert(retryable.toList.size)(equalTo(1)) &&
          assert(errors.toList.size)(equalTo(2))
      },
      testM("SendMessageBatchResponse can be partitioned and mapped") {
        val retryMaxCount = 10
        val rs            = Range(0, 4).toList
        val ids           = rs.map(_.toString)
        val bodies        = rs.map(_ + 'A').map(_.toChar.toString)
        val retries       = List(1, 2, retryMaxCount, 3)
        for {
          dones                                               <- ZIO.foreach(Range(0, 4))(_ => Promise.make[Throwable, ErrorOrEvent[String]])
          requestEntries                                       = bodies.zip(dones).zip(retries).map { case ((a, b), c) => SqsRequestEntry(ProducerEvent(a), b, c) }
          m                                                    = ids.zip(requestEntries).toMap
          resultEntry0                                         = SendMessageBatchResultEntry.builder().id("0").build()
          errorEntry1                                          = BatchResultErrorEntry.builder().id("1").code("ServiceUnavailable").senderFault(false).build()
          errorEntry2                                          = BatchResultErrorEntry.builder().id("2").code("ThrottlingException").senderFault(false).build()
          errorEntry3                                          = BatchResultErrorEntry.builder().id("3").code("AccessDeniedException").senderFault(false).build()
          res                                                  = SendMessageBatchResponse.builder()
                                                                   .successful(resultEntry0)
                                                                   .failed(errorEntry1, errorEntry2, errorEntry3)
                                                                   .build()
          partitioner                                          = Producer.partitionResponse(m, retryMaxCount) _
          (successful, retryable, errors)                      = partitioner(res)
          mapper                                               = Producer.mapResponse(m) _
          (successfulEntries, retryableEntries, errorsEntries) = mapper(successful, retryable, errors)
        } yield assert(successful.toList.size)(equalTo(1)) &&
          assert(retryable.toList.size)(equalTo(1)) &&
          assert(errors.toList.size)(equalTo(2)) &&
          assert(successfulEntries.toList.size)(equalTo(1)) &&
          assert(retryableEntries.toList.size)(equalTo(1)) &&
          assert(errorsEntries.toList.size)(equalTo(2)) &&
          assert(successfulEntries.toList.map(_.event.data))(hasSameElements(List("A"))) &&
          assert(retryableEntries.toList.map(_.event.data))(hasSameElements(List("B"))) &&
          assert(errorsEntries.toList.map(_.error.event.data))(hasSameElements(List("C", "D")))
      },
      testM("buildSendMessageBatchRequest creates a new request") {
        val queueUrl = "sqs://queue"

        val attr = MessageAttributeValue
          .builder()
          .dataType("String")
          .stringValue("Bob")
          .build()

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
          val innerReqEntries = req.inner.entries().asScala

          assert(req.entries)(equalTo(reqEntries)) &&
          assert(innerReq.hasEntries)(isTrue) &&
          assert(innerReqEntries.size)(equalTo(2)) &&
          assert(innerReqEntries.head.id())(equalTo("0")) &&
          assert(innerReqEntries.head.messageBody())(equalTo("A")) &&
          assert(innerReqEntries.head.messageAttributes().size())(equalTo(1)) &&
          assert(innerReqEntries.head.messageAttributes().asScala.contains("Name"))(isTrue) &&
          assert(innerReqEntries.head.messageAttributes().asScala("Name"))(equalTo(attr)) &&
          assert(Option(innerReqEntries.head.messageGroupId()))(isSome(equalTo("g1"))) &&
          assert(Option(innerReqEntries.head.messageDeduplicationId()))(isSome(equalTo("d1"))) &&
          assert(innerReqEntries(1).id())(equalTo("1")) &&
          assert(innerReqEntries(1).messageBody())(equalTo("B")) &&
          assert(innerReqEntries(1).messageAttributes().size())(equalTo(0)) &&
          assert(Option(innerReqEntries(1).messageGroupId()))(isSome(equalTo("g2"))) &&
          assert(Option(innerReqEntries(1).messageDeduplicationId()))(isSome(equalTo("d2")))
        }
      },
      testM("runSendMessageBatchRequest can be executed") {
        val queueName                  = "runSendMessageBatchRequest-" + UUID.randomUUID().toString
        val settings: ProducerSettings = ProducerSettings()
        val eventCount                 = settings.batchSize
        for {
          events     <- Util.chunkOfStringsN(eventCount)
                          .sample
                          .map(_.value.map(ProducerEvent(_)))
                          .run(Sink.head[Chunk[ProducerEvent[String]]])
                          .someOrFailException
          server     <- serverResource
          client     <- clientResource
          retryQueue <- queueResource(16)
          dones      <- server.use {
                          _ =>
                            client.use {
                              c =>
                                retryQueue.use {
                                  q =>
                                    for {
                                      _          <- Utils.createQueue(c, queueName)
                                      queueUrl   <- Utils.getQueueUrl(c, queueName)
                                      reqEntries <- ZIO.foreach(events) { event =>
                                                      for {
                                                        done <- Promise.make[Throwable, ErrorOrEvent[String]]
                                                      } yield SqsRequestEntry[String](event, done, 0)
                                                    }
                                      req         = Producer.buildSendMessageBatchRequest[String](queueUrl, Serializer.serializeString)(reqEntries.toList)
                                      retryDelay  = 1.millisecond
                                      retryCount  = 1
                                      reqSender   = Producer.runSendMessageBatchRequest(c, q, retryDelay, retryCount) _
                                      _          <- reqSender(req)
                                    } yield ZIO.foreach(reqEntries)(entry => entry.done.await)
                                }
                            }
                        }
          isAllRight <- dones.map(_.forall(_.isRight))
        } yield assert(isAllRight)(isTrue)
      },
      testM("events can be published using sendStream and return the results") {
        val queueName                  = "sendStream-" + UUID.randomUUID().toString
        val settings: ProducerSettings = ProducerSettings()
        val eventCount                 = (settings.batchSize * 2) + 3

        for {
          events  <- Util
                       .chunkOfStringsN(eventCount)
                       .sample
                       .map(_.value.map(ProducerEvent(_)))
                       .run(Sink.head[Chunk[ProducerEvent[String]]])
                       .someOrFailException
          server  <- serverResource
          client  <- clientResource
          results <- server.use {
                       _ =>
                         client.use { c =>
                           for {
                             _           <- withFastClock.fork
                             _           <- Utils.createQueue(c, queueName)
                             queueUrl    <- Utils.getQueueUrl(c, queueName)
                             producer     = Producer.make(c, queueUrl, Serializer.serializeString, settings)
                             resultQueue <- Queue.unbounded[ErrorOrEvent[String]]
                             _           <- producer.use { p =>
                                              p.sendStreamE(Stream(events: _*))
                                                .foreach(resultQueue.offer) // replace with .via when ZIO > RC17 is released -- Sink.collectAll[SqsPublishErrorOrResult]
                                            }.fork
                             results     <- ZIO.collectAll(List.fill(eventCount)(resultQueue.take))
                           } yield results
                         }
                     }
        } yield assert(results.size)(equalTo(events.size)) &&
          assert(results.forall(_.isRight))(isTrue)
      },
      testM("events can be published using sendStream and fail the task on error") {
        val queueName                  = "produce-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("P1").map(ProducerEvent(_))
        val client                     = failUnrecoverableClient

        for {
          _           <- withFastClock.fork
          producer     = Producer.make(client, queueUrl, Serializer.serializeString, settings)
          errOrResult <- producer.use(p => p.sendStream(Stream(events: _*)).runDrain.either)
        } yield assert(errOrResult.isLeft)(isTrue)
      },
      testM("events can be published using produce and return the results") {
        val queueName                  = "produce-" + UUID.randomUUID().toString
        val settings: ProducerSettings = ProducerSettings()
        val eventCount                 = settings.batchSize

        for {
          events  <- Util
                       .chunkOfStringsN(eventCount)
                       .sample
                       .map(_.value.map(ProducerEvent(_)))
                       .run(Sink.head[Chunk[ProducerEvent[String]]])
                       .someOrFailException
          server  <- serverResource
          client  <- clientResource
          results <- server.use { _ =>
                       client.use { c =>
                         for {
                           _        <- withFastClock.fork
                           _        <- Utils.createQueue(c, queueName)
                           queueUrl <- Utils.getQueueUrl(c, queueName)
                           producer  = Producer.make(c, queueUrl, Serializer.serializeString, settings)
                           results  <- producer.use(p => ZIO.foreachPar(events)(event => p.asInstanceOf[DefaultProducer[String]].produceE(event)))
                         } yield results
                       }
                     }
        } yield assert(results.size)(equalTo(events.size)) &&
          assert(results.forall(_.isRight))(isTrue)
      },
      testM("events can be pushed using produce and fail the task on error") {
        val queueName                  = "produce-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A").map(ProducerEvent(_))
        val client                     = failUnrecoverableClient

        for {
          _            <- withFastClock.fork
          producer      = Producer.make(client, queueUrl, Serializer.serializeString, settings)
          errOrResults <- producer.use(p => ZIO.foreachPar(events)(event => p.produce(event))).either
        } yield assert(errOrResults.isLeft)(isTrue)
      },
      testM("events can be published using produceBatch and return the results") {
        val queueName                  = "produceBatch-" + UUID.randomUUID().toString
        val settings: ProducerSettings = ProducerSettings()
        val eventCount                 = settings.batchSize * 2

        for {
          events  <- Util
                       .chunkOfStringsN(eventCount)
                       .sample
                       .map(_.value.map(ProducerEvent(_)))
                       .run(Sink.head[Chunk[ProducerEvent[String]]])
                       .someOrFailException
          server  <- serverResource
          client  <- clientResource
          results <- server.use { _ =>
                       client.use { c =>
                         for {
                           _        <- withFastClock.fork
                           _        <- Utils.createQueue(c, queueName)
                           queueUrl <- Utils.getQueueUrl(c, queueName)
                           producer <- Task.succeed(Producer.make(c, queueUrl, Serializer.serializeString, settings))
                           results  <- producer.use(p => p.produceBatchE(events))
                         } yield results
                       }
                     }
        } yield assert(results.size)(equalTo(events.size)) &&
          assert(results.forall(_.isRight))(isTrue)
      },
      testM("events can be published using produceBatch and fail the task on error") {
        val queueName                  = "produceBatch-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("B1").map(ProducerEvent(_))
        val client                     = failUnrecoverableClient

        for {
          _            <- withFastClock.fork
          producer      = Producer.make(client, queueUrl, Serializer.serializeString, settings)
          errOrResults <- producer.use(p => p.produceBatch(events)).either
        } yield assert(errOrResults.isLeft)(isTrue)
      },
      testM("events can be published using sendSink") {
        val queueName                  = "sendSink-" + UUID.randomUUID().toString
        val settings: ProducerSettings = ProducerSettings()
        val eventCount                 = settings.batchSize

        for {
          events  <- Util
                       .chunkOfStringsN(eventCount)
                       .sample
                       .map(_.value.map(ProducerEvent(_)))
                       .run(Sink.head[Chunk[ProducerEvent[String]]])
                       .someOrFailException
          server  <- serverResource
          client  <- clientResource
          results <- server.use { _ =>
                       client.use { c =>
                         for {
                           _        <- withFastClock.fork
                           _        <- Utils.createQueue(c, queueName)
                           queueUrl <- Utils.getQueueUrl(c, queueName)
                           producer  = Producer.make(c, queueUrl, Serializer.serializeString, settings)
                           results  <- producer.use(p => Stream.succeed(events).run(p.sendSink))
                         } yield results
                       }
                     }
        } yield assert(results)(equalTo(()))
      },
      testM("events that published using sendSink and generate an exception on send should fail the sink") {
        val queueName                  = "sendSink-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A").map(ProducerEvent(_))

        val client = new SqsAsyncClient {
          override def serviceName(): String = "test-sqs-async-client"

          override def close(): Unit = ()

          override def sendMessageBatch(sendMessageBatchRequest: SendMessageBatchRequest): CompletableFuture[SendMessageBatchResponse] =
            CompletableFuture.supplyAsync[SendMessageBatchResponse](() => throw new RuntimeException("network failure"))
        }

        for {
          _            <- withFastClock.fork
          producer      = Producer.make(client, queueUrl, Serializer.serializeString, settings)
          errOrResults <- producer.use(p => Stream.succeed(events).run(p.sendSink)).either
        } yield assert(errOrResults.isLeft)(isTrue)
      },
      testM("events that published using sendSink and return an unrecoverable error should fail the sink on error") {
        val queueName                  = "sendSink-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A").map(ProducerEvent(_))
        val client                     = failUnrecoverableClient

        for {
          _            <- withFastClock.fork
          producer      = Producer.make(client, queueUrl, Serializer.serializeString, settings)
          errOrResults <- producer.use(p => Stream.succeed(events).run(p.sendSink)).either
        } yield assert(errOrResults.isLeft)(isTrue)
      },
      testM("submitted events can succeed and fail if there are unrecoverable errors") {
        val queueName                  = "success-and-unrecoverable-failures-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A", "B", "C").map(ProducerEvent(_))

        val client = new SqsAsyncClient {
          override def serviceName(): String = "test-sqs-async-client"

          override def close(): Unit = ()

          override def sendMessageBatch(sendMessageBatchRequest: SendMessageBatchRequest): CompletableFuture[SendMessageBatchResponse] = {
            val batchRequestEntries                                       = sendMessageBatchRequest.entries().asScala
            val (batchRequestEntriesToSucceed, batchRequestEntriesToFail) = batchRequestEntries.partition(_.messageBody() == "A")

            val resultEntries = batchRequestEntriesToSucceed.map(entry => SendMessageBatchResultEntry.builder().id(entry.id()).build()).toList

            val errorEntries = batchRequestEntriesToFail.map { entry =>
              BatchResultErrorEntry.builder().id(entry.id()).code("AccessDeniedException").senderFault(false).build()
            }.toList

            val res = SendMessageBatchResponse
              .builder()
              .successful(resultEntries: _*)
              .failed(errorEntries: _*)
              .build()

            CompletableFuture.completedFuture(res)
          }
        }

        for {
          _       <- withFastClock.fork
          producer = Producer.make(client, queueUrl, Serializer.serializeString, settings)
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
      testM("submitted events can be republished if there are recoverable errors") {
        val queueName                  = "success-and-recoverable-failures-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A", "B", "C").map(ProducerEvent(_))

        val invokeCount = new AtomicInteger(0)
        val client      = new SqsAsyncClient {
          override def serviceName(): String = "test-sqs-async-client"

          override def close(): Unit = ()

          override def sendMessageBatch(sendMessageBatchRequest: SendMessageBatchRequest): CompletableFuture[SendMessageBatchResponse] = {
            invokeCount.incrementAndGet()

            val batchRequestEntries                                       = sendMessageBatchRequest.entries().asScala
            val (batchRequestEntriesToSucceed, batchRequestEntriesToFail) = batchRequestEntries.splitAt(2)

            val resultEntries = batchRequestEntriesToSucceed.map(entry => SendMessageBatchResultEntry.builder().id(entry.id()).build()).toList

            val errorEntries = batchRequestEntriesToFail.map { entry =>
              BatchResultErrorEntry.builder().id(entry.id()).code("ServiceUnavailable").senderFault(false).build()
            }.toList

            val res = SendMessageBatchResponse
              .builder()
              .successful(resultEntries: _*)
              .failed(errorEntries: _*)
              .build()

            CompletableFuture.completedFuture(res)
          }
        }

        for {
          _       <- withFastClock.fork
          producer = Producer.make(client, queueUrl, Serializer.serializeString, settings)
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
      testM("if the number of recoverable retries exceeds the limit, messages fail") {
        val queueName                  = "fail-when-retry-limit-reached-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A", "B", "C").map(ProducerEvent(_))

        val invokeCount = new AtomicInteger(0)
        val client      = new SqsAsyncClient {
          override def serviceName(): String = "test-sqs-async-client"

          override def close(): Unit = ()

          override def sendMessageBatch(sendMessageBatchRequest: SendMessageBatchRequest): CompletableFuture[SendMessageBatchResponse] = {
            val batchRequestEntriesToFail = sendMessageBatchRequest.entries().asScala

            invokeCount.addAndGet(batchRequestEntriesToFail.size)

            val errorEntries = batchRequestEntriesToFail.map { entry =>
              BatchResultErrorEntry.builder().id(entry.id()).code("ServiceUnavailable").senderFault(false).build()
            }.toList

            val res = SendMessageBatchResponse
              .builder()
              .failed(errorEntries: _*)
              .build()

            CompletableFuture.completedFuture(res)
          }
        }

        for {
          _       <- withFastClock.fork
          producer = Producer.make(client, queueUrl, Serializer.serializeString, settings)
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
      testM("a SendMessageBatchRequest failed with an exception should fail") {
        val queueName                  = "fail-with-exception-" + UUID.randomUUID().toString
        val queueUrl                   = s"sqs://$queueName"
        val settings: ProducerSettings = ProducerSettings()
        val events                     = List("A").map(ProducerEvent(_))

        val invokeCount = new AtomicInteger(0)
        val client      = new SqsAsyncClient {
          override def serviceName(): String = "test-sqs-async-client"

          override def close(): Unit = ()

          override def sendMessageBatch(sendMessageBatchRequest: SendMessageBatchRequest): CompletableFuture[SendMessageBatchResponse] = {
            invokeCount.addAndGet(1)
            CompletableFuture.supplyAsync[SendMessageBatchResponse](() => throw new RuntimeException("unexpected failure"))
          }
        }

        for {
          _            <- withFastClock.fork
          producer      = Producer.make(client, queueUrl, Serializer.serializeString, settings)
          errOrResults <- producer.use(p => p.produceBatchE(events)).either
        } yield assert(errOrResults.isLeft)(isTrue)
      }
    )

  override def aspects: List[TestAspect[Nothing, TestEnvironment, Nothing, Any]] =
    List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))

  def queueResource(capacity: Int): Task[ZManaged[Any, Throwable, Queue[SqsRequestEntry[String]]]] =
    Task.succeed {
      Queue.bounded[SqsRequestEntry[String]](capacity).toManaged(_.shutdown)
    }

  def withFastClock: ZIO[TestClock with Live, Nothing, Int] =
    Live.withLive(TestClock.adjust(1.seconds))(_.repeat(Schedule.spaced(10.millis)))

  /**
   * A client that fails all incoming messages in the batch with unrecoverable error.
   */
  val failUnrecoverableClient: SqsAsyncClient = new SqsAsyncClient {
    override def serviceName(): String = "test-sqs-async-client"

    override def close(): Unit = ()

    override def sendMessageBatch(sendMessageBatchRequest: SendMessageBatchRequest): CompletableFuture[SendMessageBatchResponse] = {
      val batchRequestEntries = sendMessageBatchRequest.entries().asScala
      val errorEntries        = batchRequestEntries.map { entry =>
        BatchResultErrorEntry.builder().id(entry.id()).code("AccessDeniedException").senderFault(false).build()
      }.toList

      val res = SendMessageBatchResponse
        .builder()
        .failed(errorEntries: _*)
        .build()

      CompletableFuture.completedFuture(res)
    }
  }

}
