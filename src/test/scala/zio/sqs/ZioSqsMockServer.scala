package zio.sqs

import java.net.URI

import io.github.vigoo.zioaws.core.config.AwsConfig
import io.github.vigoo.zioaws.sqs.Sqs
import io.github.vigoo.zioaws
import org.elasticmq.rest.sqs.{ SQSRestServer, SQSRestServerBuilder }
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import zio.{ Task, UIO, ZIO, ZLayer, ZManaged }

object ZioSqsMockServer {
  private val staticCredentialsProvider: StaticCredentialsProvider =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "key"))
  private val uri                                                  = new URI("http://localhost:9324")
  private val region: Region                                       = Region.AP_NORTHEAST_2

  // TODO should probably be just a ZManaged instead of wrapped in a Task
  val serverResource: Task[ZManaged[Any, Throwable, SQSRestServer]] = ZIO.effect(
    ZManaged.make(
      Task(SQSRestServerBuilder.start())
    )(server => UIO.effectTotal(server.stopAndWait()))
  )

  val clientResource: ZLayer[AwsConfig, Throwable, Sqs] =
    zioaws.sqs.customized(
      _.region(region)
        .credentialsProvider(
          staticCredentialsProvider
        )
        .endpointOverride(uri)
    )
}
