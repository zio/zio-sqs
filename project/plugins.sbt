addSbtPlugin("org.scalameta"  % "sbt-scalafmt"    % "2.5.2")
addSbtPlugin("com.github.sbt" % "sbt-ci-release"  % "1.5.10")
addSbtPlugin("dev.zio"        % "zio-sbt-website" % "0.3.2")

resolvers ++= Resolver.sonatypeOssRepos("public")
