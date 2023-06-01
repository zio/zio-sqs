val mainScala     = "2.13.10"
val allScala      = Seq("3.2.1", "2.13.10", "2.12.16")
val zioVersion    = "2.0.14"
val zioAwsVersion = "5.17.224.4"

inThisBuild(
  List(
    organization := "dev.zio",
    homepage := Some(url("https://zio.dev/zio-sqs")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalaVersion := mainScala,
    crossScalaVersions := allScala,
    Test / parallelExecution := false,
    Test / fork := true,
    run / fork := true,
    pgpPassphrase := sys.env.get("PGP_PASSWORD").map(_.toArray),
    pgpPublicRing := file("/tmp/public.asc"),
    pgpSecretRing := file("/tmp/secret.asc"),
    scmInfo := Some(
      ScmInfo(url("https://github.com/zio/zio-sqs/"), "scm:git:git@github.com:zio/zio-sqs.git")
    ),
    developers := List(
      Developer(
        "ghostdogpr",
        "Pierre Ricadat",
        "ghostdogpr@gmail.com",
        url("https://github.com/ghostdogpr")
      )
    )
  )
)

publishTo := sonatypePublishToBundle.value

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

lazy val root = project
  .in(file("."))
  .settings(
    publish / skip := true
  )
  .aggregate(
    sqs,
    docs
  )

lazy val sqs =
  project
    .in(file("zio-sqs"))
    .settings(
      name := "zio-sqs",
      scalafmtOnCompile := true,
      libraryDependencies ++= Seq(
        "dev.zio"                %% "zio"                     % zioVersion,
        "dev.zio"                %% "zio-streams"             % zioVersion,
        "dev.zio"                %% "zio-aws-sqs"             % zioAwsVersion,
        "dev.zio"                %% "zio-aws-netty"           % zioAwsVersion,
        "org.scala-lang.modules" %% "scala-collection-compat" % "2.8.0",
        "dev.zio"                %% "zio-test"                % zioVersion % "test",
        "dev.zio"                %% "zio-test-sbt"            % zioVersion % "test",
        "org.elasticmq"          %% "elasticmq-rest-sqs"      % "1.3.7"    % "test" cross CrossVersion.for3Use2_13,
        "org.elasticmq"          %% "elasticmq-core"          % "1.3.7"    % "test" cross CrossVersion.for3Use2_13
      ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12)) =>
          Seq("org.typelevel" %% "kind-projector" % "0.10.3")
        case Some((2, 13)) =>
          Seq("org.typelevel" %% "kind-projector" % "0.10.3")
        case _             =>
          Nil
      }),
      scalacOptions ++= Seq(
        "-deprecation",
        "-encoding",
        "UTF-8",
        "-explaintypes",
        "-feature",
        "-language:higherKinds",
        "-language:existentials",
        "-unchecked"
      ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12)) =>
          Seq(
            "-Xfuture",
            "-Xsource:2.13",
            "-Xlint:_,-type-parameter-shadow",
            "-Yno-adapted-args",
            "-Ypartial-unification",
            "-Ywarn-extra-implicit",
            "-Ywarn-inaccessible",
            "-Ywarn-infer-any",
            "-Ywarn-nullary-override",
            "-Ywarn-nullary-unit",
            "-Yrangepos",
            "-Ywarn-numeric-widen",
            "-Ywarn-unused",
            "-Ywarn-value-discard",
            "-opt-inline-from:<source>",
            "-opt-warnings",
            "-opt:l:inline"
          )
        case Some((2, 13)) =>
          Seq(
            "-Xlint:_,-type-parameter-shadow",
            "-Werror",
            "-Yrangepos",
            "-Ywarn-numeric-widen",
            "-Ywarn-unused",
            "-Ywarn-value-discard"
          )
        case _             => Nil
      }),
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
    )

lazy val docs = project
  .in(file("zio-sqs-docs"))
  .settings(
    moduleName := "zio-sqs-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    projectName := "ZIO SQS",
    mainModuleName := (sqs / moduleName).value,
    projectStage := ProjectStage.ProductionReady,
    docsPublishBranch := "series/2.x",
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(sqs),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion
    )
  )
  .dependsOn(sqs)
  .enablePlugins(WebsitePlugin)
