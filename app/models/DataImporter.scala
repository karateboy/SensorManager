package models

import akka.actor._
import com.github.nscala_time.time.Imports.{DateTimeFormat, richInt}
import com.github.tototoshi.csv.CSVReader
import models.DataImporter.FileType
import models.ModelHelper.{errorHandler, getPeriods}
import org.apache.poi.ss.usermodel.{CellType, WorkbookFactory}
import org.joda.time.{DateTime, LocalDateTime, Period}
import play.api._

import java.io.File
import java.util.Locale
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}


object DataImporter {
  var n = 0
  private var actorRefMap = Map.empty[String, ActorRef]

  def start(monitorOp: MonitorOp, recordOp: RecordOp, monitorGroupOp: MonitorGroupOp, sensorOp: MqttSensorOp,
            dataFile: File, fileType: FileType, dataCollectManagerOp: DataCollectManagerOp)(implicit actorSystem: ActorSystem) = {
    val name = getName
    val actorRef = actorSystem.actorOf(DataImporter.props(monitorOp = monitorOp,
      recordOp = recordOp, monitorGroupOp = monitorGroupOp, sensorOp = sensorOp,
      dataFile = dataFile, fileType, dataCollectManagerOp = dataCollectManagerOp), name)
    actorRefMap = actorRefMap + (name -> actorRef)
    name
  }

  def getName: String = {
    n = n + 1
    s"dataImporter${n}"
  }

  def props(monitorOp: MonitorOp, recordOp: RecordOp, monitorGroupOp: MonitorGroupOp, sensorOp: MqttSensorOp,
            dataFile: File, fileType: FileType, dataCollectManagerOp: DataCollectManagerOp): Props =
    Props(new DataImporter(monitorOp, recordOp, monitorGroupOp, sensorOp, dataFile, fileType, dataCollectManagerOp))

  def finish(actorName: String): Unit = {
    actorRefMap = actorRefMap.filter(p => {
      p._1 != actorName
    })
  }

  def isFinished(actorName: String): Boolean = {
    !actorRefMap.contains(actorName)
  }

  sealed trait FileType

  final case object SensorData extends FileType

  final case object SensorRawData extends FileType

  final case object UpdateSensorData extends FileType

  final case object EpaData extends FileType

  final case object MonitorData extends FileType

  case object Import

  case object Complete
}

