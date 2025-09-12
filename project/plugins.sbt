val zioSbtVersion = "0.4.0-alpha.34"
addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % zioSbtVersion)
addSbtPlugin("dev.zio" % "zio-sbt-ci"        % zioSbtVersion)
addSbtPlugin("dev.zio" % "zio-sbt-website"   % zioSbtVersion)

addSbtPlugin("org.scalameta"    % "sbt-scalafmt"       % "2.5.5")
addSbtPlugin("ch.epfl.scala"    % "sbt-scalafix"       % "0.14.3")
addSbtPlugin("com.github.sbt"   % "sbt-github-actions" % "0.28.0")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"        % "0.6.4")

resolvers ++= Resolver.sonatypeOssRepos("public")
