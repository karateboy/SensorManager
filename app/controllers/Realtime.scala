package controllers

import com.github.nscala_time.time.Imports._
import models._
import play.api.libs.json._
import play.api.mvc._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

class Realtime @Inject()
(monitorTypeOp: MonitorTypeOp, dataCollectManagerOp: DataCollectManagerOp,
 monitorStatusOp: MonitorStatusOp, recordOp: RecordOp, monitorOp: MonitorOp, sysConfig: SysConfig,
 errorReportOp: ErrorReportOp) extends Controller {
  val overTimeLimit = 6

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
    val f = recordOp.getLastestSensorSummary(recordOp.MinCollection)
    for (ret <- f) yield {
      Ok(Json.toJson(ret))
    }
  }

  def sensorDisconnected(county: String, district: String, sensorType: String) = Security.Authenticated.async {
    val today = DateTime.now().withMillisOfDay(0).toDate
    for (reports <- errorReportOp.get(today)) yield {
      val monitors: Seq[Monitor] = {
        if (reports.isEmpty)
          Seq.empty[Monitor]
        else {
          for (sensorID <- reports(0).disconnect if monitorOp.map.contains(sensorID)) yield
            monitorOp.map(sensorID)
        }
      }

      val result: Seq[String] = monitors.filter(m =>
        m.tags.contains(MonitorTag.SENSOR)
      ).filter(m => {
        if (county == "")
          true
        else
          m.county == Some(county)
      }).filter(m => {
        if (district == "")
          true
        else
          m.district == Some(district)
      }).filter(m => {
        if (sensorType == "")
          true
        else
          m.tags.contains(sensorType)
      }) map {
        _._id
      }

      Ok(Json.toJson(result))
    }
  }

  def epaStatus() = Security.Authenticated.async {
    implicit request =>
      import recordOp.monitorRecordWrite
      val f = recordOp.getLatestEpaStatus(TableType.mapCollection(TableType.min))
      for (recordList <- f) yield {
        recordList.foreach(r => {
          monitorOp.populateMonitorRecord(r, true)
        })
        Ok(Json.toJson(recordList))
      }
  }

  def sensorStatus(pm25Threshold: String, county: String, district: String, sensorType: String) =
    Security.Authenticated.async {
      implicit request =>
        import recordOp.monitorRecordWrite
        val start = DateTime.now
        val gpsUsageF = sysConfig.get(SysConfig.SensorGPS)
        val f = recordOp.getLatestSensorStatus(TableType.mapCollection(TableType.min))(pm25Threshold, county, district, sensorType)
        for {recordList <- f
             ret <- gpsUsageF
             } yield {
          val duration = new Duration(start, DateTime.now)
          recordList.foreach(r => {
            monitorOp.populateMonitorRecord(r, ret.asBoolean().getValue)
          })
          Ok(Json.toJson(recordList))
        }
    }

  def sensorConstant(county: String, district: String, sensorType: String) =
    Security.Authenticated.async {
      implicit request =>
        val today = DateTime.now().withMillisOfDay(0)
        val f = errorReportOp.get(today.toDate)
        for {report <- f
             } yield {
          val monitorIDs =
            if (report.isEmpty)
              Seq.empty[String]
            else {
              report(0).constant.map(monitorOp.map).filter(m => m.enabled.getOrElse(true))
                .filter(m => {
                  if (county == "")
                    true
                  else
                    m.county == Some(county)
                }).filter(m => {
                if (district == "")
                  true
                else
                  m.district == Some(district)
              }).filter(m => {
                if (sensorType == "")
                  true
                else
                  m.tags.contains(sensorType)
              }).map(_._id)
            }
          Ok(Json.toJson(monitorIDs))
        }
    }

  def getPowerUsageErrorSensor(county: String, district: String, sensorType: String) = Security.Authenticated.async {
    val today = DateTime.now().withMillisOfDay(0).toDate
    for (reports <- errorReportOp.get(today)) yield {
      val monitors: Seq[Monitor] = {
        if (reports.isEmpty)
          Seq.empty[Monitor]
        else {
          for (sensorID <- reports(0).powerError if monitorOp.map.contains(sensorID)) yield
            monitorOp.map(sensorID)
        }
      }

      val result: Seq[String] = monitors.filter(m =>
        m.tags.contains(MonitorTag.SENSOR)
      ).filter(m => {
        if (county == "")
          true
        else
          m.county == Some(county)
      }).filter(m => {
        if (district == "")
          true
        else
          m.district == Some(district)
      }).filter(m => {
        if (sensorType == "")
          true
        else
          m.tags.contains(sensorType)
      }) map {
        _._id
      }

      Ok(Json.toJson(result))
    }
  }

  def getNoErrorCodeSensors(county: String, district: String, sensorType: String) = Security.Authenticated.async {
    val today = DateTime.now().withMillisOfDay(0).toDate
    for (reports <- errorReportOp.get(today)) yield {
      val monitors: Seq[Monitor] = {
        if (reports.isEmpty)
          Seq.empty[Monitor]
        else {
          for (sensorID <- reports(0).noErrorCode if monitorOp.map.contains(sensorID)) yield
            monitorOp.map(sensorID)
        }
      }

      val result: Seq[String] = monitors.filter(m =>
        m.tags.contains(MonitorTag.SENSOR)
      ).filter(m => {
        if (county == "")
          true
        else
          m.county == Some(county)
      }).filter(m => {
        if (district == "")
          true
        else
          m.district == Some(district)
      }).filter(m => {
        if (sensorType == "")
          true
        else
          m.tags.contains(sensorType)
      }) map {
        _._id
      }

      Ok(Json.toJson(result))
    }
  }

  def lessThan95sensor(county: String, district: String, sensorType: String) =
    Security.Authenticated.async {
      implicit request =>
        implicit val writes = Json.writes[EffectiveRate]
        val today = DateTime.now().withMillisOfDay(0)
        val f = errorReportOp.get(today.toDate)
        for {report <- f
             } yield {
          val effectiveRates =
            if (report.isEmpty)
              Seq.empty[EffectiveRate]
            else {
              report(0).ineffective.filter(item=>{
                val m = monitorOp.map(item._id)
                m.enabled.getOrElse(true) &&
                  (county == ""||m.county == Some(county)) &&
                  (district == "" || m.district == Some(district)) &&
                  (sensorType == "" || m.tags.contains(sensorType))
              })
            }
          Ok(Json.toJson(effectiveRates))
        }
    }

  case class MonitorTypeStatus(desp: String, value: String, unit: String, instrument: String, status: String, classStr: Seq[String], order: Int)
}