class DataImporter(monitorOp: MonitorOp, recordOp: RecordOp, monitorGroupOp: MonitorGroupOp, sensorOp: MqttSensorOp,
                   dataFile: File, fileType: FileType, dataCollectManagerOp: DataCollectManagerOp) extends Actor {

  import DataImporter._

  self ! Import

  def receive: Receive = {
    case Import =>
      Future {
        blocking {
          try {
            fileType match {
              case SensorData =>
                try {
                  if (importSensorData("UTF-8") == 0)
                    importSensorData("BIG5")
                } catch {
                  case ex: Throwable =>
                    Logger.error("failed to import sensor data", ex)
                }
              case SensorRawData =>
                try {
                  importSensorRawData("UTF-8")
                } catch {
                  case ex: Throwable =>
                    Logger.error("failed to import sensor raw data", ex)
                }
              case UpdateSensorData =>
                try {
                  importSensorRawData("UTF-8", updateOnly = true)
                } catch {
                  case ex: Throwable =>
                    Logger.error("failed to update sensor raw data", ex)
                }
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

  def importSensorData(encoding: String): Int = {
    Logger.info(s"Start import ${dataFile.getName}")
    val reader = CSVReader.open(dataFile, encoding)
    var count = 0
    val docOpts =
      for (record <- reader.allWithHeaders()) yield
        try {
          val deviceID = record("DEVICE_NAME")
          val time = try {
            LocalDateTime.parse(record("TIME"), DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")).toDate
          } catch {
            case _: IllegalArgumentException =>
              LocalDateTime.parse(record("TIME"), DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")).toDate
          }
          val value = record("VALUE(小時平均值)").toDouble
          count = count + 1
          Some(RecordList(time = time, monitor = deviceID,
            mtDataList = Seq(MtRecord(mtName = MonitorType.PM25, value, MonitorStatus.NormalStat))))
        } catch {
          case ex: Throwable =>
            None
        }
    val docs = docOpts.flatten

    reader.close()
    Logger.info(s"Total $count records")
    if (docs.nonEmpty) {
      dataFile.delete()
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
    docs.size
  }

  def importSensorRawData(encoding: String, updateOnly: Boolean = false): Int = {
    Logger.info(s"Start import sensor raw ${dataFile.getName}")
    val sensorMapF = sensorOp.getFullSensorMap
    val reader = CSVReader.open(dataFile, encoding)
    var count = 0
    val mtMap: Map[String, String] =
      Map[String, String](
        "pm2_5" -> MonitorType.PM25,
        "pm10" -> MonitorType.PM10,
        "humidity" -> MonitorType.HUMID,
        "o3" -> MonitorType.O3,
        "temperature" -> MonitorType.TEMP,
        "voc" -> MonitorType.VOC,
        "no2" -> MonitorType.NO2,
        "h2s" -> MonitorType.H2S,
        "nh3" -> MonitorType.NH3)

    val docOpts =
      for (record <- reader.allWithHeaders()) yield
        try {
          val deviceID = record("id").toDouble.formatted("%.0f")
          val time = try {
            DateTime.parse(record("time"), DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")).toDate
          } catch {
            case _: IllegalArgumentException =>
              DateTime.parse(record("time"), DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")).toDate
          }

          val mtRecords =
            for {mt <- mtMap.keys.toList
                 value <- record.get(mt)
                 } yield
              MtRecord(mtName = mtMap(mt), value.toDouble, MonitorStatus.NormalStat)

          count = count + 1

          Some(RecordList(time = time, monitor = deviceID,
            mtDataList = mtRecords))
        } catch {
          case _: Throwable =>
            None
        }
    val docs = docOpts.flatten
    reader.close()
    Logger.info(s"Total $count records")
    if (docs.nonEmpty) {
      dataFile.delete()
      val f = recordOp.upsertManyRecord(docs = docs)(recordOp.MinCollection)

      f onFailure errorHandler
      f onComplete {
        case Success(result) =>
          Logger.info(s"Import ${dataFile.getName} complete.")
          val start = new DateTime(docs.map(_._id.time).min)
          val end = new DateTime(docs.map(_._id.time).max).plusHours(1)
          val monitors = mutable.Set.empty[String]
          docs.foreach(recordList=>monitors.add(recordList.monitor))
          for {
            monitor <- monitors
            current <- getPeriods(start, end, new Period(1, 0,0,0))}
            dataCollectManagerOp.recalculateHourData(monitor, current, forward = false, alwaysValid = true)(mtMap.keys.toList)

          self ! Complete
        case Failure(exception) =>
          Logger.error("Failed to import data", exception)
          self ! Complete
      }
    }
    docs.size
  }


  def importEpaData(): Unit = {
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
      val row = sheet.getRow(rowN)
      if (row == null)
        finish = true
      else {
        try {
          val monitorID = row.getCell(0).getStringCellValue.trim
          val shortCodeOpt = for (v <- Option(monitorID.reverse.take(4))) yield v.reverse
          val codeOpt = for (v <- Option(row.getCell(2))) yield v.getStringCellValue
          val enabledOpt = try {
            for (v <- Option(row.getCell(3))) yield v.getNumericCellValue != 0
          } catch {
            case _: Throwable =>
              None
          }
          val sensorTypeOpt = for (v <- Option(row.getCell(4))) yield v.getStringCellValue
          val countyOpt = for (v <- Option(row.getCell(6))) yield v.getStringCellValue
          val districtOpt = for (v <- Option(row.getCell(7))) yield v.getStringCellValue
          val roadNameOpt = for (v <- Option(row.getCell(8))) yield v.getStringCellValue
          val locationDescOpt =
            for (cell <- Option(row.getCell(9))) yield
              if (cell.getCellType == CellType.STRING)
                cell.getStringCellValue
              else
                cell.getNumericCellValue.toString

          val authorityOpt = for (v <- Option(row.getCell(10))) yield v.getStringCellValue
          val epaCodeOpt =
            try {
              for (cell <- Option(row.getCell(11))) yield
                if (cell.getCellType == CellType.NUMERIC)
                  cell.getNumericCellValue.toLong.toString
                else
                  cell.getStringCellValue
            } catch {
              case _: Throwable =>
                None
            }

          val targetOpt = for (v <- Option(row.getCell(12))) yield v.getStringCellValue
          val targetDetailOpt = for (v <- Option(row.getCell(13))) yield v.getStringCellValue
          val heightOpt = for (v <- Option(row.getCell(14))) yield v.getNumericCellValue

          val lngOpt = try {
            for (v <- Option(row.getCell(16))) yield v.getNumericCellValue
          } catch {
            case _: Throwable =>
              None
          }

          val latOpt = try {
            for (v <- Option(row.getCell(17))) yield v.getNumericCellValue
          } catch {
            case _: Throwable =>
              None
          }

          def getDistance(): Seq[Double] = {
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
          monitor.shortCode = shortCodeOpt
          monitor.code = codeOpt
          monitor.tags = {
            for (code <- codeOpt) yield
              Seq(MonitorTag.SENSOR, code.reverse.take(2).reverse)
          }.getOrElse(Seq.empty[String])

          monitor.enabled = enabledOpt
          monitor.county = countyOpt
          monitor.district = for (district <- districtOpt) yield
            district.dropWhile(_ != '(').drop(1).takeWhile(_ != ')')
          monitor.location =
            for (lng <- lngOpt; lat <- latOpt) yield
              Seq(lng, lat)

          val detail = SensorDetail(
            sensorType = sensorTypeOpt.getOrElse(""),
            roadName = roadNameOpt.getOrElse(""),
            locationDesc = locationDescOpt.getOrElse(""),
            authority = authorityOpt.getOrElse(""),
            epaCode = epaCodeOpt.getOrElse(""),
            target = targetOpt.getOrElse(""),
            targetDetail = targetDetailOpt.getOrElse(""),
            height = heightOpt.getOrElse(0),
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

    Logger.info(s"monitorSeq #=${monitorSeq.size}")
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