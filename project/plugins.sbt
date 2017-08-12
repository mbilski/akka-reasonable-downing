addSbtPlugin("com.typesafe.sbt"  % "sbt-git"         % "0.9.3")
addSbtPlugin("com.lucidchart"    % "sbt-scalafmt"    % "1.10")
addSbtPlugin("com.dwijnand"      % "sbt-travisci"    % "1.1.0")
addSbtPlugin("org.wartremover"   % "sbt-wartremover" % "2.1.1")
addSbtPlugin("de.heikoseeberger" % "sbt-header"      % "2.0.0")
addSbtPlugin("com.typesafe.sbt"  % "sbt-multi-jvm"   % "0.3.11")
addSbtPlugin("com.github.gseitz" % "sbt-release"     % "1.0.6")

libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.25" // Needed by sbt-git
