addSbtPlugin("org.scalameta"  % "sbt-scalafmt"    % "2.4.3")
addSbtPlugin("com.github.sbt" % "sbt-ci-release"  % "1.5.10")
addSbtPlugin("dev.zio"        % "zio-sbt-website" % "0.1.5+27-a79a4f13-SNAPSHOT")

resolvers ++= Resolver.sonatypeOssRepos("public")
