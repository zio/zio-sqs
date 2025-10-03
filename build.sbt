val mainScala        = "2.13.16"
val allScala         = Seq("3.3.6", "2.13.16")
val zioVersion       = "2.1.21"
val zioAwsVersion    = "7.34.6.1"
val elasticMqVersion = "1.6.14"

enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

inThisBuild(
  List(
    name := "ZIO SQS",
    organization := "dev.zio",
    homepage := Some(url("https://zio.dev/zio-sqs")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalaVersion := mainScala,
    crossScalaVersions := allScala,
    Test / parallelExecution := false,
    Test / fork := true,
    run / fork := true,
    ciJvmOptions ++= Seq("-Xms6G", "-Xmx6G", "-Xss4M", "-XX:+UseG1GC"),
    ciEnabledBranches := List("series/2.x"),
    ciTargetJavaVersions := List("17", "21"),
    developers := List(
      Developer(
        "ghostdogpr",
        "Pierre Ricadat",
        "ghostdogpr@gmail.com",
        url("https://github.com/ghostdogpr")
      ),
      Developer(
        "calvinlfer",
        "Calvin Fernandes",
        "cal@kaizen-solutions.io",
        url("https://github.com/calvinlfer")
      )
    ),
    semanticdbEnabled := true
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("lint", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
addCommandAlias("validate", "check" + allScala.map(v => s"++${v}! test").mkString(";", ";", ""))

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
        "org.scala-lang.modules" %% "scala-collection-compat" % "2.13.0",
        "dev.zio"                %% "zio-test"                % zioVersion       % "test",
        "dev.zio"                %% "zio-test-sbt"            % zioVersion       % "test",
        "org.elasticmq"          %% "elasticmq-rest-sqs"      % elasticMqVersion % "test",
        "org.elasticmq"          %% "elasticmq-core"          % elasticMqVersion % "test"
      ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12 | 13)) =>
          Seq("org.typelevel" %% "kind-projector" % "0.13.4" cross CrossVersion.full)
        case _                  =>
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
        case _             =>
          Nil
      }),
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
    )

lazy val docs = project
  .in(file("zio-sqs-docs"))
  .enablePlugins(WebsitePlugin)
  .settings(
    moduleName := "zio-sqs-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    projectName := "ZIO SQS",
    mainModuleName := (sqs / moduleName).value,
    projectStage := ProjectStage.ProductionReady,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(sqs)
  )
  .dependsOn(sqs)
