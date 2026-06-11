import scoverage.ScoverageKeys
import uk.gov.hmrc.DefaultBuildSettings.*

val appName = "email"

lazy val TemplateTest = config("tt") extend Test

val allPhases = "tt->test;test->test;test->compile;compile->compile"
val allItPhases = "tit->it;it->it;it->compile;compile->compile"

Global / majorVersion := 11
Global / scalaVersion := "3.6.1"

val excludedPackages: Seq[String] = Seq(
  "<empty>",
  "Reverse.*",
  ".*Routes.*",
  ".*\\$anon.*",
  "uk.gov.hmrc.email.controllers.testonly",
  "testOnlyDoNotUseInAppConf.*"
)

lazy val scoverageSettings =
  Seq(
    ScoverageKeys.coverageExcludedPackages := excludedPackages.mkString(","),
    ScoverageKeys.coverageMinimumStmtTotal := 90.00,
    ScoverageKeys.coverageFailOnMinimum := true,
    ScoverageKeys.coverageHighlighting := true
  )

lazy val microservice = Project(appName, file("."))
  .enablePlugins(PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) // Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(routesGenerator := InjectedRoutesGenerator)
  .settings(routesImport ++= Seq("uk.gov.hmrc.email.utils.{PositiveInteger, NonEmptyString}"))
  .settings(defaultSettings()*)
  .settings(
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    dependencyOverrides ++= AppDependencies.overrides,
    Test / parallelExecution := false,
    Test / fork := false,
    retrieveManaged := true,
    scalacOptions ++= Seq("-Xmax-inlines", "64"),
    scalacOptions ++= List(
      "-feature",
      "-language:implicitConversions",
      // Silence "Flag -XXX set repeatedly"
      "-Wconf:msg=Flag.*repeatedly:s",
      // Silence unused warnings on Play `routes` files
      "-Wconf:src=routes/.*:s"
    )
  )
  .settings(scoverageSettings.settings*)
  .settings(inConfig(TemplateTest)(Defaults.testSettings)*)

lazy val it = (project in file("it"))
  .enablePlugins(PlayScala)
  .dependsOn(`microservice` % "test->test")
  .settings(
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test
  )
