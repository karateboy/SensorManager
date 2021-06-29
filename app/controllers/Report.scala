package controllers

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models._
import play.api.libs.json.Json
import play.api.mvc._

import java.nio.file.Files
import javax.inject._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.postfixOps

object PeriodReport extends Enumeration {
  val DailyReport = Value("daily")
  val MonthlyReport = Value("monthly")
  val YearlyReport = Value("yearly")

  def map = Map(DailyReport -> "日報", MonthlyReport -> "月報",
    YearlyReport -> "年報")

}

case class StatRow(name: String, cellData: Seq[CellData])

case class HourEntry(time: Long, cells: CellData)

case class DailyReport(columnNames: Seq[String], hourRows: Seq[RowData], statRows: Seq[StatRow])

case class MonthlyHourReport(columnNames: Seq[String], rows: Seq[RowData], statRows: Seq[StatRow])

@Singleton
class Report @Inject()(monitorTypeOp: MonitorTypeOp, recordOp: RecordOp, query: Query, monitorGroupOp: MonitorGroupOp,
                       excelUtility: ExcelUtility) extends Controller {
  implicit val w3 = Json.writes[CellData]
  implicit val w2 = Json.writes[StatRow]
  implicit val w1 = Json.writes[RowData]
  implicit val w = Json.writes[DailyReport]

  def getMonitorReport(reportTypeStr: String, startNum: Long, outputTypeStr: String) = Security.Authenticated {
    implicit request =>
      val reportType = PeriodReport.withName(reportTypeStr)
      val outputType = OutputType.withName(outputTypeStr)

      if (outputType == OutputType.html || outputType == OutputType.pdf) {
        reportType match {
          case PeriodReport.DailyReport =>
            val startDate = new DateTime(startNum).withMillisOfDay(0)
            val mtList = monitorTypeOp.realtimeMtvList
            val periodMap = recordOp.getRecordMap(recordOp.HourCollection)("", mtList, startDate, startDate + 1.day)
            val mtTimeMap: Map[String, Map[DateTime, Record]] = periodMap.map { pair =>
              val k = pair._1
              val v = pair._2
              k -> Map(v.map { r => r.time -> r }: _*)
            }
            val statMap: Map[String, Map[DateTime, Stat]] = query.getPeriodStatReportMap(periodMap, 1.day)(startDate, startDate + 1.day)

            val avgRow = {
              val avgData =
                for (mt <- mtList) yield {
                  CellData(monitorTypeOp.format(mt, statMap(mt)(startDate).avg), Seq.empty[String])
                }
              StatRow("平均", avgData)
            }
            val maxRow = {
              val maxData =
                for (mt <- mtList) yield {
                  CellData(monitorTypeOp.format(mt, statMap(mt)(startDate).max), Seq.empty[String])
                }
              StatRow("最大", maxData)
            }
            val minRow = {
              val minData =
                for (mt <- mtList) yield {
                  CellData(monitorTypeOp.format(mt, statMap(mt)(startDate).min), Seq.empty[String])
                }
              StatRow("最小", minData)
            }
            val effectiveRow = {
              val effectiveData =
                for (mt <- mtList) yield {
                  CellData(monitorTypeOp.format(mt, statMap(mt)(startDate).effectPercent), Seq.empty[String])
                }
              StatRow("有效率(%)", effectiveData)
            }
            val statRows = Seq(avgRow, maxRow, minRow, effectiveRow)

            val hourRows =
              for (i <- 0 to 23) yield {
                val recordTime = startDate + i.hour
                val mtData =
                  for (mt <- mtList) yield {
                    CellData(monitorTypeOp.formatRecord(mt, mtTimeMap(mt).get(recordTime)),
                      monitorTypeOp.getCssClassStr(mt, mtTimeMap(mt).get(recordTime)))
                  }
                RowData(recordTime.getMillis, mtData)
              }
            val columnNames = mtList map {
              monitorTypeOp.map(_).desp
            }
            val dailyReport = DailyReport(columnNames, hourRows, statRows)

            Ok(Json.toJson(dailyReport))

          case PeriodReport.MonthlyReport =>
            val start = new DateTime(startNum).withMillisOfDay(0).withDayOfMonth(1)
            val mtList = monitorTypeOp.realtimeMtvList
            val periodMap = recordOp.getRecordMap(recordOp.HourCollection)("", monitorTypeOp.activeMtvList, start, start + 1.month)
            val statMap = query.getPeriodStatReportMap(periodMap, 1.day)(start, start + 1.month)
            val overallStatMap = getOverallStatMap(statMap)
            val avgRow = {
              val avgData =
                for (mt <- mtList) yield {
                  CellData(monitorTypeOp.format(mt, statMap(mt)(start).avg), Seq.empty[String])
                }
              StatRow("平均", avgData)
            }
            val maxRow = {
              val maxData =
                for (mt <- mtList) yield {
                  CellData(monitorTypeOp.format(mt, statMap(mt)(start).max), Seq.empty[String])
                }
              StatRow("最大", maxData)
            }
            val minRow = {
              val minData =
                for (mt <- mtList) yield {
                  CellData(monitorTypeOp.format(mt, statMap(mt)(start).min), Seq.empty[String])
                }
              StatRow("最小", minData)
            }
            val effectiveRow = {
              val effectiveData =
                for (mt <- mtList) yield {
                  CellData(monitorTypeOp.format(mt, statMap(mt)(start).effectPercent), Seq.empty[String])
                }
              StatRow("有效率(%)", effectiveData)
            }
            val statRows = Seq(avgRow, maxRow, minRow, effectiveRow)

            val dayRows =
              for (recordTime <- getPeriods(start, start + 1.month, 1.day)) yield {
                val mtData =
                  for (mt <- mtList) yield {
                    CellData(monitorTypeOp.format(mt, statMap(mt)(recordTime).avg),
                      Seq(statMap(mt)(recordTime).isEffective.toString))
                  }
                RowData(recordTime.getMillis, mtData)
              }
            val columnNames = mtList map {
              monitorTypeOp.map(_).desp
            }
            val dailyReport = DailyReport(columnNames, dayRows, statRows)
            Ok(Json.toJson(dailyReport))

          case PeriodReport.YearlyReport =>
            val start = new DateTime(startNum)
            val startDate = start.withMillisOfDay(0).withDayOfMonth(1).withMonthOfYear(1)
            val periodMap = recordOp.getRecordMap(recordOp.HourCollection)("", monitorTypeOp.activeMtvList, startDate, startDate + 1.year)
            val statMap = query.getPeriodStatReportMap(periodMap, 1.month)(start, start + 1.year)
            val overallStatMap = getOverallStatMap(statMap)
            Ok("")

          //case PeriodReport.MonthlyReport =>
          //val nDays = monthlyReport.typeArray(0).dataList.length
          //("月報", "")
        }
      } else {
        Ok("")
        //                val (title, excelFile) =
        //                  reportType match {
        //                    case PeriodReport.DailyReport =>
        //                      //val dailyReport = Record.getDailyReport(monitor, startTime)
        //                      //("日報" + startTime.toString("YYYYMMdd"), ExcelUtility.createDailyReport(monitor, startTime, dailyReport))
        //
        //        }
        //            case PeriodReport.MonthlyReport =>
        //              val adjustStartDate = DateTime.parse(startTime.toString("YYYY-MM-1"))
        //              val monthlyReport = getMonthlyReport(monitor, adjustStartDate)
        //              val nDay = monthlyReport.typeArray(0).dataList.length
        //              ("月報" + startTime.toString("YYYYMM"), ExcelUtility.createMonthlyReport(monitor, adjustStartDate, monthlyReport, nDay))
        //
        //          }
        //
        //                Ok.sendFile(excelFile, fileName = _ =>
        //                  play.utils.UriEncoding.encodePathSegment(title + ".xlsx", "UTF-8"),
        //                  onClose = () => { Files.deleteIfExists(excelFile.toPath()) })
      }
  }

  def getOverallStatMap(statMap: Map[String, Map[DateTime, Stat]]) = {
    val mTypes = statMap.keys.toList
    statMap.map { pair =>
      val mt = pair._1
      val dateMap = pair._2
      val values = dateMap.values.toList
      val total = values.size
      val count = values.count(_.avg.isDefined)
      val overCount = values.map {
        _.overCount
      }.sum
      val max = values.map {
        _.avg
      }.max
      val min = values.map {
        _.avg
      }.min
      val avg =
        if (mt != MonitorType.WIN_DIRECTION) {
          if (total == 0 || count == 0)
            None
          else {
            Some(values.filter {
              _.avg.isDefined
            }.map { s => s.avg.get * s.total }.sum / (values.map(_.total).sum))
          }
        } else {
          val winSpeedMap = statMap(MonitorType.WIN_SPEED)
          val dates = dateMap.keys.toList
          val windDir = dates.map {
            dateMap
          }
          val windSpeed = dates.map {
            winSpeedMap
          }

          def windAvg1(): Option[Double] = {
            val windRecord = windSpeed.zip(windDir).filter(w => w._1.avg.isDefined && w._2.avg.isDefined)
            if (windRecord.length == 0)
              None
            else {
              val wind_sin = windRecord.map {
                v => v._1.avg.get * Math.sin(Math.toRadians(v._2.avg.get))
              }.sum

              val wind_cos = windRecord.map(v => v._1.avg.get * Math.cos(Math.toRadians(v._2.avg.get))).sum
              Some(windAvg(wind_sin, wind_cos))
            }
          }

          windAvg1()
        }

      mt -> Stat(
        avg = avg,
        min = min,
        max = max,
        total = total,
        count = count,
        overCount = overCount)
    }
  }

  def monthlyHourReport(monitorTypeStr: String, startDate: Long, outputTypeStr: String) = Security.Authenticated {
    val mt = (monitorTypeStr)
    val start = new DateTime(startDate).withMillisOfDay(0).withDayOfMonth(1)
    val outputType = OutputType.withName(outputTypeStr)
    val title = "月份時報表"
    if (outputType == OutputType.html || outputType == OutputType.pdf) {
      val recordList = recordOp.getRecordMap(recordOp.HourCollection)("", List(mt), start, start + 1.month)(mt)
      val timePair = recordList.map { r => r.time -> r }
      val timeMap = Map(timePair: _*)

      def getHourPeriodStat(records: Seq[Record], hourList: List[DateTime]) = {
        if (records.length == 0)
          Stat(None, None, None, 0, 0, 0)
        else {
          val values = records.map { r => r.value }
          val min = values.min
          val max = values.max
          val sum = values.sum
          val count = records.length
          val total = new Duration(start, start + 1.month).getStandardDays.toInt
          val overCount = if (monitorTypeOp.map(mt).std_law.isDefined) {
            values.count {
              _ > monitorTypeOp.map(mt).std_law.get
            }
          } else
            0

          val avg = if (mt == MonitorType.WIN_DIRECTION) {
            val windDir = records
            val windSpeed = hourList.map(timeMap)
            windAvg(windSpeed, windDir)
          } else {
            sum / total
          }
          Stat(
            avg = Some(avg),
            min = Some(min),
            max = Some(max),
            total = total,
            count = count,
            overCount = overCount)
        }
      }

      val hourValues =
        for {
          h <- 0 to 23
          hourList = query.getPeriods(start + h.hour, start + 1.month, 1.day)
        } yield {
          h -> getHourPeriodStat(hourList.flatMap {
            timeMap.get
          }, hourList)
        }
      val hourStatMap = Map(hourValues: _*)
      val dayStatMap = query.getPeriodStatReportMap(Map(mt -> recordList), 1.day)(start, start + 1.month)
      val overallStat = query.getPeriodStatReportMap(Map(mt -> recordList), 1.day)(start, start + 1.month)(mt)(start)
      var columns = Seq.empty[String]
      for (i <- 0 to 23) {
        columns = columns.:+(s"$i:00")
      }
      columns = columns ++ Seq("平均", "最大", "最小", "有效筆數")

      val avgRow = {
        var avgData =
          for (h <- 0 to 23) yield {
            CellData(monitorTypeOp.format(mt, hourStatMap(h).avg), Seq.empty[String])
          }
        avgData = avgData.:+(CellData("", Seq.empty[String]))
        avgData = avgData.:+(CellData("", Seq.empty[String]))
        avgData = avgData.:+(CellData("", Seq.empty[String]))
        StatRow("平均", avgData)
      }
      val maxRow = {
        var maxData =
          for (h <- 0 to 23) yield {
            CellData(monitorTypeOp.format(mt, hourStatMap(h).max), Seq.empty[String])
          }
        maxData = maxData.:+(CellData("", Seq.empty[String]))
        maxData = maxData.:+(CellData(monitorTypeOp.format(mt, overallStat.max), Seq.empty[String]))
        maxData = maxData.:+(CellData("", Seq.empty[String]))
        StatRow("最大", maxData)
      }
      val minRow = {
        var minData =
          for (h <- 0 to 23) yield {
            CellData(monitorTypeOp.format(mt, hourStatMap(h).min), Seq.empty[String])
          }
        minData = minData.:+(CellData("", Seq.empty[String]))
        minData = minData.:+(CellData("", Seq.empty[String]))
        minData = minData.:+(CellData(monitorTypeOp.format(mt, overallStat.min), Seq.empty[String]))
        StatRow("最小", minData)
      }

      val statRows = Seq(avgRow, maxRow, minRow)

      var rows = Seq.empty[RowData]
      for (day <- getPeriods(start, start + 1.month, 1.day)) yield {
        val date = day.getMillis
        var cellData = Seq.empty[CellData]
        for (h <- 0 to 23) {
          cellData = cellData.:+(CellData(monitorTypeOp.formatRecord(mt, timeMap.get(day + h.hour)), Seq.empty[String]))
        }
        cellData = cellData.:+(CellData(monitorTypeOp.format(mt, dayStatMap(mt)(day).avg), Seq.empty[String]))
        cellData = cellData.:+(CellData(monitorTypeOp.format(mt, dayStatMap(mt)(day).max), Seq.empty[String]))
        cellData = cellData.:+(CellData(monitorTypeOp.format(mt, dayStatMap(mt)(day).min), Seq.empty[String]))
        cellData = cellData.:+(CellData(dayStatMap(mt)(day).count.toString, Seq.empty[String]))
        rows = rows.:+(RowData(date, cellData))
      }
      implicit val write = Json.writes[MonthlyHourReport]
      Ok(Json.toJson(MonthlyHourReport(columns, rows, statRows)))
    } else {
      Ok("")
    }
  }

  def decayReport(county: String, date: Long) = Security.Authenticated.async {
    val reportDate = new LocalDateTime(date).toDateTime.withMillisOfDay(0).withDayOfMonth(1)
    val mt = MonitorType.PM25

    val mgListFuture = Future.sequence(
      county match {
        case "基隆市" =>
          val epaGroupFuture = Future {
            MonitorGroup("基隆站", Seq("epa1"))
          }
          val sensorGroupFuture = Seq("K0LO01", "K1LO01", "K0KM01", "K1KM01") map monitorGroupOp.get
          sensorGroupFuture.+:(epaGroupFuture)
        case "屏東縣" =>
          val epaGroupFuture = Future {
            MonitorGroup("基隆站", Seq("epa1"))
          }
          val sensorGroupFuture = Seq("K0LO01", "K1LO01", "K0KM01", "K1KM01", "K0AL99", "K1AL99") map monitorGroupOp.get
          sensorGroupFuture.+:(epaGroupFuture)
      })

    val thisMonthReportFuture = mgListFuture map {
      mgList => getMonitorGroupListRecordMap(mt, mgList, reportDate)
    } flatMap (x => x)

    def getLast18MonthSensorReport = {
      val monitorGroupListFuture =
        Future.sequence {
          county match {
            case "基隆市" =>
              val epaGroupFuture = Future {
                MonitorGroup("基隆站", Seq("epa1"))
              }
              val sensorGroupFuture = Seq("K0AL99", "K1AL99") map monitorGroupOp.get
              sensorGroupFuture.+:(epaGroupFuture)
          }
        }

      val resultFF =
        for (mgList <- monitorGroupListFuture) yield {
          Future.sequence {
            for (i <- 0 to 17) yield
              getMonitorGroupListRecordMap(mt, mgList, reportDate - i.month)
          }
        }
      resultFF flatMap (x => x)
    }

    for {thisMonthReport <- thisMonthReportFuture
         historyReport <- getLast18MonthSensorReport
         } yield {
      val excelFile = excelUtility.getDecayReport(thisMonthReport, historyReport.toList)
      Ok.sendFile(excelFile, fileName = _ =>
        s"${county}${reportDate.toString(DateTimeFormat.forPattern("YYYYMM"))}衰減報告.xlsx",
        onClose = () => {
          Files.deleteIfExists(excelFile.toPath())
        })
    }
  }

  def getMonitorGroupRecordList(mt: String, monitorGroup: MonitorGroup, start: DateTime) = {
    val resultFuture = recordOp.getRecordListFuture(recordOp.HourCollection)(start, start + 1.month, monitorGroup.member)
    for (recordList <- resultFuture) yield {
      import scala.collection.mutable.Map
      val timeMtMonitorMap = Map.empty[DateTime, Map[String, Double]]
      recordList map {
        r =>
          val stripedTime = new DateTime(r.time).withSecondOfMinute(0).withMillisOfSecond(0)
          val monitorMap = timeMtMonitorMap.getOrElseUpdate(stripedTime, Map.empty[String, Double])
          if (r.mtMap.contains(mt)) {
            val mtRecord = r.mtMap(mt)
            monitorMap.update(r.monitor, mtRecord.value)
          }
      }
      (monitorGroup, timeMtMonitorMap, start)
    }
  }

  def outstandingReport(county: String, date: Long) = Security.Authenticated.async {
    val reportDate = new LocalDateTime(date).toDateTime.withMillisOfDay(0).withDayOfMonth(1)
    val mt = MonitorType.PM25

    val mgListFuture = Future.sequence(
      county match {
        case "基隆市" =>
          Seq("K9SE01", "K9SE02", "K9SE03", "K9SE04", "K9SE05", "K9SE06", "K9SE07", "K9SE08", "K9SE09", "K9SE10", "K9SE11", "K9SE12") map monitorGroupOp.get
      })

    val monitorGroupListReportFuture = mgListFuture map {
      mgList => getMonitorGroupListRecordMap(mt, mgList, reportDate)
    } flatMap (x => x)

    for (monitorGroupListReport <- monitorGroupListReportFuture) yield {
      val excelFile = excelUtility.getOutstandingReport(monitorGroupListReport)
      Ok.sendFile(excelFile, fileName = _ =>
        s"${county}${reportDate.toString(DateTimeFormat.forPattern("YYYYMM"))}離群分析.xlsx",
        onClose = () => {
          Files.deleteIfExists(excelFile.toPath())
        })
    }
  }

  def getMonitorGroupListRecordMap(mt: String, mgList: Seq[MonitorGroup], reportDate: DateTime) = {
    val listF =
      for (mg <- mgList) yield
        getMonitorGroupRecordMap(mt, mg, reportDate)
    Future.sequence(listF)
  }

  def getMonitorGroupRecordMap(mt: String, monitorGroup: MonitorGroup, start: DateTime) = {
    val resultFuture = recordOp.getRecordListFuture(recordOp.HourCollection)(start, start + 1.month, monitorGroup.member)
    for (recordList <- resultFuture) yield {
      import scala.collection.mutable.Map
      val timeMtMonitorMap = Map.empty[DateTime, Map[String, Double]]
      recordList map {
        r =>
          val stripedTime = new DateTime(r.time).withSecondOfMinute(0).withMillisOfSecond(0)
          val monitorMap = timeMtMonitorMap.getOrElseUpdate(stripedTime, Map.empty[String, Double])
          if (r.mtMap.contains(mt)) {
            val mtRecord = r.mtMap(mt)
            monitorMap.update(r.monitor, mtRecord.value)
          }
      }
      (monitorGroup, timeMtMonitorMap, start)
    }
  }

  def outstandingReportJson2(monitorGroupNames: String, date: Long) = Security.Authenticated.async {
    val reportDate = new LocalDateTime(date).toDateTime.withMillisOfDay(0).withDayOfMonth(1)
    val mt = MonitorType.PM25
    val monitorGroupNameList: Array[String] = monitorGroupNames.split(':');

    def getMonitorGroupReport(monitorGroupName:String)= {
      val monitorGroupReportFuture = monitorGroupOp.get(monitorGroupName).map(mg => getMonitorGroupRecordMap(mt, mg, reportDate)) flatMap (x => x)
      val timeSeq = getPeriods(reportDate, reportDate + 1.month, 1.hour)
      for ((mg, recordMap, _) <- monitorGroupReportFuture) yield {
        var sensorRecordMap: Map[String, Seq[Option[Double]]] = Map.empty[String, Seq[Option[Double]]]
        val groupAvgOpt: Seq[Option[Double]] = {
          for (time <- timeSeq) yield {
            for (m <- mg.member) {
              val memberSeq = sensorRecordMap.getOrElse(m, Seq.empty[Option[Double]])
              val v = recordMap.getOrElse(time, Map.empty[String, Double]).get(m)
              val updated: Seq[Option[Double]] = memberSeq :+ (v)
              sensorRecordMap = sensorRecordMap + (m -> updated)
            }

            val ret = mg.member.map(recordMap.getOrElse(time, Map.empty[String, Double]).get).flatten
            if (ret.isEmpty)
              None
            else
              Some(ret.sum / ret.size)
          }
        }
        var monitorRecordList: Seq[(String, Seq[Double])] = Seq.empty[(String, Seq[Double])]
        monitorRecordList = monitorRecordList.:+((monitorGroupName, groupAvgOpt.flatten.sorted))
        for (m <- mg.member) {
          monitorRecordList = monitorRecordList.:+((m, sensorRecordMap(m).flatten.sorted))
        }
        val result: Seq[Option[QuartileReport]] =
          for ((monitor, sorted) <- monitorRecordList) yield {
            val size = sorted.size
            if (size >= 4) {
              val q3 = sorted(size * 3 / 4)
              val q1 = sorted(size / 4)
              val iqr = (q3 - q1) / 2
              val outlier = Seq(sorted(0), sorted.last).filter(p => p < q1 - 1.5 * iqr || p > q3 + 1.5 * iqr)
              Some(QuartileReport(monitor, Quartile(q1 - 1.5 * iqr, sorted(size / 4), sorted(size / 2), sorted(size * 3 / 4), q3 + 1.5 * iqr), outlier))
            } else
              None
          }
        result.flatten
      }
    }
    val reportsFuture = Future.sequence(monitorGroupNameList.map(getMonitorGroupReport).toSeq)
    for(reports<-reportsFuture) yield{
      implicit val w1 = Json.writes[Quartile]
      implicit val write = Json.writes[QuartileReport]
      Ok(Json.toJson(reports.flatten))
    }
  }


  def getMonitorGroupRecordListFuture(monitorGroup: MonitorGroup, reportDate: DateTime) = {
    val listF =
      for (monitor <- monitorGroup.member) yield {
        recordOp.getMonitorRecordListFuture(recordOp.HourCollection)(reportDate, reportDate + 1.month, monitor)
      }
    Future.sequence(listF)
  }

}