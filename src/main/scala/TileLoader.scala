package io.geoalert.tileloader

import java.nio.file.{Files, Path, Paths}
import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.http.scaladsl.model._
import geotrellis.proj4.{LatLng, WebMercator}
import geotrellis.spark.SpatialKey
import geotrellis.spark.tiling.ZoomedLayoutScheme

import scala.util.{Success, Try}
import geotrellis.vector._
import geotrellis.vector.io.json._
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.scaladsl.{FileIO, Sink, Source}
import cats.implicits._

import scala.concurrent.duration._

object TileLoader extends App {
  implicit val system = ActorSystem("tile-loader")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val conSettings = ConnectionPoolSettings(system).withMaxRetries(5)

  val z = sys.env.getOrElse("ZOOM", "14").toInt
  val targetPath = Paths.get(sys.env.getOrElse("TARGET_PATH", "/tile-loader/tiles"))
  val tileUrl = sys.env("TILE_URL")

  targetPath.toFile.mkdirs()

  val geomBounds = scala.io.Source.fromResource("bounds.geojson")
    .getLines()
    .mkString("")
    .parseGeoJson[JsonFeatureCollection]()
    .getAllGeometries()
    .flatMap {
      case p: Polygon =>  List(p)
      case m: MultiPolygon =>  m.polygons.toList
      case _ => List[Polygon]()
    }
    .map(_.reproject(LatLng, WebMercator))
    .toMultiPolygon()
  val extent = geomBounds.envelope

  val layout = ZoomedLayoutScheme(WebMercator).levelForZoom(z).layout

  val keyBounds = layout.mapTransform.extentToBounds(extent)

  def getTilePath(key: SpatialKey) =
    targetPath.resolve(s"${key.col}-${key.row}.jpg")

  def keyIsInBounds(key: SpatialKey, bounds: MultiPolygon) =
    bounds.intersects(key.extent(layout))

  val keys = for {
    x <- (keyBounds.colMin to keyBounds.colMax).toStream
    y <- (keyBounds.rowMin to keyBounds.rowMax).toStream
    key = SpatialKey(x, y) if (keyIsInBounds(key, geomBounds) && !Files.exists(getTilePath(key)))
  } yield key

  def loadTile(key: SpatialKey) = {
    val url = tileUrl.replace("{z}", z.toString)
      .replace("{x}", key.col.toString)
      .replace("{y}", key.row.toString)

    def responseOrFail[T](responseTry: (Try[HttpResponse], T)) = responseTry match {
      case (Success(r), _) if r.status.isSuccess() => r
      case _ => sys.error(s"Unable to download from $url")
    }

    def writeFile(dst: Path)(httpResponse : HttpResponse) =
      httpResponse.entity.dataBytes.runWith(FileIO.toPath(dst))

    val requestResponseFlow = Http().superPool[Unit](settings = conSettings)

    Source.single((Get(url), ()))
      .via(requestResponseFlow)
      .map(responseOrFail)
      .runWith(Sink.foreach(writeFile(getTilePath(key))))
  }

  println(s"Starting to download tiles from $tileUrl to $targetPath")

  keys.traverse(loadTile)

  system.scheduler.schedule(
    0 seconds,
    60 seconds,
    () => println(s"${LocalDateTime.now()} Current amount of tiles loaded: ${Files.list(targetPath).count()}")
  )
}
