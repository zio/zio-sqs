addSbtPlugin("org.scalameta"    % "sbt-scalafmt"       % "2.5.4")
addSbtPlugin("com.github.sbt"   % "sbt-ci-release"     % "1.11.1")
addSbtPlugin("com.github.sbt"   % "sbt-github-actions" % "0.25.0")
addSbtPlugin("dev.zio"          % "zio-sbt-ecosystem"  % "0.4.0-alpha.31")
addSbtPlugin("dev.zio"          % "zio-sbt-ci"         % "0.4.0-alpha.31")
addSbtPlugin("dev.zio"          % "zio-sbt-website"    % "0.4.0-alpha.31")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"        % "0.6.4")

resolvers ++= Resolver.sonatypeOssRepos("public")
