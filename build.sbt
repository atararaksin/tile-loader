name := "tile-loader"

version := "0.1"

scalaVersion := "2.12.8"

resolvers ++= Seq(
  "locationtech-releases" at "https://repo.locationtech.org/content/groups/releases",
  "locationtech-snapshots" at "https://repo.locationtech.org/content/groups/snapshots",
  "Azavea Public Builds" at "https://dl.bintray.com/azavea/geotrellis"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http" % "10.1.8",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.8",
  "com.typesafe.akka" %% "akka-stream" % "2.5.23",
  "com.typesafe.akka" %% "akka-actor" % "2.5.23",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "org.locationtech.geotrellis" %% "geotrellis-spark" % "3.0.0-M3",
  "org.locationtech.geotrellis" %% "geotrellis-s3" % "3.0.0-M3",
  "org.scalatest" %% "scalatest" % "3.0.8" % Test
)
