package zio.sqs

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import zio.sqs.ZioSqsMockServer._
import zio.stream.{ Sink, Stream }
import zio.test.Assertion._
import zio.test._

import scala.jdk.CollectionConverters._

object SqsPublishStreamSpec
    extends DefaultRunnableSpec(
      suite("SqsPublishStream")(
        test("buildSendMessageBatchRequest can be created") {
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

          val (req, indexedMessages) = SqsPublisherStream.buildSendMessageBatchRequest(queueUrl, events)
          val reqEntries             = req.entries().asScala

          assert(reqEntries.size, equalTo(2))

          assert(reqEntries(0).id(), equalTo("0"))
          assert(reqEntries(0).messageBody(), equalTo("A"))
          assert(reqEntries(0).messageAttributes().size(), equalTo(1))
          assert(reqEntries(0).messageAttributes().asScala.contains("Name"), equalTo(true))
          assert(reqEntries(0).messageAttributes().asScala("Name"), equalTo(attr))
          assert(Option(reqEntries(0).messageGroupId()), equalTo(Some("g1")))
          assert(Option(reqEntries(0).messageDeduplicationId()), equalTo(Some("d1")))

          assert(reqEntries(1).id(), equalTo("1"))
          assert(reqEntries(1).messageBody(), equalTo("B"))
          assert(reqEntries(1).messageAttributes().size(), equalTo(0))
          assert(Option(reqEntries(1).messageGroupId()), equalTo(Some("g2")))
          assert(Option(reqEntries(1).messageDeduplicationId()), equalTo(Some("d2")))

          assert(indexedMessages, equalTo(events.zipWithIndex))
        },
        test("buildSendMessageBatchRequest can be created from strings") {
          val queueUrl = "sqs://queue"
          val events = List(
            SqsPublishEvent("a"),
            SqsPublishEvent("b")
          )

          val (req, indexedMessages) = SqsPublisherStream.buildSendMessageBatchRequest(queueUrl, events)
          val reqEntries             = req.entries().asScala

          assert(reqEntries.size, equalTo(2))

          assert(reqEntries(0).id(), equalTo("0"))
          assert(reqEntries(0).messageBody(), equalTo("a"))
          assert(reqEntries(0).messageAttributes().size(), equalTo(0))
          assert(Option(reqEntries(0).messageGroupId()), equalTo(None))
          assert(Option(reqEntries(0).messageDeduplicationId()), equalTo(None))

          assert(reqEntries(1).id(), equalTo("1"))
          assert(reqEntries(1).messageBody(), equalTo("b"))
          assert(reqEntries(1).messageAttributes().size(), equalTo(0))
          assert(Option(reqEntries(1).messageGroupId()), equalTo(None))
          assert(Option(reqEntries(1).messageDeduplicationId()), equalTo(None))

          assert(indexedMessages, equalTo(events.zipWithIndex))
        },
        testM("runSendMessageBatchRequest can be executed") {
          val queueName = "SqsPublishStreamSpec_runSend"
          for {
            events <- Util
                       .stringGen(10)
                       .sample
                       .map(_.value.map(SqsPublishEvent(_)))
                       .run(Sink.await[List[SqsPublishEvent]])
            server <- serverResource
            client <- clientResource
            results <- server.use { _ =>
                        client.use { c =>
                          for {
                            _         <- Utils.createQueue(c, queueName)
                            queueUrl  <- Utils.getQueueUrl(c, queueName)
                            (req, ms) = SqsPublisherStream.buildSendMessageBatchRequest(queueUrl, events)
                            results   <- SqsPublisherStream.runSendMessageBatchRequest(c, req, ms)
                          } yield results
                        }
                      }
          } yield {
            assert(results.size, equalTo(events.size))
            assert(results.forall(_.isRight), equalTo(true))
          }
        },
        testM("events can be published using a stream") {
          val queueName = "SqsPublishStreamSpec_sendStream"
          for {
            events <- Util
                       .stringGen(23)
                       .sample
                       .map(_.value.map(SqsPublishEvent(_)))
                       .run(Sink.await[List[SqsPublishEvent]])
            server <- serverResource
            client <- clientResource
            results <- server.use { _ =>
                        client.use { c =>
                          for {
                            _        <- Utils.createQueue(c, queueName)
                            queueUrl <- Utils.getQueueUrl(c, queueName)
                            sendStream = SqsPublisherStream
                              .sendStream(c, queueUrl, settings = SqsPublisherStreamSettings()) _
                            results <- sendStream(Stream(events: _*))
                                        .run(Sink.collectAll[SqsPublishErrorOrEvent]) // replace with .via when ZIO > RC17 is released
                          } yield results
                        }
                      }
          } yield {
            assert(results.size, equalTo(events.size))
            assert(results.forall(_.isRight), equalTo(true))
          }
        }
      ),
      List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
    )
