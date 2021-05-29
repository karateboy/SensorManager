package models

import akka.actor._
import com.github.nscala_time.time.Imports.DateTimeFormat
import com.github.tototoshi.csv.CSVReader
import models.DataImporter.FileType
import models.ModelHelper.errorHandler
import org.joda.time.LocalDateTime
import play.api._

import java.io.File
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}


object DataImporter {
  var n = 0
  private var actorRefMap = Map.empty[String, ActorRef]

  def start(monitorOp: MonitorOp, recordOp: RecordOp, dataFile: File, fileType: FileType)(implicit actorSystem: ActorSystem) = {
    val name = getName
    val actorRef = actorSystem.actorOf(DataImporter.props(monitorOp = monitorOp, recordOp = recordOp, dataFile = dataFile, fileType), name)
    actorRefMap = actorRefMap + (name -> actorRef)
    name
  }

  def getName = {
    n = n + 1
    s"dataImporter${n}"
  }

  def props(monitorOp: MonitorOp, recordOp: RecordOp, dataFile: File, fileType: FileType) =
    Props(classOf[DataImporter], monitorOp, recordOp, dataFile, fileType)

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

  case object Import

  case object Complete
}

class DataImporter(monitorOp: MonitorOp, recordOp: RecordOp, dataFile: File, fileType: FileType) extends Actor {

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
          case _:java.lang.NumberFormatException =>
            RecordList(time = time, monitor = monitorOpt.get._1, mtDataList = Seq.empty[MtRecord])
        }
      }

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
}