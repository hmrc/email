import play.sbt.PlayImport.ws
import sbt.ExclusionRule
import sbt.*

object AppDependencies {

  private val akkaVersion = "3.0.1"
  private val pekkoVersion = "1.0.3"
  private val pekkoHttpVersion = "1.0.0"
  private val akkaHttpVersion = "10.2.7"
  private val bootstrapPlayVersion = "10.7.0"
  private val catsEffect = "2.5.3"
  private val hmrcMongo = "2.12.0"
  private val domainVersion = "13.0.0"

  val compile: Seq[ModuleID] = Seq(
    ws excludeAll ExclusionRule("org.apache.httpcomponents"),
    "uk.gov.hmrc"       %% "bootstrap-backend-play-30"         % bootstrapPlayVersion,
    "org.typelevel"     %% "cats-core"                         % "2.9.0",
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-work-item-repo-play-30" % hmrcMongo,
    "uk.gov.hmrc"       %% "cluster-work-throttling"           % "9.2.0",
    "net.codingwell"    %% "scala-guice"                       % "5.1.1",
    "org.playframework" %% "play-streams"                      % akkaVersion,
    "org.apache.pekko"  %% "pekko-stream"                      % pekkoVersion,
    "commons-codec"      % "commons-codec"                     % "20041127.091804",
    "com.jcraft"         % "jsch"                              % "0.1.55",
    "org.typelevel"     %% "cats-effect"                       % catsEffect
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"       %% "bootstrap-test-play-30"    % bootstrapPlayVersion % Test,
    "org.scalatestplus" %% "mockito-4-11"              % "3.2.18.0"           % Test,
    "org.scalatestplus" %% "scalacheck-1-18"           % "3.2.18.0"           % Test,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-30"   % hmrcMongo            % Test,
    "org.jsoup"          % "jsoup"                     % "1.17.2"             % Test,
    "uk.gov.hmrc"       %% "domain-play-30"            % domainVersion        % Test,
    "org.apache.pekko"  %% "pekko-testkit"             % pekkoVersion         % Test,
    "org.apache.pekko"  %% "pekko-stream-testkit"      % pekkoVersion         % Test,
    "org.apache.pekko"  %% "pekko-actor-testkit-typed" % pekkoVersion         % Test,
    "org.typelevel"     %% "cats-effect"               % catsEffect           % Test
  )
  val overrides: Seq[ModuleID] = Seq(
    "org.apache.pekko"  %% "pekko-stream"               % pekkoVersion,
    "com.typesafe.akka" %% "akka-slf4j"                 % akkaVersion,
    "org.apache.pekko"  %% "pekko-slf4j"                % pekkoVersion,
    "org.apache.pekko"  %% "pekko-actor-typed"          % pekkoVersion,
    "com.typesafe.akka" %% "akka-serialization-jackson" % "2.8.0",
    "org.apache.pekko"  %% "pekko-http"                 % pekkoHttpVersion
  )
}
