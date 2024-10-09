addSbtPlugin("org.scalameta"    % "sbt-scalafmt"       % "2.4.3")
addSbtPlugin("com.github.sbt"   % "sbt-ci-release"     % "1.7.0")
addSbtPlugin("com.github.sbt"   % "sbt-github-actions" % "0.24.0")
addSbtPlugin("dev.zio"          % "zio-sbt-website"    % "0.4.0-alpha.28")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"        % "0.6.4")

resolvers ++= Resolver.sonatypeOssRepos("public")
