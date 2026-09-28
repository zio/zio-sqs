val zioSbtVersion = "0.6.0"

addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % zioSbtVersion)
addSbtPlugin("dev.zio" % "zio-sbt-ci"        % zioSbtVersion)
addSbtPlugin("dev.zio" % "zio-sbt-website"   % zioSbtVersion)

addSbtPlugin("org.scalameta"    % "sbt-scalafmt"       % "2.6.2")
addSbtPlugin("ch.epfl.scala"    % "sbt-scalafix"       % "0.14.6")
addSbtPlugin("com.github.sbt"   % "sbt-github-actions" % "0.30.0")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"        % "0.7.0")

resolvers ++= Resolver.sonatypeOssRepos("public")
