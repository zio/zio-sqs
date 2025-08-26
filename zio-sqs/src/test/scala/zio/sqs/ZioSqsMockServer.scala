package zio.sqs

import org.elasticmq.NodeAddress
import org.elasticmq.RelaxedSQSLimits
import org.elasticmq.rest.sqs.SQSRestServer
import org.elasticmq.rest.sqs.TheSQSRestServerBuilder
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import zio.Scope
import zio.ZIO
import zio.ZLayer
import zio.aws.core.config.AwsConfig
import zio.aws.sqs.Sqs

import java.net.URI

object ZioSqsMockServer extends TheSQSRestServerBuilder(None, None, "", 9324, NodeAddress(), true, RelaxedSQSLimits, "elasticmq", "000000000000", None) {
  private val staticCredentialsProvider: StaticCredentialsProvider =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
  private val uri                                                  = new URI("http://localhost:9324")
  private val region: Region                                       = Region.AP_NORTHEAST_2

  val serverResource: ZIO[Any with Scope, Throwable, SQSRestServer] =
    ZIO.acquireRelease(
      ZIO.attempt(this.start())
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
