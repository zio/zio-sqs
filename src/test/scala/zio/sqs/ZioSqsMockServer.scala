package zio.sqs

import java.net.URI

import org.elasticmq.rest.sqs.{ SQSRestServer, SQSRestServerBuilder }
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import zio.{ Task, UIO, ZIO, ZManaged }

object ZioSqsMockServer {
  private val staticCredentialsProvider: StaticCredentialsProvider =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
  private val uri            = new URI("http://localhost:9324")
  private val region: Region = Region.AP_NORTHEAST_2

  val serverResource: Task[ZManaged[Any, Throwable, SQSRestServer]] = ZIO.effect(
    ZManaged.make(
      Task(SQSRestServerBuilder.start())
    ) { server =>
      UIO.effectTotal(server.stopAndWait())
    }
  )

  val clientResource: Task[ZManaged[Any, Throwable, SqsAsyncClient]] = ZIO.effect(
    ZManaged.make(
      Task {
        SqsAsyncClient
          .builder()
          .region(region)
          .credentialsProvider(
            staticCredentialsProvider
          )
          .endpointOverride(uri)
          .build()
      }
    ) { client =>
      UIO.effectTotal(client.close())
    }
  )
}
