package zio.sqs

import scala.collection.JavaConverters._
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._
import scalaz.zio.{ IO, Task }

object Utils {

  def createQueue(
    client: SqsAsyncClient,
    name: String,
    attributes: Map[QueueAttributeName, String] = Map()
  ): Task[Unit] =
    IO.effectAsync[Any, Throwable, Unit] { cb =>
      client
        .createQueue(CreateQueueRequest.builder.queueName(name).attributes(attributes.asJava).build)
        .handle[Unit]((_, err) => {
          err match {
            case null => cb(IO.unit)
            case ex   => cb(IO.fail(ex))
          }
        })
      ()
    }

  def getQueueUrl(client: SqsAsyncClient, name: String): Task[String] = IO.effectAsync[Any, Throwable, String] { cb =>
    client
      .getQueueUrl(GetQueueUrlRequest.builder.queueName(name).build)
      .handle[Unit]((result, err) => {
        err match {
          case null => cb(IO.succeed(result.queueUrl))
          case ex   => cb(IO.fail(ex))
        }
      })
    ()
  }

}
