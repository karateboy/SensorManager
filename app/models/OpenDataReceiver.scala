package models

import akka.actor.{Actor, ActorLogging, Cancellable, Props}
import com.github.tototoshi.csv.{CSVParser, CSVReader}
import play.api.Logger
import play.api.libs.ws.{WS, WSClient}

import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source
import scala.util.Success

object OpenDataReceiver {
  val props = Props[OpenDataReceiver]

  case object GetEpaHourData

  case object GetEpaCurrentData

  trait Factory {
    def apply(): Actor
  }
}

import java.util.Date


class OpenDataReceiver @Inject()(sysConfig: SysConfig, wsClient: WSClient, monitorOp: MonitorOp, recordOp: RecordOp)
                                () extends Actor with ActorLogging {

  import OpenDataReceiver._

  self ! GetEpaCurrentData

  var timer: Option[Cancellable] = None

  Logger.info("Open Data receiver start...")
  def getData(limit: Int)={
    import com.github.nscala_time.time.Imports._
    val url = s"https://data.epa.gov.tw/api/v1/aqx_p_432?format=csv&limit=${limit}&api_key=9be7b239-557b-4c10-9775-78cadfc555e9"

    for (ret <- wsClient.url(url).get()) yield {
      var latestRecordTime = DateTime.now() - 1.day
      val reader = CSVReader.open(Source.fromString(ret.body))
      val records = {
        for {siteMap <- reader.iteratorWithHeaders
             siteName = siteMap("SiteName")
             id = siteMap("SiteId")
             time = siteMap("PublishTime")
             pm25 <- siteMap.get("PM2.5_AVG")
             pm10 <- siteMap.get("PM10")
             lng <- siteMap.get("Longitude")
             lat <- siteMap.get("Latitude")
             } yield {

          val epaId = Monitor.epaID(id)
          monitorOp.ensureMonitor(epaId, siteName, Seq(MonitorType.PM10, MonitorType.PM25),
            Seq(MonitorTag.EPA))
          val dt = DateTime.parse(time.trim(), DateTimeFormat.forPattern("YYYY-MM-dd hh:mm"))
          Logger.info(s"${dt} ${siteName}")
          if(latestRecordTime < dt)
            latestRecordTime = dt

          val mtRecords = Seq(
            MtRecord(MonitorType.PM10, pm10.toDouble, MonitorStatus.NormalStat),
            MtRecord(MonitorType.PM25, pm25.toDouble, MonitorStatus.NormalStat)
          )
          val location = Some(GeoPoint(lng.toDouble, lat.toDouble))
          RecordList(dt.toDate, epaId, location, mtRecords)
        }
      }
      recordOp.insertManyRecord(records.toList)(recordOp.MinCollection)
    }
  }

  /*
  .andThen({
        case Success(_)=>
          sysConfig.setEpaLastDataTime(latestRecordTime.toDate)

      })
   */
  def receive = {
    case GetEpaCurrentData =>
      for (dt <- sysConfig.getEpaLastDataTime()) {
        val latest = dt.toInstant
        val duration = Duration.between(latest, Instant.now)
        if (duration.get(ChronoUnit.MINUTES) < 60) {
          val delay = 60 - duration.get(ChronoUnit.MINUTES) + 1
          timer = Some(context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(delay, TimeUnit.MINUTES), self, GetEpaCurrentData))
        }else{
          getData(100)
        }
      }
  }

  override def postStop =
    for (t <- timer)
      t.cancel()

}
