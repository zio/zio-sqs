val zioSbtVersion = "0.4.4"
addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % zioSbtVersion)
addSbtPlugin("dev.zio" % "zio-sbt-ci"        % zioSbtVersion)
addSbtPlugin("dev.zio" % "zio-sbt-website"   % zioSbtVersion)

addSbtPlugin("org.scalameta"    % "sbt-scalafmt"       % "2.5.6")
addSbtPlugin("ch.epfl.scala"    % "sbt-scalafix"       % "0.14.6")
addSbtPlugin("com.github.sbt"   % "sbt-github-actions" % "0.29.0")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"        % "0.6.4")

resolvers ++= Resolver.sonatypeOssRepos("public")
