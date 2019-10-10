package io.geoalert.tileloader


import java.nio.file.{Files, Path, Paths}

import akka.Done
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
import akka.stream.scaladsl.{FileIO, Sink, Source}
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.Future
import scala.concurrent.duration._

object TileLoader extends App with StrictLogging {
  implicit val system = ActorSystem("tile-loader")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val z = sys.env.getOrElse("ZOOM", "14").toInt
  val targetPath = Paths.get(sys.env.getOrElse("TARGET_PATH", "/tile-loader/tiles"))
  val tileUrl = sys.env("TILE_URL")
  val sliceSize = 1000000

  targetPath.toFile.mkdirs()

  val geomBounds = scala.io.Source.fromResource("bounds.geojson")
    .getLines()
    .mkString("")
    .parseGeoJson[JsonFeatureCollection]()
    .getAllGeometries()
    .flatMap {
      case p: Polygon => List(p)
      case m: MultiPolygon => m.polygons.toList
      case _ => List[Polygon]()
    }
    .map(_.reproject(LatLng, WebMercator))
    .toMultiPolygon()
  val extent = geomBounds.envelope

  val layout = ZoomedLayoutScheme(WebMercator).levelForZoom(z).layout

  val keyBounds = layout.mapTransform.extentToBounds(extent)

  def getTilePath(key: SpatialKey, n: Int) =
    targetPath.resolve((n / sliceSize).toString).resolve(s"${key.col}-${key.row}.jpg")

  val keys = layout.mapTransform
    .keysForGeometry(geomBounds)
    .toList
    .sorted
    .zipWithIndex

  def downloadTile(key: SpatialKey, n: Int, attempts: Int): Future[Done] = {
    if (attempts == 0) {
      Future.failed(new RuntimeException(s"Exceeded amount of attemps for $key"))
    } else {
      val url = tileUrl.replace("{z}", z.toString)
        .replace("{x}", key.col.toString)
        .replace("{y}", key.row.toString)

      def responseOrFail[T](responseTry: (Try[HttpResponse], T)) = responseTry match {
        case (Success(r), _) if r.status.isSuccess() => r
        case _ => sys.error(s"Unable to download from $url")
      }

      def writeFile(dst: Path)(httpResponse: HttpResponse) =
        httpResponse.entity.dataBytes.runWith(FileIO.toPath(dst))

      val requestResponseFlow = Http().superPool[Unit]()

      val path = getTilePath(key, n)
      path.getParent.toFile.mkdirs()

      Source.single((Get(url), ()))
        .via(requestResponseFlow)
        .map(responseOrFail)
        .runWith(Sink.foreach(writeFile(path)))
        .recoverWith { case _ => downloadTile(key, n, attempts - 1) }
    }
  }

  logger.info(s"Starting to download tiles from $tileUrl to $targetPath")

  system.scheduler.schedule(
    0 seconds,
    300 seconds,
    () => {
      logger.info(s"Current amount of tiles loaded: ${Files.walk(targetPath, 2).count()}")
    }
  )

  def batchDownload(keys: List[(SpatialKey, Int)], batchSize: Int): Unit = {
    if (!keys.isEmpty) {
      val (batch, tail) = keys.splitAt(batchSize)
      batch.filterNot(e => Files.exists(getTilePath(e._1, e._2)))
        .traverse(e => downloadTile(e._1, e._2, 2))
        .onComplete {
          case _ => batchDownload(tail, batchSize)
        }
    }
  }

  batchDownload(keys, 64)
}
