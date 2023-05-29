package models

import akka.actor.{Actor, Cancellable}
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import play.api._
import play.api.libs.json.{JsError, Json, Reads}
import play.api.libs.ws._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


object OpenDataReceiver {
  case object GetEpaHourData

  case class ReloadEpaData(start: DateTime, end: DateTime)
}

@Singleton
class OpenDataReceiver @Inject()(monitorTypeOp: MonitorTypeOp, monitorOp: MonitorOp, recordOp: RecordOp,
                                 WSClient: WSClient,
                                 sysConfig: SysConfig) extends Actor {

  import OpenDataReceiver._
  import com.github.nscala_time.time.Imports._

  val timerOpt: Option[Cancellable] = {
    import scala.concurrent.duration._
    Some(context.system.scheduler.schedule(FiniteDuration(5, SECONDS), FiniteDuration(1, HOURS), self, GetEpaHourData))
  }

  def receive: Receive = {
    case GetEpaHourData =>
      for {startDate <- sysConfig.getEpaLastDataTime()
           start = new DateTime(startDate).minusDays(7).withTimeAtStartOfDay()
           end = DateTime.tomorrow().withTimeAtStartOfDay()
           } {

        Logger.info(s"Get EpaData ${start.toString("yyyy-MM-d")} => ${end.toString("yyyy-MM-d")}")
        for (success <- fetchEpaHourData(start, end) if success) {
            Logger.info(s"Get EpaData ${start.toString("yyyy-MM-d")} => ${end.toString("yyyy-MM-d")} successful")
            sysConfig.setEpaLastDataTime(end)
        }
      }

    case ReloadEpaData(start, end) =>
      Logger.info(s"reload EpaData ${start.toString("yyyy-MM-d")} => ${end.toString("yyyy-MM-d")}")
      fetchEpaHourData(start, end)
  }

  private case class RecordList2(var mtDataList: Seq[MtRecord], _id: RecordListID)

  private def fetchEpaHourData(start: DateTime, end: DateTime): Future[Boolean] = {
    val upstream = "http://59.124.12.181:20000"
    val epaMonitorsIdList = for (idx <- 1 to 85) yield s"Epa$idx"
    val epaMonitorIDs = epaMonitorsIdList.mkString(":")
    val startNum = start.getMillis
    val endNum = end.getMillis
    val f = WSClient.url(s"$upstream/HourRecord/$epaMonitorIDs/$startNum/$endNum").get()
    f onFailure errorHandler
    for (response <- f) yield {
      implicit val r1: Reads[RecordListID] = Json.reads[RecordListID]
      implicit val r2: Reads[MtRecord] = Json.reads[MtRecord]
      implicit val r3: Reads[RecordList2] = Json.reads[RecordList2]
      val ret = response.json.validate[Seq[RecordList2]]
      ret.fold(
        err => {
          Logger.error(JsError.toJson(err).toString())
          false
        },
        recordList2s => {

          val recordLists = recordList2s.map(r => RecordList(time = r._id.time, monitor = r._id.monitor.toLowerCase, mtDataList = r.mtDataList))
          Logger.info(s"Total ${recordLists.size} records fetched.")
          recordOp.upsertManyRecord(recordLists)(recordOp.HourCollection)
          recordOp.upsertManyRecord(recordLists)(recordOp.MinCollection)
          true
        }
      )
    }
  }


  override def postStop: Unit = {
    for (timer <- timerOpt)
      timer.cancel()
  }

}

