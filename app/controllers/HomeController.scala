package controllers

import akka.actor.ActorSystem
import com.github.nscala_time.time.Imports._
import models.ModelHelper.errorHandler
import models._
import play.api._
import play.api.data.Forms._
import play.api.data._
import play.api.libs.json._
import play.api.mvc._

import java.nio.file.Files
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class HomeController @Inject()(environment: play.api.Environment,
                               userOp: UserOp, instrumentOp: InstrumentOp, dataCollectManagerOp: DataCollectManagerOp,
                               monitorTypeOp: MonitorTypeOp, query: Query, monitorOp: MonitorOp, groupOp: GroupOp,
                               instrumentTypeOp: InstrumentTypeOp, monitorStatusOp: MonitorStatusOp, actorSystem: ActorSystem,
                               recordOp: RecordOp, sysConfig: SysConfig, monitorGroupOp: MonitorGroupOp, sensorOp: MqttSensorOp,
                               errorReportOp: ErrorReportOp, emailTargetOp: EmailTargetOp) extends Controller {

  val epaReportPath: String = environment.rootPath + "/importEPA/"

  implicit val userParamRead: Reads[User] = Json.reads[User]

  import MonitorType.{mtRead, mtWrite}
  import groupOp.{read, write}


  def newUser = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val newUserParam = request.body.validate[User]

      newUserParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          userOp.newUser(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def getUserInfo() = Security.Authenticated {
    implicit request =>
      implicit val write = Json.writes[User]
      val userInfoOpt = Security.getUserinfo(request)
      val userInfo = userInfoOpt.get
      val userOpt = userOp.getUserByEmail(userInfo.id)
      if (userOpt.isEmpty)
        Unauthorized("not loginned")
      else
        Ok(Json.toJson(userOpt.get))
  }

  def deleteUser(email: String) = Security.Authenticated {
    implicit request =>
      val userInfoOpt = Security.getUserinfo(request)
      val userInfo = userInfoOpt.get

      userOp.deleteUser(email)
      Ok(Json.obj("ok" -> true))
  }

  def updateUser(id: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val userParam = request.body.validate[User]

      userParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          userOp.updateUser(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def getAllUsers = Security.Authenticated {
    val users = userOp.getAllUsers()
    implicit val userWrites = Json.writes[User]

    Ok(Json.toJson(users))
  }

  def newGroup = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val newUserParam = request.body.validate[Group]

      newUserParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          groupOp.newGroup(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def deleteGroup(id: String) = Security.Authenticated {
    implicit request =>
      val ret = groupOp.deleteGroup(id)
      Ok(Json.obj("ok" -> (ret.getDeletedCount != 0)))
  }

  def updateGroup(id: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val userParam = request.body.validate[Group]

      userParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          val ret = groupOp.updateGroup(param)
          Ok(Json.obj("ok" -> (ret.getMatchedCount != 0)))
        })
  }

  def getAllGroups = Security.Authenticated {
    val groups = groupOp.getAllGroups()

    Ok(Json.toJson(groups))
  }

  def saveMonitorTypeConfig() = Security.Authenticated {
    implicit request =>
      try {
        val mtForm = Form(
          mapping(
            "id" -> text,
            "data" -> text)(EditData.apply)(EditData.unapply))

        val mtData = mtForm.bindFromRequest.get
        val mtInfo = mtData.id.split(":")
        val mt = (mtInfo(0))

        monitorTypeOp.updateMonitorType(mt, mtInfo(1), mtData.data)

        Ok(mtData.data)
      } catch {
        case ex: Throwable =>
          Logger.error(ex.getMessage, ex)
          BadRequest(ex.toString)
      }
  }

  def getInstrumentTypes = Security.Authenticated {
    implicit val w1 = Json.writes[ProtocolInfo]
    implicit val write = Json.writes[InstrumentTypeInfo]
    val iTypes =
      for (instType <- instrumentTypeOp.map.keys) yield {
        val t = instrumentTypeOp.map(instType)
        InstrumentTypeInfo(t.id, t.desp,
          t.protocol.map { p => ProtocolInfo(p, Protocol.map(p)) })
      }
    val sorted = iTypes.toList.sortWith((a, b) => a.id < b.id)
    Ok(Json.toJson(sorted.toList))
  }

  def getInstrumentType(id: String) = Security.Authenticated {
    implicit val w1 = Json.writes[ProtocolInfo]
    implicit val write = Json.writes[InstrumentTypeInfo]
    val iTypes = {
      val t = instrumentTypeOp.map(id)
      InstrumentTypeInfo(t.id, t.desp,
        t.protocol.map { p => ProtocolInfo(p, Protocol.map(p)) })
    }
    Ok(Json.toJson(iTypes))
  }

  def newInstrument = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val r1 = Json.reads[InstrumentStatusType]
      implicit val reads = Json.reads[Instrument]
      val instrumentResult = request.body.validate[Instrument]

      instrumentResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        rawInstrument => {
          try {
            val instType = instrumentTypeOp.map(rawInstrument.instType)
            val instParam = instType.driver.verifyParam(rawInstrument.param)
            val newInstrument = rawInstrument.replaceParam(instParam)
            if (newInstrument._id.isEmpty())
              throw new Exception("儀器ID不可是空的!")

            instrumentOp.upsertInstrument(newInstrument)

            //Stop measuring if any
            dataCollectManagerOp.stopCollect(newInstrument._id)
            monitorTypeOp.stopMeasuring(newInstrument._id)

            val mtList = instType.driver.getMonitorTypes(instParam)
            for (mt <- mtList) {
              monitorTypeOp.addMeasuring(mt, newInstrument._id, instType.analog)
            }
            if (newInstrument.active)
              dataCollectManagerOp.startCollect(newInstrument)

            Ok(Json.obj("ok" -> true))
          } catch {
            case ex: Throwable =>
              ModelHelper.logException(ex)
              Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
          }
        })
  }

  implicit val w1 = Json.writes[InstrumentStatusType]
  implicit val w = Json.writes[Instrument]

  def getInstrumentInfoList = Security.Authenticated {
    implicit val write = Json.writes[InstrumentInfo]
    val ret = instrumentOp.getInstrumentList()

    val ret2 = ret.map { inst =>
      def getMonitorTypes: List[String] = {
        val instTypeCase = instrumentTypeOp.map(inst.instType)
        instTypeCase.driver.getMonitorTypes(inst.param)
      }

      def getStateStr = {
        if (inst.active) {
          monitorStatusOp.map(inst.state).desp
        } else
          "停用"
      }

      def getCalibrationTime = {
        val instTypeCase = instrumentTypeOp.map(inst.instType)
        instTypeCase.driver.getCalibrationTime(inst.param)
      }

      def getInfoClass = {
        val mtStr = getMonitorTypes.map {
          monitorTypeOp.map(_).desp
        }.mkString(",")
        val protocolParam =
          inst.protocol.protocol match {
            case Protocol.tcp =>
              inst.protocol.host.get
            case Protocol.serial =>
              s"COM${inst.protocol.comPort.get}"
          }
        val calibrationTime = getCalibrationTime.map { t => t.toString("HH:mm") }

        val state = getStateStr

        InstrumentInfo(inst._id, instrumentTypeOp.map(inst.instType).desp, state,
          Protocol.map(inst.protocol.protocol), protocolParam, mtStr, calibrationTime, inst)
      }

      getInfoClass
    }
    Ok(Json.toJson(ret2))
  }

  def getInstrumentList = Security.Authenticated {
    val ret = instrumentOp.getInstrumentList()

    Ok(Json.toJson(ret))
  }

  def getDoInstrumentList = Security.Authenticated {
    val ret = instrumentOp.getInstrumentList().filter(p => instrumentTypeOp.DoInstruments.contains(p.instType))

    Ok(Json.toJson(ret))
  }

  def getInstrument(id: String) = Security.Authenticated {
    val ret = instrumentOp.getInstrument(id)
    if (ret.isEmpty)
      BadRequest(s"No such instrument: $id")
    else {
      val inst = ret(0)
      Ok(Json.toJson(inst))
    }
  }

  def removeInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach {
        dataCollectManagerOp.stopCollect(_)
      }
      ids.foreach {
        monitorTypeOp.stopMeasuring
      }
      ids.map {
        instrumentOp.delete
      }
    } catch {
      case ex: Exception =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def deactivateInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach {
        dataCollectManagerOp.stopCollect(_)
      }
      ids.map {
        instrumentOp.deactivate
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def activateInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      val f = ids.map {
        instrumentOp.activate
      }
      ids.foreach {
        dataCollectManagerOp.startCollect(_)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def toggleMaintainInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        instrumentOp.getInstrument(id).map { inst =>
          val newState =
            if (inst.state == MonitorStatus.MaintainStat)
              MonitorStatus.NormalStat
            else
              MonitorStatus.MaintainStat

          dataCollectManagerOp.setInstrumentState(id, newState)
        }
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def calibrateInstrument(instruments: String, zeroCalibrationStr: String) = Security.Authenticated {
    val ids = instruments.split(",")
    val zeroCalibration = zeroCalibrationStr.toBoolean
    Logger.debug(s"zeroCalibration=$zeroCalibration")

    try {
      ids.foreach { id =>
        if (zeroCalibration)
          dataCollectManagerOp.zeroCalibration(id)
        else
          dataCollectManagerOp.spanCalibration(id)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def calibrateInstrumentFull(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach { id =>
        dataCollectManagerOp.autoCalibration(id)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def resetInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        dataCollectManagerOp.setInstrumentState(id, MonitorStatus.NormalStat)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def writeDO(instruments: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val read = Json.reads[WriteDO]
      val mResult = request.body.validate[WriteDO]
      mResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        writeDO => {
          val ids = instruments.split(",")
          try {
            ids.map { id =>
              dataCollectManagerOp.writeTargetDO(id, writeDO.bit, writeDO.on)
            }
            Ok(Json.obj("ok" -> true))
          } catch {
            case ex: Throwable =>
              Logger.error(ex.getMessage, ex)
              Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
          }
        })
  }

  def getExecuteSeq(instruments: String, seq: Int) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        dataCollectManagerOp.executeSeq(seq)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(ex.getMessage)
    }

    Ok(s"Execute $instruments $seq")
  }

  def executeSeq(instruments: String, seq: Int) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        dataCollectManagerOp.executeSeq(seq)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def monitorList = Security.Authenticated {
    implicit request =>
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      import Monitor.mWrite

      if (userInfo.isAdmin) {
        val mList2 = monitorOp.mvList map { m => monitorOp.map(m) }
        Ok(Json.toJson(mList2))
      } else {
        val mList2 = group.monitors map { m => monitorOp.map(m) }
        Ok(Json.toJson(mList2))
      }
  }

  def upsertMonitor(id: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      import Monitor.mRead
      val mResult = request.body.validate[Monitor]
      mResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        m => {
          monitorOp.upsert(m)
          Ok(Json.obj("ok" -> true))
        })
  }

  def monitorTypeList = Security.Authenticated {
    implicit request =>
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      val mtList = if (userInfo.isAdmin)
        monitorTypeOp.mtvList map monitorTypeOp.map
      else {
        group.monitorTypes map monitorTypeOp.map
        //val pm25 = monitorTypeOp.mtvList.filter(p => p == "PM25")
        //pm25 map monitorTypeOp.map
      }

      Ok(Json.toJson(mtList))
  }

  def activeMonitorTypeList = Security.Authenticated {
    implicit request =>
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      val mtList = if (userInfo.isAdmin)
        monitorTypeOp.realtimeMtvList map monitorTypeOp.map
      else {
        group.monitorTypes map monitorTypeOp.map
      }

      Ok(Json.toJson(mtList))
  }

  def upsertMonitorType(id: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      Logger.info(s"upsert Mt:${id}")
      val mtResult = request.body.validate[MonitorType]

      mtResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        mt => {
          Logger.info(mt.toString)
          monitorTypeOp.upsertMonitorType(mt)
          Ok(Json.obj("ok" -> true))
        })
  }

  def signalTypeList = Security.Authenticated {
    implicit request =>
      val mtList = monitorTypeOp.signalMtvList map monitorTypeOp.map

      Ok(Json.toJson(mtList))
  }

  def signalValues = Security.Authenticated {
    implicit request =>
      Ok(Json.toJson(monitorTypeOp.getSignalValueMap()))
  }

  def recalculateHour(monitorStr: String, startNum: Long, endNum: Long) = Security.Authenticated {
    val monitors = monitorStr.split(":")
    val start = new DateTime(startNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
    val end = new DateTime(endNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)

    for {
      monitor <- monitors
      mCase = monitorOp.map(monitor)
      hour <- query.getPeriods(start, end + 1.hour, 1.hour)} {
      dataCollectManagerOp.recalculateHourData(monitor, hour, false, true)(mCase.monitorTypes)
    }
    Ok(Json.obj("ok" -> true))
  }

  def uploadData(tabStr: String, startStr: String, endStr: String) = Security.Authenticated {
    val tab = TableType.withName(tabStr)
    val start = DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
    val end = DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))

    tab match {
      case TableType.min =>
        ForwardManager.forwardMinRecord(start, end)
      case TableType.hour =>
        ForwardManager.forwardHourRecord(start, end)
    }

    Ok(Json.obj("ok" -> true))
  }

  def testEvtOptHigh = Security.Authenticated {
    dataCollectManagerOp.evtOperationHighThreshold
    Ok("ok")
  }

  def testSpray = Security.Authenticated {
    Logger.info("testSpray")
    val ret: Seq[Instrument] = instrumentOp.getInstrumentList().filter(p => p.instType == instrumentTypeOp.ADAM6066)
    ret map {
      inst =>
        dataCollectManagerOp.toggleTargetDO(inst._id, 17, 10)
    }

    Ok("ok")
  }

  def importData(fileTypeStr: String) = Security.Authenticated(parse.multipartFormData) {
    implicit request =>
      val dataFileOpt = request.body.file("data")
      if (dataFileOpt.isEmpty) {
        Logger.info("data is empty..")
        Ok(Json.obj("ok" -> true))
      } else {
        val dataFile = dataFileOpt.get
        val (fileType, filePath) = fileTypeStr match {
          case "sensor" =>
            (DataImporter.SensorData, Files.createTempFile("sensor", ".csv"))
          case "sensorRaw" =>
            (DataImporter.SensorRawData, Files.createTempFile("sensorRaw", ".csv"))
          case "epa" =>
            (DataImporter.EpaData, Files.createTempFile("epa", ".csv"))
          case "monitor" =>
            (DataImporter.MonitorData, Files.createTempFile("monitor", ".xlsx"))
        }
        val file = dataFile.ref.moveTo(filePath.toFile, true)

        val actorName = DataImporter.start(monitorOp = monitorOp, recordOp = recordOp, monitorGroupOp = monitorGroupOp,
          sensorOp = sensorOp,
          dataFile = file, fileType = fileType)(actorSystem)
        Ok(Json.obj("actorName" -> actorName))
      }
  }

  def getUploadProgress(actorName: String) = Security.Authenticated {
    Ok(Json.obj("finished" -> DataImporter.isFinished(actorName)))
  }

  def getSystemConfig(key: String) = Security.Authenticated.async {
    key match {
      case SysConfig.SensorGPS =>
        for (ret <- sysConfig.get(SysConfig.SensorGPS))
          yield {
            Ok(Json.obj(SysConfig.valueKey -> ret.asBoolean().getValue))
          }
    }
  }

  def setSystemConfig(key: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      key match {
        case SysConfig.SensorGPS =>
          implicit val reads = Json.reads[BooleanValue]
          val ret = request.body.validate[BooleanValue]
          ret.fold(
            err => {
              Logger.error(JsError.toJson(err).toString())
              BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(err).toString()))
            },
            value => {
              sysConfig.set(SysConfig.SensorGPS, value.value)
              Ok(Json.obj("ok" -> true))
            }
          )
      }
  }

  def monitorGroupList = Security.Authenticated.async {
    implicit request =>
      implicit val writes = Json.writes[MonitorGroup]
      val f = monitorGroupOp.getList()
      for (ret <- f) yield
        Ok(Json.toJson(ret))
  }

  def upsertMonitorGroup(id: String) = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads = Json.reads[MonitorGroup]
      val ret = request.body.validate[MonitorGroup]
      ret.fold(
        err => {
          Logger.error(JsError.toJson(err).toString())
          Future {
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(err).toString()))
          }
        },
        mg => {
          if (mg._id != id) {
            Future {
              BadRequest(Json.obj("ok" -> false, "msg" -> s"monitorGroup id mismatch!"))
            }
          } else {
            for (ret <- monitorGroupOp.upsert(mg)) yield
              Ok(Json.obj("ok" -> (ret.getMatchedCount != 0)))
          }

        }
      )
  }

  def deleteMonitorGroup(id: String) = Security.Authenticated.async({
    val f = monitorGroupOp.delete(id)
    for (ret <- f) yield
      Ok(Json.obj("ok" -> (ret.getDeletedCount != 0)))
  })

  def getAlertEmailTargets = Security.Authenticated.async({
    import EmailTarget._
    val f = emailTargetOp.getList()
    f onFailure (errorHandler)
    for (ret <- f) yield
      Ok(Json.toJson((ret)))
  })

  def saveAlertEmailTargets() = Security.Authenticated(BodyParsers.parse.json)({
    implicit request =>
      import EmailTarget._
      val ret = request.body.validate[Seq[EmailTarget]]
      ret.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        emails => {
          emailTargetOp.upsertMany(emails)
          Ok(Json.obj("ok" -> true))
        })
  })

  def testAlertEmail(email: String) = Security.Authenticated {
    Logger.info(s"send test email to $email")
    errorReportOp.sendEmail(Seq(EmailTarget(email, Seq(""))))
    Ok("ok")
  }
  def testAllAlertEmail = Security.Authenticated {
    Logger.info(s"send test email to all")
    dataCollectManagerOp.sendErrorReport()
    Ok("ok")
  }

  def getConstantCheckTime() = Security.Authenticated.async {
    val f = sysConfig.getConstantCheckTime()
    for(v<-f) yield {
      Ok(v.toString)
    }
  }
  def saveConstantCheckTime() = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads = Json.reads[EditData]
      val ret = request.body.validate[EditData]

      ret.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          val localTime = LocalTime.parse(param.data)
          sysConfig.setConstantCheckTime(localTime)
          dataCollectManagerOp.udateCheckConstantTime(localTime)
          Ok(Json.obj("ok" -> true))
        })
  }

  case class EditData(id: String, data: String)

  case class BooleanValue(value: Boolean)
}
