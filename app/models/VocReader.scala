package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import com.github.nscala_time.time.Imports._
import play.api.Play.current
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.ExecutionContext.Implicits.global
import javax.inject._
@Singleton
class VocReaderOp @Inject()(monitorTypeOp: MonitorTypeOp, recordOp: RecordOp, system: ActorSystem) {
  case object ReadFile
  case class ReparseDir(year: Int, month: Int)

  var managerOpt: Option[ActorRef] = None
  var count = 0
  def startup(dir: String) = {
    val props = Props(classOf[VocReader], dir)
    Logger.info(s"VOC dir=>$dir")

    managerOpt = Some(system.actorOf(props, name = s"vocReader$count"))
    count += 1
  }

  def reparse(year: Int, month: Int) = {
    for (manager <- managerOpt) {
      manager ! ReparseDir(year, month)
    }
  }
  import java.nio.file.{ Paths, Files, StandardOpenOption }
  import java.nio.charset.{ StandardCharsets }
  import scala.collection.JavaConverters._
  import scala.concurrent._
  import java.io.File

  val parsedFileName = "parsed.list"
  var parsedFileList =
    try {
      Files.readAllLines(Paths.get(parsedFileName), StandardCharsets.UTF_8).asScala.toSeq
    } catch {
      case ex: Throwable =>
        Logger.info("Cannot open parsed.lst")
        Seq.empty[String]
    }

  def appendToParsedFileList(filePath: String) = {
    parsedFileList = parsedFileList ++ Seq(filePath)

    try {
      Files.write(Paths.get(parsedFileName), (filePath + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    } catch {
      case ex: Throwable =>
        Logger.warn(ex.getMessage)
    }
  }

  def getFileDateTime(fileName: String, year: Int, month: Int) = {
    val dayHour = fileName.takeWhile { x => x != '.' }.dropWhile { x => !x.isDigit }
    if (dayHour.forall { x => x.isDigit }) {
      val day = dayHour.take(2).toInt
      val hour = dayHour.drop(2).toInt - 1
      val localDate = new LocalDate(year, month, day)
      val localTime = new LocalTime(hour, 0)
      Some(localDate.toDateTime(localTime))
    } else
      None
  }

  def parser(file: File, dateTime: DateTime): Future[Any] = {
    import scala.io.Source
    import com.github.tototoshi.csv._

    val reader = CSVReader.open(file)
    val recordList = reader.all().dropWhile { col => !col(0).startsWith("------") }.drop(1).takeWhile { col => !col(0).isEmpty() }
    val dataList =
      for (line <- recordList) yield {
        val mtName = line(2)
        val mtID = "_" + mtName.replace(",", "_").replace("-", "_")
        val mtCase = monitorTypeOp.rangeType(mtID, mtName, "ppb", 2)
        mtCase.measuringBy = Some(List.empty[String])
        if (!monitorTypeOp.exist(mtCase)) {
          monitorTypeOp.upsertMonitorType(mtCase)
          monitorTypeOp.refreshMtv
        }

        try {
          val v = line(5).toDouble
          Some(((mtID), (v, MonitorStatus.NormalStat)))
        } catch {
          case ex: Throwable =>
            None
        }
      }
    reader.close()
    recordOp.findAndUpdate(dateTime, dataList.flatMap(x => x))(recordOp.HourCollection)
  }

  def parseAllTx0(dir: String, year: Int, month: Int, ignoreParsed: Boolean = false) = {
    //val today = DateTime.now().toLocalDate
    val monthFolder = dir + File.separator + s"${year - 1911}${"%02d".format(month)}"

    def listTx0Files = {
      val BP1Files = Option(new java.io.File(monthFolder + File.separator + "BP1").listFiles())
        .getOrElse(Array.empty[File])
      val PlotFiles = Option(new java.io.File(monthFolder + File.separator + "Plot").listFiles())
        .getOrElse(Array.empty[File])
      val allFiles = BP1Files ++ PlotFiles
      allFiles.filter(p =>
        p != null && (ignoreParsed || !parsedFileList.contains(p.getAbsolutePath)))
    }

    val files = listTx0Files
    for (f <- files) {
      if (f.getName.toLowerCase().endsWith("tx0")) {
        try {
          Logger.info(s"parse ${f.getAbsolutePath}")
          for (dateTime <- getFileDateTime(f.getName, year, month)) {
            parser(f, dateTime)
            appendToParsedFileList(f.getAbsolutePath)
            ForwardManager.forwardHourRecord(dateTime, dateTime + 1.hour)
          }
        } catch {
          case ex: Throwable =>
            Logger.error("skip buggy file", ex)
        }
      }
    }
  }
}

class VocReader @Inject()(vocReaderOp: VocReaderOp, system: ActorSystem)(dir: String) extends Actor {
  import vocReaderOp._
  def resetTimer = {
    import scala.concurrent.duration._
    system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ReadFile)
  }

  var timer = resetTimer

  def receive = handler

  def handler: Receive = {
    case ReadFile =>
      val today = (DateTime.now() - 2.hour).toLocalDate
      parseAllTx0(dir, today.getYear, today.getMonthOfYear)
      timer = resetTimer

    case ReparseDir(year: Int, month: Int) =>
      parseAllTx0(dir, year, month, true)
  }

  override def postStop(): Unit = {
    timer.cancel()
  }
}