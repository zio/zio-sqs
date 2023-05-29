package zio.sqs

import java.net.URI
import zio.aws.core.config.{ AwsConfig, CommonAwsConfig }
import zio.aws.sqs.Sqs
import org.elasticmq.rest.sqs.SQSRestServer
import org.elasticmq.RelaxedSQSLimits
import org.elasticmq.rest.sqs.TheSQSRestServerBuilder
import org.elasticmq.NodeAddress
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import zio.aws.netty.NettyHttpClient
import zio.{ Scope, ZIO, ZLayer }

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

object MockSqsServerAndClient {

  lazy val layer: ZLayer[Any, Throwable, Sqs] =
    (NettyHttpClient.default ++ mockServer) >>> AwsConfig.configured() >>> zio.aws.sqs.Sqs.live

  private lazy val mockServer: ZLayer[Any, Throwable, CommonAwsConfig] = {
    val dummyAwsKeys =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
    val region       = Region.AP_NORTHEAST_2
    ZLayer.scoped {
      for {
        // Random port to allow parallel tests
        port          <- ZIO.randomWith(_.nextIntBetween(9000, 50000))
        serverBuilder <- ZIO.attempt(
                           TheSQSRestServerBuilder(
                             providedActorSystem = None,
                             providedQueueManagerActor = None,
                             interface = "",
                             port = port,
                             serverAddress = NodeAddress(),
                             generateServerAddress = true,
                             sqsLimits = RelaxedSQSLimits,
                             _awsRegion = region.toString,
                             _awsAccountId = "000000000000",
                             queueEventListener = None
                           )
                         )
        server        <- ZIO.acquireRelease(
                           ZIO.attempt(serverBuilder.start())
                         )(server => ZIO.succeed(server.stopAndWait()))
        awsConfig      = CommonAwsConfig(
                           region = Some(region),
                           credentialsProvider = dummyAwsKeys,
                           endpointOverride = Some(new URI(s"http://localhost:${port}")),
                           None
                         )
      } yield awsConfig
    }
  }

}
