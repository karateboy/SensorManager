package models

import akka.actor.{Actor, ActorLogging, Cancellable}
import com.mongodb.client.model.UpdateManyModel
import models.ModelHelper.errorHandler
import play.api.Logger
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{JsError, JsPath, Json, Reads}
import play.api.libs.ws.WSClient

import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

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

  case object GetEpaHourData

  case object GetEpaCurrentData

  implicit val epaRecordReads: Reads[EpaRecord] = {
    val builder = {
      (JsPath \ "SiteName").read[String] and
        (JsPath \ "County").read[String] and
        (JsPath \ "PM10").read[String] and
        (JsPath \ "PM2.5").read[String] and
        (JsPath \ "PublishTime").read[String] and
        (JsPath \ "Longitude").read[String] and
        (JsPath \ "Latitude").read[String] and
        (JsPath \ "SiteId").read[String]
    }
    (builder) (EpaRecord.apply _)
  }
}


class OpenDataReceiver @Inject()(sysConfig: SysConfig, wsClient: WSClient, monitorOp: MonitorOp, recordOp: RecordOp)
                                () extends Actor with ActorLogging {

  import OpenDataReceiver._

  self ! GetEpaCurrentData

  var timer: Option[Cancellable] = None

  Logger.info("Open Data receiver start...")

  def receive = {
    case GetEpaCurrentData =>
      for (dt <- sysConfig.getEpaLastDataTime()) {
        val latest = dt.toInstant
        val duration = Duration.between(latest, Instant.now)
        if (duration.getSeconds < 60 * 60) {
          val delay = 60 - duration.getSeconds / +1
          timer = Some(context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(delay, TimeUnit.MINUTES), self, GetEpaCurrentData))
        } else {
          getData(100)
        }
      }
  }


  def getData(limit: Int) = {
    Logger.info("Get EPA current data")
    import com.github.nscala_time.time.Imports._
    val url = s"https://data.epa.gov.tw/api/v1/aqx_p_432?format=json&limit=${limit}&api_key=9be7b239-557b-4c10-9775-78cadfc555e9"

    val f = wsClient.url(url).get()
    f onFailure (errorHandler)

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

            val dt = DateTime.parse(time.trim(), DateTimeFormat.forPattern("YYYY/MM/dd HH:mm:ss"))
            if (latestRecordTime < dt)
              latestRecordTime = dt

            def getMtRecord(mt: String, valueStr: String) = {
              try {
                val mtValue = valueStr.toDouble
                Some(MtRecord(mt, mtValue, MonitorStatus.NormalStat))
              } catch {
                case _ : Throwable=>
                  None
              }
            }

            val mtRecords = Seq(
              getMtRecord(MonitorType.PM10, pm10),
              getMtRecord(MonitorType.PM25, pm25)
            ).flatten

            val location = Some(Seq(lng.toDouble, lat.toDouble))
            RecordList(dt.toDate, epaId, location, mtRecords)
        }

        Logger.info(s"Total ${recordLists.length} records")

        val f = recordOp.upsertManyRecord(recordLists)(recordOp.MinCollection)
        f onComplete ({
          case Success(_) =>
            sysConfig.setEpaLastDataTime(latestRecordTime.toDate)
          case Failure(ex) =>
            Logger.error("failed", ex)
        })
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

  override def postStop =
    for (t <- timer)
      t.cancel()


  case class EpaResult(records: Seq[EpaRecord])

  case class EpaDatta(result: EpaResult)

}
