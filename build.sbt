ThisBuild / scalaVersion := "2.13.18"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "litd",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % "2.8.8",
      "com.typesafe.akka" %% "akka-http" % "10.5.3",
      "com.typesafe.akka" %% "akka-stream" % "2.8.8",
      "com.typesafe" % "config" % "1.4.5",
      "de.heikoseeberger" %% "akka-http-circe" % "1.39.2",
      "io.circe" %% "circe-core" % "0.14.15",
      "io.circe" %% "circe-generic" % "0.14.15",
      "io.circe" %% "circe-parser" % "0.14.15",
      "org.mongodb.scala" %% "mongo-scala-driver" % "5.6.3",
      "org.testcontainers" % "testcontainers" % "1.20.4" % Test,
      "org.testcontainers" % "mongodb" % "1.20.4" % Test,
      "org.scalatest" %% "scalatest" % "3.2.18" % Test,
      "ch.qos.logback" % "logback-classic" % "1.4.14"
    )
  )
