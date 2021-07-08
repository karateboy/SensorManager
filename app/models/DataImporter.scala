package models

import akka.actor._
import com.github.nscala_time.time.Imports.DateTimeFormat
import com.github.tototoshi.csv.CSVReader
import models.DataImporter.FileType
import models.ModelHelper.errorHandler
import org.apache.poi.ss.usermodel.{CellType, WorkbookFactory}
import org.joda.time.LocalDateTime
import play.api._

import java.io.File
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}


object DataImporter {
  var n = 0
  private var actorRefMap = Map.empty[String, ActorRef]

  def start(monitorOp: MonitorOp, recordOp: RecordOp, monitorGroupOp: MonitorGroupOp,
            dataFile: File, fileType: FileType)(implicit actorSystem: ActorSystem) = {
    val name = getName
    val actorRef = actorSystem.actorOf(DataImporter.props(monitorOp = monitorOp,
      recordOp = recordOp, monitorGroupOp = monitorGroupOp,
      dataFile = dataFile, fileType), name)
    actorRefMap = actorRefMap + (name -> actorRef)
    name
  }

  def getName = {
    n = n + 1
    s"dataImporter${n}"
  }

  def props(monitorOp: MonitorOp, recordOp: RecordOp, monitorGroupOp: MonitorGroupOp,
            dataFile: File, fileType: FileType) =
    Props(classOf[DataImporter], monitorOp, recordOp, monitorGroupOp, dataFile, fileType)

  def finish(actorName: String) = {
    actorRefMap = actorRefMap.filter(p => {
      p._1 != actorName
    })
  }

  def isFinished(actorName: String) = {
    !actorRefMap.contains(actorName)
  }

  sealed trait FileType

  final case object SensorData extends FileType

  final case object EpaData extends FileType

  final case object MonitorData extends FileType

  case object Import

  case object Complete
}

