package zio.sqs

import zio.aws.core.config.AwsConfig
import zio.aws.sqs.Sqs

import com.dimafeng.testcontainers.LocalStackV2Container
import org.testcontainers.containers.localstack.LocalStackContainer.Service

import zio.{ Scope, ZIO, ZLayer }

object ZioSqsMockServer {
  private val aws = LocalStackV2Container(services = Seq(Service.S3))

  val serverResource: ZIO[Any with Scope, Throwable, LocalStackV2Container] =
    ZIO.acquireRelease(ZIO.attempt { aws.start(); aws })(aws => ZIO.succeed(aws.stop()))

  val clientResource: ZLayer[AwsConfig, Throwable, Sqs] =
    zio.aws.sqs.Sqs.customized(
      _.region(aws.region)
        .credentialsProvider(
          aws.staticCredentialsProvider
        )
        .endpointOverride(aws.endpointOverride(Service.S3))
    )
}
