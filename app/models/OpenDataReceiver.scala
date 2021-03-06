package models

import akka.actor.{Actor, ActorLogging}
import models.ModelHelper.errorHandler
import play.api.Logger
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{JsError, JsPath, Json, Reads}
import play.api.libs.ws.WSClient

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
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
                                 recordOp: RecordOp)
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
        val start = new DateTime(epaLast)
        val end = DateTime.now().withMillisOfDay(0).minusDays(1)
        if (start < end) {
          getEpaHourData(start, end)
        }
      }
  }

  def getEpaHourData(start: DateTime, end: DateTime) {
    Logger.debug(s"get EPA data start=${start.toString()} end=${end.toString()}")
    val limit = 500

    def parser(node: Elem) = {
      import scala.collection.mutable.Map
      import scala.xml.Node
      val recordMap = Map.empty[String, Map[DateTime, Map[String, Double]]]

      def filter(dataNode: Node) = {
        val monitorDateOpt = dataNode \ "MonitorDate".toUpperCase()
        val mDate =
          try {
            DateTime.parse(s"${monitorDateOpt.text.trim()}", DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss"))
          } catch {
            case _: Exception =>
              DateTime.parse(s"${monitorDateOpt.text.trim()}", DateTimeFormat.forPattern("YYYY-MM-dd"))
          }
        start <= mDate && mDate <= end
      }

      def processData(dataNode: Node) {
        val siteId = dataNode \ "SiteId".toUpperCase()
        val siteName = dataNode \ "SiteName".toUpperCase()
        val itemId = dataNode \ "ItemId".toUpperCase()
        val monitorDateOpt = dataNode \ "MonitorDate".toUpperCase()

        try {
          //Filter interested EPA monitor
          if (itemId.text.trim().toInt == 33) {
            val epaId = Monitor.epaID(siteId.text.trim())
            monitorOp.ensureMonitor(epaId, siteName.text.trim(), Seq(MonitorType.PM10, MonitorType.PM25),
              Seq(MonitorTag.EPA))

            monitorTypeOp.ensureMeasuring(MonitorType.PM25, "OpenData")
            val monitorType = MonitorType.PM25
            val mDate = try {
              DateTime.parse(s"${monitorDateOpt.text.trim()}", DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss"))
            } catch {
              case _: Exception =>
                DateTime.parse(s"${monitorDateOpt.text.trim()}", DateTimeFormat.forPattern("YYYY-MM-dd"))
            }

            val monitorNodeValueSeq =
              for (v <- 0 to 23) yield {
                val monitorValue = try {
                  Some((dataNode \ "MonitorValue%02d".format(v).toUpperCase()).text.trim().toDouble)
                } catch {
                  case x: Throwable =>
                    None
                }
                (mDate + v.hour, monitorValue)
              }

            val timeMap = recordMap.getOrElseUpdate(epaId, Map.empty[DateTime, Map[String, Double]])
            for {(mDate, mtValueOpt) <- monitorNodeValueSeq} {
              val mtMap = timeMap.getOrElseUpdate(mDate, Map.empty[String, Double])
              for (mtValue <- mtValueOpt)
                mtMap.put(monitorType, mtValue)
            }
          }
        } catch {
          case x: Throwable =>
            Logger.error("failed", x)
        }
      }

      val data = node \ "data"

      val qualifiedData = data.filter(filter)

      qualifiedData.map {
        processData
      }

      val recordLists =
        for {
          monitorMap <- recordMap
          monitor = monitorMap._1
          timeMaps = monitorMap._2
          dateTime <- timeMaps.keys.toList.sorted
        } yield {
          val mtRecords = for (mtValue <- timeMaps(dateTime)) yield
            MtRecord(mtValue._1, mtValue._2, MonitorStatus.NormalStat)
          RecordList(dateTime.toDate, monitor, None, mtRecords.toSeq)
        }

      if (recordLists.size != 0) {
        val f = recordOp.upsertManyRecord(recordLists.toList)(recordOp.HourCollection)
        f onFailure (errorHandler())
        f onComplete ({
          case Success(ret) =>
            if (ret.getUpserts().size() != 0)
              Logger.debug(s"EPA ${ret.getUpserts().size()} records have been upserted.")
          case Failure(ex) =>
            Logger.error("failed", ex)
        })
      }

      qualifiedData.size
    }

    def getData(skip: Int) {
      val url = s"https://data.epa.gov.tw/api/v2/aqx_p_15?format=xml&offset=${skip}&limit=${limit}&api_key=1f4ca8f8-8af9-473d-852b-b8f2d575f26a"
      val future =
        wsClient.url(url).get().map {
          response =>
            try {
              parser(response.xml)
            } catch {
              case ex: Exception =>
                Logger.error(ex.toString())
                throw ex
            }
        }
      future onFailure (errorHandler())
      future onSuccess ({
        case ret: Int =>
          if (ret < limit) {
            Logger.info(s"Import EPA ${start.toString()} to ${end} complete")
            sysConfig.setEpaLastDataTime(end.toDate)
          } else
            getData(skip + limit)
      })
    }

    getData(0)
  }


  def getCurrentData(limit: Int) = {
    import com.github.nscala_time.time.Imports._
    val url = s"https://data.epa.gov.tw/api/v2/aqx_p_432?format=json&limit=${limit}&api_key=1f4ca8f8-8af9-473d-852b-b8f2d575f26a"

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
            RecordList(dt.toDate, epaId, location, mtRecords)
        }

        if (recordLists.length != 0) {
          val f = recordOp.upsertManyRecord(recordLists)(recordOp.MinCollection)
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

  override def postStop = {
    timer.cancel()
    timer2.cancel()
  }


  case class EpaResult(records: Seq[EpaRecord])

}