class DataImporter(monitorOp: MonitorOp, recordOp: RecordOp, monitorGroupOp: MonitorGroupOp,
                   dataFile: File, fileType: FileType) extends Actor {

  import DataImporter._

  self ! Import

  def receive = {
    case Import =>
      Future {
        blocking {
          try {
            fileType match {
              case SensorData =>
                importSensorData()
              case EpaData =>
                importEpaData()
              case MonitorData =>
                importSensorMonitorMeta(dataFile)
            }
          } catch {
            case ex: Exception =>
              Logger.error("failed to import", ex)
          }
        }
      }
    case Complete =>
      finish(context.self.path.name)
      self ! PoisonPill
  }

  def importSensorData() = {
    Logger.info(s"Start import ${dataFile.getName}")
    val reader = CSVReader.open(dataFile)
    var count = 0
    val docs =
      for (record <- reader.allWithHeaders()) yield {
        val deviceID = record("DEVICE_NAME")
        val time = try {
          LocalDateTime.parse(record("TIME"), DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")).toDate
        } catch {
          case _: IllegalArgumentException =>
            LocalDateTime.parse(record("TIME"), DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")).toDate
        }
        val value = record("VALUE(小時平均值)").toDouble
        count = count + 1
        RecordList(time = time, monitor = deviceID, mtDataList = Seq(MtRecord(mtName = MonitorType.PM25, value, MonitorStatus.NormalStat)))
      }
    reader.close()
    dataFile.delete()
    Logger.info(s"Total $count records")
    val f = recordOp.upsertManyRecord(docs = docs)(recordOp.HourCollection)
    f onFailure (errorHandler)
    f onComplete ({
      case Success(result) =>
        Logger.info(s"Import ${dataFile.getName} complete. ${result.getUpserts.size()} records upserted.")
        self ! Complete
      case Failure(exception) =>
        Logger.error("Failed to import data", exception)
        self ! Complete
    })
  }

  def importEpaData() = {
    Logger.info(s"Start import ${dataFile.getName}")
    val reader = CSVReader.open(dataFile, "Big5")
    val docs =
      for {record <- reader.allWithHeaders()
           monitorType = record("測項") if monitorType == "PM2.5"
           monitorName = record("測站")
           monitorOpt = monitorOp.map.find(p => {
             p._2.desc == monitorName
           }) if monitorOpt.isDefined
           } yield {
        val time = try {
          LocalDateTime.parse(record("時間"), DateTimeFormat.forPattern("YYYY-MM-dd HH:mm")).toDate
        } catch {
          case _: IllegalArgumentException =>
            LocalDateTime.parse(record("時間"), DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")).toDate
        }

        try {
          val value = record("資料").toDouble
          RecordList(time = time, monitor = monitorOpt.get._1, mtDataList = Seq(MtRecord(mtName = MonitorType.PM25, value, MonitorStatus.NormalStat)))
        } catch {
          case _: java.lang.NumberFormatException =>
            RecordList(time = time, monitor = monitorOpt.get._1, mtDataList = Seq.empty[MtRecord])
        }
      }
    reader.close()
    dataFile.delete()
    Logger.info(s"Total ${docs.length} records")
    val f = recordOp.upsertManyRecord(docs = docs)(recordOp.HourCollection)
    f onFailure (errorHandler)
    f onComplete ({
      case Success(result) =>
        Logger.info(s"Import ${dataFile.getName} complete. ${result.getUpserts.size()} records upserted.")
        self ! Complete
      case Failure(exception) =>
        Logger.error("Failed to import data", exception)
        self ! Complete
    })
  }

  import java.io.{File, FileInputStream}

  def importSensorMonitorMeta(f: File) = {
    Logger.info(s"Import ${f.getAbsolutePath}")
    val wb = WorkbookFactory.create(new FileInputStream(f));
    val sheet = wb.getSheetAt(0)

    var monitorGroups = List.empty[MonitorGroup]

    def importMonitorGroupName: Unit = {
      val nameRow = sheet.getRow(0)
      var col = 22
      var finished = false
      do {
        val cell = nameRow.getCell(col)
        if (cell == null)
          finished = true
        else {
          val groupName = nameRow.getCell(col).getStringCellValue
          if (groupName.nonEmpty) {
            val mg = MonitorGroup(_id = groupName, Seq.empty[String])
            monitorGroups = monitorGroups.:+(mg)
          }
        }
        col = col + 1
      } while (!finished)
    }

    importMonitorGroupName

    var rowN = 1
    var finish = false
    var monitorSeq = Seq.empty[Monitor]
    do {
      var row = sheet.getRow(rowN)
      if (row == null)
        finish = true
      else {
        try {
          val monitorID = row.getCell(0).getStringCellValue.trim
          val shortCode = monitorID.reverse.take(4).reverse
          val code = row.getCell(2).getStringCellValue
          val enabled = row.getCell(3).getNumericCellValue != 0
          val sensorType = row.getCell(4).getStringCellValue
          val county = row.getCell(6).getStringCellValue
          val district = row.getCell(7).getStringCellValue
          val roadName = row.getCell(8).getStringCellValue
          val locationDesc = {
            val cell = row.getCell(9)
            if (cell.getCellType == CellType.STRING)
              cell.getStringCellValue
            else
              cell.getNumericCellValue.toString
          }
          val authority = row.getCell(10).getStringCellValue
          val epaCode = {
            val cell = row.getCell(11)
            if (cell.getCellType == CellType.NUMERIC)
              cell.getNumericCellValue.toLong.toString
            else
              cell.getStringCellValue
          }
          val target = row.getCell(12).getStringCellValue
          val targetDetail = row.getCell(13).getStringCellValue
          val height = row.getCell(14).getNumericCellValue
          val year = row.getCell(15).getStringCellValue
          val lng = row.getCell(16).getNumericCellValue
          val lat = row.getCell(17).getNumericCellValue

          def getDistance() = {
            var ret = Seq.empty[Double]
            try {
              for (col <- 18 to 21) {
                val d = row.getCell(col).getNumericCellValue
                ret = ret :+ d
              }
              ret
            } catch {
              case _: Throwable =>
                ret
            }
          }

          val distance = getDistance()
          val monitor = monitorOp.map.getOrElse(monitorID, {
            val defaultMt = Seq(MonitorType.PM25, MonitorType.PM10)
            val defaultTags = Seq(MonitorTag.SENSOR)
            monitorOp.ensureMonitor(monitorID, monitorID, defaultMt, defaultTags)
          })
          monitor.shortCode = Some(shortCode)
          monitor.code = Some(code)
          monitor.tags = Seq(MonitorTag.SENSOR, code.reverse.take(2).reverse)
          monitor.enabled = Some(enabled)
          monitor.county = Some(county)
          monitor.district = Some(district.dropWhile(_ != '(').drop(1).takeWhile(_ != ')'))
          monitor.location = Some(Seq(lng, lat))
          monitor.location = Some(Seq(lng, lat))
          monitor.location = Some(Seq(lng, lat))
          val detail = SensorDetail(
            sensorType = sensorType,
            roadName = roadName,
            locationDesc = locationDesc,
            authority = authority,
            epaCode = epaCode,
            target = target,
            targetDetail = targetDetail,
            height = height,
            distance = distance
          )
          monitor.sensorDetail = Some(detail)
          monitorSeq = monitorSeq :+ monitor
          //Update monitorGroup
          for (idx <- Range(0, monitorGroups.length - 1)) {
            val col = idx + 22
            try {
              val cell = row.getCell(col).getNumericCellValue
              if (cell != 0) {
                val mg = monitorGroups(idx)
                mg.member = mg.member :+ (monitor._id)
                if (idx == 0)
                  Logger.info(s"${mg.toString}")
              }
            } catch {
              case _: Throwable =>
              // Simply ignore
            }
          }
        } catch {
          case ex: Throwable =>
            Logger.error(s"failed to handle $rowN", ex)
        }
        rowN += 1
      }
    } while (!finish)
    wb.close()
    dataFile.delete()

    if (!monitorGroups.isEmpty)
      monitorGroupOp.upsertMany(monitorGroups)

    val updateFuture = monitorOp.upsertMany(monitorSeq)
    updateFuture.onComplete({
      case Success(ret) =>
        Logger.info(s"Sensor meta import complete. modified=${ret.getModifiedCount}")
        self ! Complete
      case Failure(exception) =>
        Logger.error("failed to import meta", exception)
        self ! Complete
    })
  }
}