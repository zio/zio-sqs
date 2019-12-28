package zio.sqs

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._
import zio.clock.Clock
import zio.duration._
import zio.sqs.SqsPublishStreamSpecUtil._
import zio.sqs.SqsPublisherStream.{ SqsRequest, SqsRequestEntry, SqsResponseErrorEntry }
import zio.sqs.ZioSqsMockServer._
import zio.stream.{ Sink, Stream }
import zio.test.Assertion._
import zio.test._
import zio.test.environment.{ Live, TestClock }
import zio.{ test => _, _ }

import scala.jdk.CollectionConverters._

object SqsPublishStreamSpec
    extends DefaultRunnableSpec(
      suite("SqsPublishStream")(
        test("nextPower2 can be calculated") {
          assert(SqsPublisherStream.nextPower2(0), equalTo(0)) &&
          assert(SqsPublisherStream.nextPower2(1), equalTo(1)) &&
          assert(SqsPublisherStream.nextPower2(2), equalTo(2)) &&
          assert(SqsPublisherStream.nextPower2(9), equalTo(16)) &&
          assert(SqsPublisherStream.nextPower2(129), equalTo(256))
        },
        testM("SqsRequestEntry can be created") {
          val attr = MessageAttributeValue
            .builder()
            .dataType("String")
            .stringValue("Bob")
            .build()

          val pe = SqsPublishEvent(
            body = "A",
            attributes = Map("Name" -> attr),
            groupId = Some("g1"),
            deduplicationId = Some("d1")
          )

          for {
            done         <- Promise.make[Throwable, SqsPublishErrorOrResult]
            requestEntry = SqsRequestEntry(pe, done, 10)
            isDone       <- requestEntry.done.isDone
          } yield {
            assert(requestEntry.event, equalTo(pe)) &&
            assert(isDone, equalTo(false)) &&
            assert(requestEntry.retryCount, equalTo(10))
          }
        },
        testM("SqsRequest can be created") {
          val attr = MessageAttributeValue
            .builder()
            .dataType("String")
            .stringValue("Bob")
            .build()

          val pe = SqsPublishEvent(
            body = "A",
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
            done         <- Promise.make[Throwable, SqsPublishErrorOrResult]
            requestEntry = SqsRequestEntry(pe, done, 10)
            request      = SqsRequest(batchReq, List(requestEntry))
          } yield {
            assert(request.inner, equalTo(batchReq)) &&
            assert(request.entries, equalTo(List(requestEntry)))
          }
        },
        testM("SqsResponseErrorEntry can be created") {
          val event = SqsPublishEvent("e1")
          val errEntry =
            BatchResultErrorEntry.builder().id("id1").code("code2").message("message3").senderFault(true).build()

          val eventError = SqsPublishEventError(errEntry, event)

          for {
            done     <- Promise.make[Throwable, SqsPublishErrorOrResult]
            errEntry = SqsResponseErrorEntry(done, eventError)
            isDone   <- errEntry.done.isDone
          } yield {
            assert(errEntry.error, equalTo(eventError)) &&
            assert(isDone, equalTo(false))
          }
        },
        testM("SendMessageBatchResponse can be partitioned") {
          val retryMaxCount = 10
          val rs            = Range(0, 4).toList
          val ids           = rs.map(_.toString)
          val bodies        = rs.map(_ + 'A').map(_.toChar.toString)
          val retries       = List(1, 2, retryMaxCount, 3)
          for {
            dones          <- ZIO.traverse(Range(0, 4))(_ => Promise.make[Throwable, SqsPublishErrorOrResult])
            requestEntries = bodies.zip(dones).zip(retries).map { case ((a, b), c) => SqsRequestEntry(SqsPublishEvent(a), b, c) }
            m              = ids.zip(requestEntries).toMap
            resultEntry0   = SendMessageBatchResultEntry.builder().id("0").build()
            errorEntry1    = BatchResultErrorEntry.builder().id("1").code("ServiceUnavailable").senderFault(false).build()
            errorEntry2    = BatchResultErrorEntry.builder().id("2").code("ThrottlingException").senderFault(false).build()
            errorEntry3    = BatchResultErrorEntry.builder().id("3").code("AccessDeniedException").senderFault(false).build()
            res = SendMessageBatchResponse
              .builder()
              .successful(resultEntry0)
              .failed(errorEntry1, errorEntry2, errorEntry3)
              .build()
            partitioner                     = SqsPublisherStream.partitionResponse(m, retryMaxCount) _
            (successful, retryable, errors) = partitioner(res)
          } yield {
            assert(successful.toList.size, equalTo(1)) &&
            assert(retryable.toList.size, equalTo(1)) &&
            assert(errors.toList.size, equalTo(2))
          }
        },
        testM("SendMessageBatchResponse can be partitioned and mapped") {
          val retryMaxCount = 10
          val rs            = Range(0, 4).toList
          val ids           = rs.map(_.toString)
          val bodies        = rs.map(_ + 'A').map(_.toChar.toString)
          val retries       = List(1, 2, retryMaxCount, 3)
          for {
            dones          <- ZIO.traverse(Range(0, 4))(_ => Promise.make[Throwable, SqsPublishErrorOrResult])
            requestEntries = bodies.zip(dones).zip(retries).map { case ((a, b), c) => SqsRequestEntry(SqsPublishEvent(a), b, c) }
            m              = ids.zip(requestEntries).toMap
            resultEntry0   = SendMessageBatchResultEntry.builder().id("0").build()
            errorEntry1    = BatchResultErrorEntry.builder().id("1").code("ServiceUnavailable").senderFault(false).build()
            errorEntry2    = BatchResultErrorEntry.builder().id("2").code("ThrottlingException").senderFault(false).build()
            errorEntry3    = BatchResultErrorEntry.builder().id("3").code("AccessDeniedException").senderFault(false).build()
            res = SendMessageBatchResponse
              .builder()
              .successful(resultEntry0)
              .failed(errorEntry1, errorEntry2, errorEntry3)
              .build()
            partitioner                                          = SqsPublisherStream.partitionResponse(m, retryMaxCount) _
            (successful, retryable, errors)                      = partitioner(res)
            mapper                                               = SqsPublisherStream.mapResponse(m) _
            (successfulEntries, retryableEntries, errorsEntries) = mapper(successful, retryable, errors)
          } yield {
            assert(successful.toList.size, equalTo(1)) &&
            assert(retryable.toList.size, equalTo(1)) &&
            assert(errors.toList.size, equalTo(2)) &&
            assert(successfulEntries.toList.size, equalTo(1)) &&
            assert(retryableEntries.toList.size, equalTo(1)) &&
            assert(errorsEntries.toList.size, equalTo(2)) &&
            assert(successfulEntries.toList.map(_.event.body), hasSameElements(List("A"))) &&
            assert(retryableEntries.toList.map(_.event.body), hasSameElements(List("B"))) &&
            assert(errorsEntries.toList.map(_.error.event.body), hasSameElements(List("C", "D")))
          }
        },
        testM("buildSendMessageBatchRequest creates a new request") {
          val queueUrl = "sqs://queue"

          val attr = MessageAttributeValue
            .builder()
            .dataType("String")
            .stringValue("Bob")
            .build()

          val events = List(
            SqsPublishEvent(
              body = "A",
              attributes = Map("Name" -> attr),
              groupId = Some("g1"),
              deduplicationId = Some("d1")
            ),
            SqsPublishEvent(
              body = "B",
              attributes = Map.empty[String, MessageAttributeValue],
              groupId = Some("g2"),
              deduplicationId = Some("d2")
            )
          )

          for {
            reqEntries <- ZIO.traverse(events) { event =>
                           for {
                             done <- Promise.make[Throwable, SqsPublishErrorOrResult]
                           } yield SqsRequestEntry(event, done, 0)
                         }
          } yield {
            val req = SqsPublisherStream.buildSendMessageBatchRequest(queueUrl, reqEntries)

            val innerReq        = req.inner
            val innerReqEntries = req.inner.entries().asScala

            assert(req.entries, equalTo(reqEntries)) &&
            assert(innerReq.hasEntries, equalTo(true)) &&
            assert(innerReqEntries.size, equalTo(2)) &&
            assert(innerReqEntries(0).id(), equalTo("0")) &&
            assert(innerReqEntries(0).messageBody(), equalTo("A")) &&
            assert(innerReqEntries(0).messageAttributes().size(), equalTo(1)) &&
            assert(innerReqEntries(0).messageAttributes().asScala.contains("Name"), equalTo(true)) &&
            assert(innerReqEntries(0).messageAttributes().asScala("Name"), equalTo(attr)) &&
            assert(Option(innerReqEntries(0).messageGroupId()), equalTo(Some("g1"))) &&
            assert(Option(innerReqEntries(0).messageDeduplicationId()), equalTo(Some("d1"))) &&
            assert(innerReqEntries(1).id(), equalTo("1")) &&
            assert(innerReqEntries(1).messageBody(), equalTo("B")) &&
            assert(innerReqEntries(1).messageAttributes().size(), equalTo(0)) &&
            assert(Option(innerReqEntries(1).messageGroupId()), equalTo(Some("g2"))) &&
            assert(Option(innerReqEntries(1).messageDeduplicationId()), equalTo(Some("d2")))
          }
        },
        testM("runSendMessageBatchRequest can be executed") {
          val queueName                            = "runSendMessageBatchRequest-" + UUID.randomUUID().toString
          val settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
          val eventCount                           = settings.batchSize
          for {
            events <- Util
                       .listOfStringsN(eventCount)
                       .sample
                       .map(_.value.map(SqsPublishEvent(_)))
                       .run(Sink.await[List[SqsPublishEvent]])
            server     <- serverResource
            client     <- clientResource
            retryQueue <- queueResource(16)
            dones <- server.use {
                      _ =>
                        client.use {
                          c =>
                            retryQueue.use { q =>
                              for {
                                _        <- Utils.createQueue(c, queueName)
                                queueUrl <- Utils.getQueueUrl(c, queueName)
                                reqEntries <- ZIO.traverse(events) { event =>
                                               for {
                                                 done <- Promise.make[Throwable, SqsPublishErrorOrResult]
                                               } yield SqsRequestEntry(event, done, 0)
                                             }
                                req        = SqsPublisherStream.buildSendMessageBatchRequest(queueUrl, reqEntries)
                                retryDelay = 1.millisecond
                                retryCount = 1
                                reqSender  = SqsPublisherStream.runSendMessageBatchRequest(c, q, retryDelay, retryCount) _
                                _          <- reqSender(req)
                              } yield ZIO.traverse(reqEntries)(entry => entry.done.await)
                            }
                        }
                    }
            isAllRight <- dones.map(_.forall(_.isRight))
          } yield {
            assert(isAllRight, equalTo(true))
          }
        },
        testM("events can be published using sendStream") {
          val queueName                            = "sendStream-" + UUID.randomUUID().toString
          val settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
          val eventCount                           = (settings.batchSize * 2) + 3

          for {
            events <- Util
                       .listOfStringsN(eventCount)
                       .sample
                       .map(_.value.map(SqsPublishEvent(_)))
                       .run(Sink.await[List[SqsPublishEvent]])
            server <- serverResource
            client <- clientResource
            results <- server.use {
                        _ =>
                          client.use { c =>
                            for {
                              _           <- withFastClock.fork
                              _           <- Utils.createQueue(c, queueName)
                              queueUrl    <- Utils.getQueueUrl(c, queueName)
                              producer    <- Task.succeed(SqsPublisherStream.producer(c, queueUrl, settings))
                              resultQueue <- Queue.unbounded[SqsPublishErrorOrResult]
                              _ <- producer.use { p =>
                                    p.sendStream(Stream(events: _*))
                                      .foreach(resultQueue.offer) // replace with .via when ZIO > RC17 is released -- Sink.collectAll[SqsPublishErrorOrResult]
                                  }.fork
                              results <- ZIO.sequence(List.fill(eventCount)(resultQueue.take))
                            } yield results
                          }
                      }
          } yield {
            assert(results.size, equalTo(events.size)) &&
            assert(results.forall(_.isRight), equalTo(true))
          }
        },
        testM("events can be published using produce") {
          val queueName                            = "produce-" + UUID.randomUUID().toString
          val settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
          val eventCount                           = settings.batchSize

          for {
            events <- Util
                       .listOfStringsN(eventCount)
                       .sample
                       .map(_.value.map(SqsPublishEvent(_)))
                       .run(Sink.await[List[SqsPublishEvent]])
            server <- serverResource
            client <- clientResource
            results <- server.use { _ =>
                        client.use { c =>
                          for {
                            _        <- withFastClock.fork
                            _        <- Utils.createQueue(c, queueName)
                            queueUrl <- Utils.getQueueUrl(c, queueName)
                            producer <- Task.succeed(SqsPublisherStream.producer(c, queueUrl, settings))
                            results <- producer.use { p =>
                                        ZIO.traversePar(events)(event => p.produce(event))
                                      }
                          } yield results
                        }
                      }
          } yield {
            assert(results.size, equalTo(events.size)) &&
            assert(results.forall(_.isRight), equalTo(true))
          }
        },
        testM("events can be published using produceBatch") {
          val queueName                            = "produceBatch-" + UUID.randomUUID().toString
          val settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
          val eventCount                           = settings.batchSize * 2

          for {
            events <- Util
                       .listOfStringsN(eventCount)
                       .sample
                       .map(_.value.map(SqsPublishEvent(_)))
                       .run(Sink.await[List[SqsPublishEvent]])
            server <- serverResource
            client <- clientResource
            results <- server.use { _ =>
                        client.use { c =>
                          for {
                            _        <- withFastClock.fork
                            _        <- Utils.createQueue(c, queueName)
                            queueUrl <- Utils.getQueueUrl(c, queueName)
                            producer <- Task.succeed(SqsPublisherStream.producer(c, queueUrl, settings))
                            results <- producer.use { p =>
                                        p.produceBatch(events)
                                      }
                          } yield results
                        }
                      }
          } yield {
            assert(results.size, equalTo(events.size)) &&
            assert(results.forall(_.isRight), equalTo(true))
          }
        },
        testM("events can be published using sendSink") {
          val queueName                            = "sendSink-" + UUID.randomUUID().toString
          val settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
          val eventCount                           = settings.batchSize

          for {
            events <- Util
                       .listOfStringsN(eventCount)
                       .sample
                       .map(_.value.map(SqsPublishEvent(_)))
                       .run(Sink.await[List[SqsPublishEvent]])
            server <- serverResource
            client <- clientResource
            results <- server.use { _ =>
                        client.use { c =>
                          for {
                            _        <- withFastClock.fork
                            _        <- Utils.createQueue(c, queueName)
                            queueUrl <- Utils.getQueueUrl(c, queueName)
                            producer <- Task.succeed(SqsPublisherStream.producer(c, queueUrl, settings))
                            results <- producer.use { p =>
                                        Stream.succeed(events).run(p.sendSink)
                                      }
                          } yield results
                        }
                      }
          } yield {
            assert(results, equalTo(()))
          }
        },
        testM("submitted events can succeed and fail if there are unrecoverable errors") {
          val queueName                            = "success-and-unrecoverable-failures-" + UUID.randomUUID().toString
          val queueUrl                             = s"sqs://${queueName}"
          val settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
          val events                               = List("A", "B", "C").map(SqsPublishEvent(_))

          val client = new SqsAsyncClient {
            override def serviceName(): String = "test-sqs-async-client"
            override def close(): Unit         = ()
            override def sendMessageBatch(sendMessageBatchRequest: SendMessageBatchRequest): CompletableFuture[SendMessageBatchResponse] = {
              val batchRequestEntries                                       = sendMessageBatchRequest.entries().asScala
              val (batchRequestEntriesToSucceed, batchRequestEntriesToFail) = batchRequestEntries.partition(_.messageBody() == "A")

              val resultEntries = batchRequestEntriesToSucceed.map { entry =>
                SendMessageBatchResultEntry.builder().id(entry.id()).build()
              }.toList

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
            _        <- withFastClock.fork
            producer <- Task.succeed(SqsPublisherStream.producer(client, queueUrl, settings))
            results <- producer.use { p =>
                        p.produceBatch(events)
                      }
          } yield {
            val successes = results.filter(_.isRight).collect {
              case Right(x) => x.body
            }

            val failures = results.filter(_.isLeft).collect {
              case Left(x) => x.event.body
            }

            assert(results.size, equalTo(events.size)) &&
            assert(successes, hasSameElements(List("A"))) &&
            assert(failures, hasSameElements(List("B", "C")))
          }
        },
        testM("submitted events can be republished if there are recoverable errors") {
          val queueName                            = "success-and-recoverable-failures-" + UUID.randomUUID().toString
          val queueUrl                             = s"sqs://${queueName}"
          val settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
          val events                               = List("A", "B", "C").map(SqsPublishEvent(_))

          val invokeCount = new AtomicInteger(0)
          val client = new SqsAsyncClient {
            override def serviceName(): String = "test-sqs-async-client"
            override def close(): Unit         = ()
            override def sendMessageBatch(sendMessageBatchRequest: SendMessageBatchRequest): CompletableFuture[SendMessageBatchResponse] = {
              invokeCount.incrementAndGet()

              val batchRequestEntries                                       = sendMessageBatchRequest.entries().asScala
              val (batchRequestEntriesToSucceed, batchRequestEntriesToFail) = batchRequestEntries.splitAt(2)

              val resultEntries = batchRequestEntriesToSucceed.map { entry =>
                SendMessageBatchResultEntry.builder().id(entry.id()).build()
              }.toList

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
            _        <- withFastClock.fork
            producer <- Task.succeed(SqsPublisherStream.producer(client, queueUrl, settings))
            results <- producer.use { p =>
                        p.produceBatch(events)
                      }
          } yield {
            val successes = results.filter(_.isRight).collect {
              case Right(x) => x.body
            }

            assert(results.size, equalTo(events.size)) &&
            assert(successes, hasSameElements(List("A", "B", "C"))) &&
            assert(invokeCount.get(), isGreaterThanEqualTo(2))
          }
        },
        testM("if the number of recoverable retries exceeds the limit, messages fail") {
          val queueName                            = "fail-when-retry-limit-reached-" + UUID.randomUUID().toString
          val queueUrl                             = s"sqs://${queueName}"
          val settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
          val events                               = List("A", "B", "C").map(SqsPublishEvent(_))

          val invokeCount = new AtomicInteger(0)
          val client = new SqsAsyncClient {
            override def serviceName(): String = "test-sqs-async-client"
            override def close(): Unit         = ()
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
            _        <- withFastClock.fork
            producer <- Task.succeed(SqsPublisherStream.producer(client, queueUrl, settings))
            results <- producer.use { p =>
                        p.produceBatch(events)
                      }
          } yield {
            val failures = results.filter(_.isLeft).collect {
              case Left(x) => x.event.body
            }

            assert(results.size, equalTo(events.size)) &&
            assert(failures, hasSameElements(List("A", "B", "C"))) &&
            assert(invokeCount.get(), equalTo((settings.retryMaxCount + 1) * events.size))
          }
        },
        testM("a SendMessageBatchRequest failed with an exception should fail") {
          val queueName                            = "fail-with-exception-" + UUID.randomUUID().toString
          val queueUrl                             = s"sqs://${queueName}"
          val settings: SqsPublisherStreamSettings = SqsPublisherStreamSettings()
          val events                               = List("A").map(SqsPublishEvent(_))

          val invokeCount = new AtomicInteger(0)
          val client = new SqsAsyncClient {
            override def serviceName(): String = "test-sqs-async-client"
            override def close(): Unit         = ()
            override def sendMessageBatch(sendMessageBatchRequest: SendMessageBatchRequest): CompletableFuture[SendMessageBatchResponse] = {
              invokeCount.addAndGet(1)
              CompletableFuture.failedFuture(new RuntimeException("unexpected failure"))
            }
          }

          for {
            _        <- withFastClock.fork
            producer <- Task.succeed(SqsPublisherStream.producer(client, queueUrl, settings))
            errOrResults <- producer.use { p =>
              p.produceBatch(events)
            }.either
          } yield {
            assert(errOrResults.isLeft, equalTo(true))
          }
        }
      ),
      List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
    )

object SqsPublishStreamSpecUtil {

  def queueResource(capacity: Int): Task[ZManaged[Any, Throwable, Queue[SqsRequestEntry]]] = Task.succeed {
    Queue.bounded[SqsRequestEntry](capacity).toManaged(_.shutdown)
  }

  def withFastClock: ZIO[TestClock with Live[Clock], Nothing, Int] =
    Live.withLive(TestClock.adjust(1.seconds))(_.repeat(Schedule.spaced(10.millis)))

}
