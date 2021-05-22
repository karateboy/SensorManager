package models

import akka.actor._
import com.github.nscala_time.time.Imports.DateTimeFormat
import com.github.tototoshi.csv.CSVReader
import models.ModelHelper.errorHandler
import org.joda.time.LocalDateTime
import play.api._

import java.io.File
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}


object SensorDataImporter {
  var n = 0
  def getName = {
    n = n +1
    s"dataImporter${n}"
  }
  private var actorRefMap = Map.empty[String, ActorRef]

  def start(recordOp: RecordOp, dataFile: File)(implicit actorSystem: ActorSystem) = {
    val name = getName
    val actorRef = actorSystem.actorOf(SensorDataImporter.props(recordOp = recordOp, dataFile=dataFile), name)
    actorRefMap = actorRefMap + (name -> actorRef)
    name
  }

  def finish(actorName:String)={
    actorRefMap = actorRefMap.filter(p=>{ p._1 != actorName})
  }

  def isFinished(actorName:String) = {
    !actorRefMap.contains(actorName)
  }

  def props(recordOp: RecordOp, dataFile: File) = Props(classOf[SensorDataImporter], recordOp, dataFile)

  case object Import

  case object Complete
}

class SensorDataImporter (recordOp: RecordOp, dataFile: File) extends Actor {

  import SensorDataImporter._

  self ! Import

  def receive = {
    case Import =>
      Future {
        blocking {
          try{
            importData()
          }catch {
            case ex:Exception=>
              Logger.error("failed to import", ex)
          }

        }
      }
    case Complete =>
      finish(context.self.path.name)
      self ! PoisonPill

  }

  def importData() = {
    Logger.info(s"Start import ${dataFile.getName}")
    val reader = CSVReader.open(dataFile)
    var count = 0
    val docs =
      for (record <- reader.allWithHeaders()) yield {
        val deviceID = record("DEVICE_NAME")
        val time = try {
          LocalDateTime.parse(record("TIME"), DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")).toDate
        }catch {
          case _:IllegalArgumentException =>
            LocalDateTime.parse(record("TIME"), DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")).toDate
        }
        val value = record("VALUE(小時平均值)").toDouble
        count = count +1
        RecordList(time = time, monitor = deviceID, mtDataList = Seq(MtRecord(mtName = MonitorType.PM25, value, MonitorStatus.NormalStat)))
      }
    Logger.info(s"Total $count records")
    val f = recordOp.upsertManyRecord(docs = docs)(recordOp.HourCollection)
    f onFailure(errorHandler)
    f onComplete({
      case Success(result)=>
        Logger.info(s"Import ${dataFile.getName} complete. ${result.getUpserts.size()} records upserted.")
        self ! Complete
      case Failure(exception)=>
        Logger.error("Failed to import data", exception)
        self ! Complete
    })
  }
}