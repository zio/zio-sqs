package zio.sqs

import java.net.URI

import zio.aws.core.config.AwsConfig
import zio.aws.sqs.Sqs
import org.elasticmq.rest.sqs.{ SQSRestServer, SQSRestServerBuilder }
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import zio.{ Scope, ZIO, ZLayer }

object ZioSqsMockServer {
  private val staticCredentialsProvider: StaticCredentialsProvider =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
  private val uri                                                  = new URI("http://localhost:9324")
  private val region: Region                                       = Region.AP_NORTHEAST_2

  val serverResource: ZIO[Any with Scope, Throwable, SQSRestServer] =
    ZIO.acquireRelease(
      ZIO.attempt(SQSRestServerBuilder.start())
    )(server => ZIO.succeed(server.stopAndWait()))

  val clientResource: ZLayer[AwsConfig, Throwable, Sqs] =
    zio.aws.sqs.Sqs.customized(
      _.region(region)
        .credentialsProvider(
          staticCredentialsProvider
        )
        .endpointOverride(uri)
    )
}
