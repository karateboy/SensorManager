package models
import akka.actor._
import com.github.nscala_time.time.Imports._
import play.api.Play.current
import play.api._
import play.api.libs.concurrent.Akka
import play.api.libs.json._
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext.Implicits.global

case class LatestRecordTime(time: Long)

object ForwardManager {
  implicit val latestRecordTimeRead = Json.reads[LatestRecordTime]


  //val enable = serverConfig.getBoolean("enable").getOrElse(false)

  case object ForwardHour
  case class ForwardHourRecord(start: DateTime, end: DateTime)
  case object ForwardMin
  case class ForwardMinRecord(start: DateTime, end: DateTime)
  case object ForwardCalibration
  case object ForwardAlarm
  case object ForwardInstrumentStatus
  case object UpdateInstrumentStatusType
  case object GetInstrumentCmd

  var managerOpt: Option[ActorRef] = None
  var count = 0

  def updateInstrumentStatusType = {
    managerOpt map { _ ! UpdateInstrumentStatusType }
  }

  def forwardHourData = {
    managerOpt map { _ ! ForwardHour }
  }

  def forwardHourRecord(start: DateTime, end: DateTime) = {
    managerOpt map { _ ! ForwardHourRecord(start, end) }
  }

  def forwardMinData = {
    managerOpt map { _ ! ForwardMin }
  }

  def forwardMinRecord(start: DateTime, end: DateTime) = {
    managerOpt map { _ ! ForwardMinRecord(start, end) }
  }

}

import javax.inject._
class ForwardManager @Inject()
(system: ActorSystem, dataCollectManagerOp: DataCollectManagerOp, ws: WSClient, configuration: Configuration)
() extends Actor {
  import ForwardManager._
  val serverConfig = configuration.getConfig("server").getOrElse(Configuration.empty)
  val server = serverConfig.getString("host").getOrElse("localhost")
  val monitor = serverConfig.getString("monitor").getOrElse("A01")
  Logger.info(s"create forwarder to $server/$monitor")

  val hourRecordForwarder = context.actorOf(Props(classOf[HourRecordForwarder], server, monitor),
    "hourForwarder")

  val minRecordForwarder = context.actorOf(Props(classOf[MinRecordForwarder], server, monitor),
    "minForwarder")

  val calibrationForwarder = context.actorOf(Props(classOf[CalibrationForwarder], server, monitor),
    "calibrationForwarder")

  val alarmForwarder = context.actorOf(Props(classOf[AlarmForwarder], server, monitor),
    "alarmForwarder")

  val instrumentStatusForwarder = context.actorOf(Props(classOf[InstrumentStatusForwarder], server, monitor),
    "instrumentStatusForwarder")

  val statusTypeForwarder = context.actorOf(Props(classOf[InstrumentStatusTypeForwarder], server, monitor),
    "statusTypeForwarder")

  {
    import scala.concurrent.duration._

    system.scheduler.scheduleOnce(Duration(30, SECONDS), statusTypeForwarder, UpdateInstrumentStatusType)
  }

  val timer = {
    import scala.concurrent.duration._
    system.scheduler.schedule(Duration(30, SECONDS), Duration(10, MINUTES), instrumentStatusForwarder, ForwardInstrumentStatus)
  }

  val timer2 = {
    import scala.concurrent.duration._
    system.scheduler.schedule(Duration(30, SECONDS), Duration(5, MINUTES), calibrationForwarder, ForwardCalibration)
  }

  val timer3 = {
    import scala.concurrent.duration._
    system.scheduler.schedule(Duration(30, SECONDS), Duration(3, MINUTES), alarmForwarder, ForwardAlarm)
  }

  val timer4 = {
    import scala.concurrent.duration._
    system.scheduler.scheduleOnce(Duration(3, SECONDS), self, GetInstrumentCmd)
  }

  def receive = handler
  def handler: Receive = {
    case ForwardHour =>
      hourRecordForwarder ! ForwardHour

    case fhr: ForwardHourRecord =>
      hourRecordForwarder ! fhr

    case ForwardMin =>
      minRecordForwarder ! ForwardMin

    case fmr: ForwardMinRecord =>
      minRecordForwarder ! fmr

    case ForwardCalibration =>
      Logger.info("Forward Calibration")
      calibrationForwarder ! ForwardCalibration

    case ForwardAlarm =>
      alarmForwarder ! ForwardAlarm

    case ForwardInstrumentStatus =>
      instrumentStatusForwarder ! ForwardInstrumentStatus

    case UpdateInstrumentStatusType =>
      statusTypeForwarder ! UpdateInstrumentStatusType

    case GetInstrumentCmd =>
      val url = s"http://$server/InstrumentCmd/$monitor"
      val f = ws.url(url).get().map {
        response =>

          val result = response.json.validate[Seq[InstrumentCommand]]
          result.fold(
            error => {
              Logger.error(JsError.toJson(error).toString())
            },
            cmdSeq => {
              if (!cmdSeq.isEmpty) {
                Logger.info("receive cmd from server=>")
                Logger.info(cmdSeq.toString())
                for (cmd <- cmdSeq) {
                  cmd.cmd match {
                    case InstrumentCommand.AutoCalibration.cmd =>
                      dataCollectManagerOp.autoCalibration(cmd.instId)

                    case InstrumentCommand.ManualZeroCalibration.cmd =>
                      dataCollectManagerOp.zeroCalibration(cmd.instId)

                    case InstrumentCommand.ManualSpanCalibration.cmd =>
                      dataCollectManagerOp.spanCalibration(cmd.instId)

                    case InstrumentCommand.BackToNormal.cmd =>
                      dataCollectManagerOp.setInstrumentState(cmd.instId, MonitorStatus.NormalStat)
                  }
                }
              }
            })
            
      }
      f onFailure {
        case ex: Throwable =>
          ModelHelper.logException(ex)
      }
      f onComplete { x =>
        {
          import scala.concurrent.duration._
          system.scheduler.scheduleOnce(Duration(10, SECONDS), self, GetInstrumentCmd)
        }
      }            
  }

  override def postStop(): Unit = {
    timer.cancel
    timer2.cancel
    timer3.cancel
    timer4.cancel
  }
}