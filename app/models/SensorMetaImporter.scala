package models

import akka.actor._
import org.apache.poi.ss.usermodel._
import play.api._

import java.io.File
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}


object SensorMetaImporter
{
  def listAllFiles(dir: String) = {
    val files = new java.io.File(dir).listFiles
    if(files == null)
      Array.empty[File]
    else{
      files.filter(_.getName.endsWith(".xlsx"))
    }
  }

  trait Factory {
    def apply(): Actor
  }
  case class ImportSensorMeta(path: String)
  case class ImportComplete(filename:String)
}



class SensorMetaImporter @Inject()
(monitorOp: MonitorOp, environment: play.api.Environment, sysConfig: SysConfig)
  extends Actor {

  import SensorMetaImporter._
  val importPath: String = environment.rootPath + "/import/"
  self ! ImportSensorMeta(importPath)

  var fileToBeImported = Seq.empty[String]
  var fileImported = Seq.empty[String]
  def receive = {
    case ImportSensorMeta(path) =>
      val files = listAllFiles(path)
      fileToBeImported = files map { _.getName }
      for (f <- files) {
        Future {
          blocking{
            for(filename <- sysConfig.getImportedSensorMetaFilename){
              if(!filename.contains(f.getName)){
                Logger.info(s"Start import ${f.getName}")
                importMetaData(f)
                self ! ImportComplete(f.getName)
              }
            }
          }
        }
      }
    case ImportComplete(filename) =>
      fileImported = fileToBeImported :+ filename
      val remain = fileToBeImported.toSet -- fileImported.toSet
      if(remain.isEmpty) {
        Logger.info(s"Import meta complete ${fileImported.toString()}")
        sysConfig.setImportedSensorMetaFilename(fileToBeImported)
        self ! PoisonPill
      }
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
          val enabled = row.getCell(3).getNumericCellValue != 0
          val sensorType = row.getCell(4).getStringCellValue
          val county = row.getCell(6).getStringCellValue
          val district = row.getCell(7).getStringCellValue
          val roadName = row.getCell(8).getStringCellValue
          val locationDesc = {
            val cell = row.getCell(9)
            if(cell.getCellType == Cell.CELL_TYPE_STRING)
              cell.getStringCellValue
            else
              cell.getNumericCellValue.toString
          }
          val authority = row.getCell(10).getStringCellValue
          val epaCode = row.getCell(11).getNumericCellValue.toString
          val target = row.getCell(12).getStringCellValue
          val targetDetail = row.getCell(13).getStringCellValue
          val height = row.getCell(14).getNumericCellValue
          val year = row.getCell(15).getStringCellValue
          val lng = row.getCell(16).getNumericCellValue
          val lat = row.getCell(17).getNumericCellValue
          def getDistance() = {
            var ret = Seq.empty[Double]
            var col = 18
            try{
              for(col <- 18 to 21){
                val d = row.getCell(col).getNumericCellValue
                ret = ret :+ d
              }
              ret
            }catch {
              case _:Throwable=>
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
            sensorType=sensorType,
            roadName=roadName,
            locationDesc=locationDesc,
            authority=authority,
            epaCode=epaCode,
            target = target,
            targetDetail = targetDetail,
            height = height,
            distance = distance
          )
          monitor.sensorDetail=Some(detail)
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