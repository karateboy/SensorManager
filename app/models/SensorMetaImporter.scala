package models

import akka.actor._
import org.apache.poi.ss.usermodel._
import play.api._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}


object SensorMetaImporter
{
  def listAllFiles(dir: String) = {
    new java.io.File(dir).listFiles.filter(_.getName.endsWith(".xlsx"))
  }

  trait Factory {
    def apply(): Actor
  }
  case class ImportSensorMeta(path: String)
  case object ImportComplete
}



class SensorMetaImporter @Inject()
(monitorOp: MonitorOp, environment: play.api.Environment, sysConfig: SysConfig)
  extends Actor {

  import SensorMetaImporter._
  val importPath: String = environment.rootPath + "/import/"
  for(imported <- sysConfig.getKLMetaImported){
    if(!imported){
      Logger.info("Start import sensor meta data")
      self ! ImportSensorMeta(importPath)
    }
  }

  def receive = {
    case ImportSensorMeta(path) =>
      val files = listAllFiles(path)
      for (f <- files) {
        Future {
          blocking{
            importMetaData(f)
            f.delete()
            self ! ImportComplete
          }
        }
      }
    case ImportComplete =>
      Logger.info("Sensor meta imported")
      sysConfig.setKLMetaImported(true)
      self ! PoisonPill
  }

  import java.io.{File, FileInputStream}

  def importMetaData(f: File)={
    Logger.info(s"Import ${f.getAbsolutePath}")
    val wb = WorkbookFactory.create(new FileInputStream(f));
    val sheet = wb.getSheetAt(0)

    var rowN = 1
    var finish = false
    var monitorSeq =  Seq.empty[Monitor]
    do {
      var row = sheet.getRow(rowN)
      if (row == null)
        finish = true
      else {
        try {
          val monitorID = row.getCell(0).getStringCellValue
          val shortCode = monitorID.reverse.take(4).reverse
          val code = row.getCell(2).getStringCellValue
          val county = row.getCell(5).getStringCellValue
          val district = row.getCell(6).getStringCellValue
          val monitor = monitorOp.map.getOrElse(monitorID, {
            val defaultMt = Seq(MonitorType.PM25, MonitorType.PM10)
            val defaultTags = Seq(MonitorTag.SENSOR)
            monitorOp.ensureMonitor(monitorID, monitorID, defaultMt, defaultTags)
          })
          monitor.shortCode = Some(shortCode)
          monitor.code = Some(code)
          val tagSet = Set(monitor.tags: _*) + code.reverse.take(2).reverse
          monitor.tags = tagSet.toSeq
          monitor.county = Some(county)
          monitor.district = Some(district)
          monitorSeq = monitorSeq :+ monitor
        }catch{
          case ex:Throwable=>
            Logger.error(s"failed to handle $rowN", ex)
        }
        rowN += 1
      }
    } while (!finish)

    val updateFuture = monitorOp.upsertMany(monitorSeq)
    updateFuture.onComplete({
      case Success(_)=>
        Logger.info("Sensor meta import complete.")
      case Failure(exception)=>
        Logger.error("failed to import meta", exception)
    })

    wb.close()
  }
}