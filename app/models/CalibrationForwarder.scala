package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports._
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

class CalibrationForwarder @Inject()(ws:WSClient, calibrationOp: CalibrationOp)(server: String, monitor: String) extends Actor {
  import ForwardManager._
  def receive = handler(None)

  def checkLatest = {
    val url = s"http://$server/CalibrationRecordRange/$monitor"
    val f = ws.url(url).get().map {
      response =>
        val result = response.json.validate[LatestRecordTime]
        result.fold(
          error => {
            Logger.error(JsError.toJson(error).toString())
          },
          latest => {
            Logger.info(s"server latest calibration: ${new DateTime(latest.time).toString}")
            val serverLatest =
              if (latest.time == 0) {
                DateTime.now() - 1.day
              } else {
                new DateTime(latest.time)
              }

            context become handler(Some(serverLatest.getMillis))
            uploadCalibration(serverLatest.getMillis)
          })
    }
    f onFailure {
      case ex: Throwable =>        
        ModelHelper.logException(ex)
    }
  }

  def uploadCalibration(latestCalibration: Long) = {
    import calibrationOp.jsonWrites
    val recordFuture = calibrationOp.calibrationReportFuture(new DateTime(latestCalibration + 1))
    for (records <- recordFuture) {
      if (!records.isEmpty) {
        val recordJSON = records.map { _.toJSON }
        val url = s"http://$server/CalibrationRecord/$monitor"
        val f = ws.url(url).put(Json.toJson(recordJSON))
        f onSuccess {
          case response =>
            context become handler(Some(records.last.startTime.getMillis))
        }
        f onFailure {
          case ex: Throwable =>
            context become handler(None)
            ModelHelper.logException(ex)
        }
      }
    }
  }

  def handler(latestCalibrationOpt: Option[Long]): Receive = {
    case ForwardCalibration =>
      try {
        if (latestCalibrationOpt.isEmpty)
          checkLatest
        else
          uploadCalibration(latestCalibrationOpt.get)
      } catch {
        case ex: Throwable =>
          ModelHelper.logException(ex)
          context become handler(None)
      }

  }

}