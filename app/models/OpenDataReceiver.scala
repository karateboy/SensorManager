package models

import akka.actor.{Actor, ActorLogging}
import models.ModelHelper.errorHandler
import play.api.{Configuration, Logger}
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{JsError, JsPath, Json, Reads}
import play.api.libs.ws.WSClient

import javax.inject.Inject
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.xml.Elem

object OpenDataReceiver {
  trait Factory {
    def apply(): Actor
  }

  case class EpaRecord(SiteName: String,
                       County: String,
                       PM10: String,
                       PM25: String,
                       PublishTime: String,
                       Longitude: String,
                       Latitude: String,
                       SiteId: String)


  case object GetEpaCurrentData

  implicit val epaRecordReads: Reads[EpaRecord] = {
    val builder = {
      (JsPath \ "SiteName".toLowerCase()).read[String] and
        (JsPath \ "County".toLowerCase()).read[String] and
        (JsPath \ "PM10".toLowerCase()).read[String] and
        (JsPath \ "PM2.5".toLowerCase()).read[String] and
        (JsPath \ "PublishTime".toLowerCase()).read[String] and
        (JsPath \ "Longitude".toLowerCase()).read[String] and
        (JsPath \ "Latitude".toLowerCase()).read[String] and
        (JsPath \ "SiteId".toLowerCase()).read[String]
    }
    (builder) (EpaRecord.apply _)
  }

  case object GetEpaHourData
}


class OpenDataReceiver @Inject()(sysConfig: SysConfig, wsClient: WSClient, monitorOp: MonitorOp,
                                 monitorTypeOp: MonitorTypeOp,
                                 recordOp: RecordOp,
                                 configuration: Configuration)
                                () extends Actor with ActorLogging {

  import OpenDataReceiver._

  val timer = {
    import scala.concurrent.duration._
    context.system.scheduler.schedule(scala.concurrent.duration.Duration(1, SECONDS), scala.concurrent.duration.Duration(10, MINUTES), self, GetEpaCurrentData)
  }

  val timer2 = {
    import scala.concurrent.duration._
    context.system.scheduler.schedule(scala.concurrent.duration.Duration(10, SECONDS), scala.concurrent.duration.Duration(1, HOURS), self, GetEpaHourData)
  }

  Logger.info("Open Data receiver start...")

  import com.github.nscala_time.time.Imports._

  def receive = {
    case GetEpaCurrentData =>
      getCurrentData(100)

    case GetEpaHourData =>
      for (epaLast <- sysConfig.getEpaLastDataTime()) {
        val start = new DateTime(epaLast).withTimeAtStartOfDay().minusDays(7)
        val end = DateTime.now().withTimeAtStartOfDay()
        if (start < end)
          fetchEpaHourData(start, end)
      }
  }

  private def fetchEpaHourData(start: DateTime, end: DateTime): Option[Future[Boolean]] = {
    val upstreamOpt: Option[String] = {
      val ret =
        for {config <- configuration.getConfig("openData")
             enable <- config.getBoolean("enable") if enable
             } yield
          config.getString("upstream")

      ret.flatten
    }

    if (upstreamOpt.isEmpty) {
      Logger.error("openData did not config upstream!")
      Some(Future.successful(true))
    }else{
      for (upstream <- upstreamOpt) yield {
        val epaMonitors = monitorOp.map.values.filter(m=>m.tags.contains(MonitorTag.EPA))
        val startNum = start.getMillis
        val endNum = end.getMillis
        val epaMonitorsIDs = epaMonitors.map(m=>s"Epa${m._id.drop(3)}").mkString(":")
        val f = wsClient.url(s"$upstream/HourRecord/$epaMonitorsIDs/$startNum/$endNum").get()
        f onFailure errorHandler
        for (response <- f) yield {
          implicit val r3: Reads[RecordListID] = Json.reads[RecordListID]
          implicit val r2: Reads[MtRecord] = Json.reads[MtRecord]

          val ret = response.json.validate[Seq[RecordList]]
          ret.fold(
            err => {
              Logger.error(JsError.toJson(err).toString())
              false
            },
            recordLists => {
              Logger.info(s"Total ${recordLists.size} records fetched.")
              recordOp.upsertManyRecords(recordOp.HourCollection)(recordLists)
              true
            }
          )
        }
      }
    }


  }

  def getCurrentData(limit: Int) = {
    import com.github.nscala_time.time.Imports._
    val url = s"https://data.epa.gov.tw/api/v2/aqx_p_432?format=json&limit=${limit}&api_key=1f4ca8f8-8af9-473d-852b-b8f2d575f26a"

    val f = wsClient.url(url).get()
    f onFailure errorHandler

    for (ret <- f) yield {
      var latestRecordTime = DateTime.now() - 1.day
      implicit val epaResultReads = Json.reads[EpaResult]
      val retEpaResult = ret.json.validate[EpaResult]

      def handleEpaRecords(records: Seq[EpaRecord]) {
        val recordLists = records map {
          record =>
            val id = record.SiteId
            val time = record.PublishTime
            val pm25 = record.PM25
            val pm10 = record.PM10
            val lng = record.Longitude
            val lat = record.Latitude

            val epaId = Monitor.epaID(id)
            monitorOp.ensureMonitor(epaId, record.SiteName, Seq(MonitorType.PM10, MonitorType.PM25),
              Seq(MonitorTag.EPA))

            monitorTypeOp.ensureMeasuring(MonitorType.PM10, "OpenData")
            monitorTypeOp.ensureMeasuring(MonitorType.PM25, "OpenData")

            val dt = DateTime.parse(time.trim(), DateTimeFormat.forPattern("YYYY/MM/dd HH:mm:ss"))
            if (latestRecordTime < dt)
              latestRecordTime = dt

            def getMtRecord(mt: String, valueStr: String) = {
              try {
                val mtValue = valueStr.toDouble
                Some(MtRecord(mt, mtValue, MonitorStatus.NormalStat))
              } catch {
                case _: Throwable =>
                  Some(MtRecord(mt, 0, MonitorStatus.InvalidDataStat))
              }
            }

            val mtRecords = Seq(
              getMtRecord(MonitorType.PM10, pm10),
              getMtRecord(MonitorType.PM25, pm25)
            ).flatten

            val location = Some(Seq(lng.toDouble, lat.toDouble))
            RecordList(mtRecords, RecordListID(dt.toDate, epaId), location)
        }

        if (recordLists.nonEmpty) {
          val f = recordOp.upsertManyRecord(recordLists)(recordOp.HourCollection)
          f onComplete ({
            case Success(ret) =>
              if (ret.getUpserts.size() != 0)
                Logger.debug(s"EPA current upsert ${ret.getUpserts.size()} records")

            case Failure(ex) =>
              Logger.error("failed", ex)
          })
        }
      }

      retEpaResult.fold(
        err => {
          Logger.error(JsError.toJson(err).toString())
        },
        results => {
          try {
            handleEpaRecords(results.records)
          } catch {
            case ex: Exception =>
              Logger.error("failed to handled epaRecord", ex)
          }
        }
      )
    }

  }

  override def postStop: Unit = {
    timer.cancel()
    timer2.cancel()
  }


  private case class EpaResult(records: Seq[EpaRecord])

}
