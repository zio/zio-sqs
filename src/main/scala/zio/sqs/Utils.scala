package zio.sqs

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._
import zio.{IO, Task}

import scala.jdk.CollectionConverters._

object Utils {

  def createQueue(
    client: SqsAsyncClient,
    name: String,
    attributes: Map[QueueAttributeName, String] = Map()
  ): Task[Unit] =
    Task.effectAsync[Unit] { cb =>
      client
        .createQueue(CreateQueueRequest.builder.queueName(name).attributes(attributes.asJava).build)
        .handle[Unit] { (_, err) =>
          err match {
            case null => cb(IO.unit)
            case ex   => cb(IO.fail(ex))
          }
        }
      ()
    }

  def getQueueUrl(client: SqsAsyncClient, name: String): Task[String] = Task.effectAsync[String] { cb =>
    client
      .getQueueUrl(GetQueueUrlRequest.builder.queueName(name).build)
      .handle[Unit] { (result, err) =>
        err match {
          case null => cb(IO.succeed(result.queueUrl))
          case ex   => cb(IO.fail(ex))
        }
      }
    ()
  }

}
