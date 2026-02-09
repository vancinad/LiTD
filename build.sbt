ThisBuild / scalaVersion := "3.3.1"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "litd",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % "2.8.5",
      "com.typesafe.akka" %% "akka-http" % "10.5.2",
      "com.typesafe.akka" %% "akka-stream" % "2.8.5",
      "com.typesafe" % "config" % "1.4.2",
      "de.heikoseeberger" %% "akka-http-circe" % "1.39.2",
      "io.circe" %% "circe-core" % "0.14.6",
      "io.circe" %% "circe-generic" % "0.14.6",
      "io.circe" %% "circe-parser" % "0.14.6",
      "org.mongodb.scala" %% "mongo-scala-driver" % "4.11.0",
      "org.scalatest" %% "scalatest" % "3.2.18" % Test
    )
  )
