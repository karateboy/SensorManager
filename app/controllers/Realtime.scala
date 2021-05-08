package controllers
import com.github.nscala_time.time.Imports._
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import javax.inject._

class Realtime @Inject()
(monitorTypeOp: MonitorTypeOp, dataCollectManagerOp: DataCollectManagerOp,
 monitorStatusOp: MonitorStatusOp, recordOp: RecordOp, monitorOp: MonitorOp) extends Controller {
  val overTimeLimit = 6
  case class MonitorTypeStatus(desp: String, value: String, unit: String, instrument: String, status: String, classStr: Seq[String], order: Int)
  def MonitorTypeStatusList() = Security.Authenticated.async {
    implicit request =>


      implicit val mtsWrite = Json.writes[MonitorTypeStatus]

      val result =
        for (dataMap <- dataCollectManagerOp.getLatestData()) yield {
          val list =
            for {
              mt <- monitorTypeOp.realtimeMtvList
              recordOpt = dataMap.get(mt)
            } yield {
              val mCase = monitorTypeOp.map(mt)
              val measuringByStr = mCase.measuringBy.map {
                instrumentList =>
                  instrumentList.mkString(",")
              }.getOrElse("??")

              if (recordOpt.isDefined) {
                val record = recordOpt.get
                val duration = new Duration(record.time, DateTime.now())
                val (overInternal, overLaw) = monitorTypeOp.overStd(mt, record.value)
                val status = if (duration.getStandardSeconds <= overTimeLimit)
                  monitorStatusOp.map(record.status).desp
                else
                  "通訊中斷"

                MonitorTypeStatus(mCase.desp, monitorTypeOp.format(mt, Some(record.value)), mCase.unit, measuringByStr,
                  monitorStatusOp.map(record.status).desp,
                  MonitorStatus.getCssClassStr(record.status, overInternal, overLaw), mCase.order)
              } else {
                MonitorTypeStatus(mCase.desp, monitorTypeOp.format(mt, None), mCase.unit, measuringByStr,
                  "通訊中斷",
                  Seq("abnormal_status"), mCase.order)
              }
            }
          Ok(Json.toJson(list))
        }

      result
  }

  def sensorSummary = Security.Authenticated.async {
    import recordOp.summaryWrites
    val f = recordOp.getLast24HrCount(recordOp.MinCollection)
    for(ret <- f) yield {
      Ok(Json.toJson(ret))
    }
  }

  def sensorDisconnectSummary= Security.Authenticated.async {
    import recordOp.summaryWrites
    val f = recordOp.getDisconnectSummary(recordOp.MinCollection)("","", "", "")
    for(ret <- f) yield {

      Ok(Json.toJson(ret))
    }
  }

  def epaStatus()= Security.Authenticated.async {
      implicit request =>
        import recordOp.monitorRecordWrite
        val f = recordOp.getLatestEpaStatus(TableType.mapCollection(TableType.min))
        for (recordList <- f) yield {
          recordList.foreach(r=> {
            if(monitorOp.map.contains(r._id)) {
              r.shortCode =monitorOp.map(r._id).shortCode
              r.code = monitorOp.map(r._id).code
              r.tags = Some(monitorOp.map(r._id).tags)
            }
          })
          Ok(Json.toJson(recordList))
        }
    }

  def sensorStatus(pm25Threshold:String, county:String, district:String, sensorType:String) =
    Security.Authenticated.async {
      implicit request =>
        import recordOp.monitorRecordWrite
        val f = recordOp.getLatestSensorStatus(TableType.mapCollection(TableType.min))(pm25Threshold, county, district, sensorType)
        for (recordList <- f) yield {
          recordList.foreach(r=> {
            if(monitorOp.map.contains(r._id)) {
              r.shortCode =monitorOp.map(r._id).shortCode
              r.code = monitorOp.map(r._id).code
              r.tags = Some(monitorOp.map(r._id).tags)
            }
          })
          Ok(Json.toJson(recordList))
        }
    }
}