addSbtPlugin("org.scalameta"  % "sbt-scalafmt"    % "2.4.3")
addSbtPlugin("com.github.sbt" % "sbt-ci-release"  % "1.6.0")
addSbtPlugin("dev.zio"        % "zio-sbt-website" % "0.3.2")

resolvers ++= Resolver.sonatypeOssRepos("public")
