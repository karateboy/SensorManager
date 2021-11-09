package models

import com.github.nscala_time.time.Imports._
import controllers.Highchart.HighchartData
import models.ModelHelper.getPeriods
import org.apache.poi.openxml4j.opc._
import org.apache.poi.ss.usermodel._
import org.apache.poi.xssf.usermodel._

import java.io._
import java.nio.file.{Files, _}
import javax.inject._
import scala.collection.mutable
import scala.language.postfixOps

@Singleton
class ExcelUtility @Inject()
(environment: play.api.Environment, monitorTypeOp: MonitorTypeOp, monitorOp: MonitorOp) {
  val docRoot: String = environment.rootPath + "/report_template/"

  implicit class PoweredSheet(sheet:Sheet){
    def getOrCreateRow(rowIndex: Int) = {
      if (sheet.getRow(rowIndex) != null)
        sheet.getRow(rowIndex)
      else
        sheet.createRow(rowIndex)
    }
  }

  implicit class PoweredRow(row:Row){
    def getOrCreateCell(cellIndex: Int) = {
      if(row.getCell(cellIndex) != null)
        row.getCell(cellIndex)
      else
        row.createCell(cellIndex)
    }
  }

  def createStyle(mt: String)(implicit wb: XSSFWorkbook) = {
    val prec = monitorTypeOp.map(mt).prec
    val format_str = "0." + "0" * prec
    val style = wb.createCellStyle();
    val format = wb.createDataFormat();
    // Create a new font and alter it.
    val font = wb.createFont();
    font.setFontHeightInPoints(10);
    font.setFontName("標楷體");

    style.setFont(font)
    style.setDataFormat(format.getFormat(format_str))
    style.setBorderBottom(BorderStyle.THIN);
    style.setBottomBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderLeft(BorderStyle.THIN);
    style.setLeftBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderRight(BorderStyle.THIN);
    style.setRightBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderTop(BorderStyle.THIN);
    style.setTopBorderColor(IndexedColors.BLACK.getIndex());
    style
  }

  def exportChartData(chart: HighchartData, monitorTypes: Array[String], showSec: Boolean): File = {
    val precArray = monitorTypes.map { mt => monitorTypeOp.map(mt).prec }
    exportChartData(chart, precArray, showSec)
  }

  def exportChartData(chart: HighchartData, precArray: Array[Int], showSec: Boolean) = {
    val (reportFilePath, pkg, wb) = prepareTemplate("chart_export.xlsx")
    val evaluator = wb.getCreationHelper().createFormulaEvaluator()
    val format = wb.createDataFormat();

    val sheet = wb.getSheetAt(0)
    val headerRow = sheet.createRow(0)
    headerRow.createCell(0).setCellValue("時間")

    var pos = 0
    for {
      col <- 1 to chart.series.length
      series = chart.series(col - 1)
    } {
      headerRow.createCell(pos + 1).setCellValue(series.name)
      pos += 1
    }

    val styles = precArray.map { prec =>
      val format_str = "0." + "0" * prec
      val style = wb.createCellStyle();
      style.setDataFormat(format.getFormat(format_str))
      style
    }

    // Categories data
    if (chart.xAxis.categories.isDefined) {
      val timeList = chart.xAxis.categories.get
      for (row <- timeList.zipWithIndex) {
        val rowNo = row._2 + 1
        val thisRow = sheet.createRow(rowNo)
        thisRow.createCell(0).setCellValue(row._1)

        for {
          col <- 1 to chart.series.length
          series = chart.series(col - 1)
        } {
          val cell = thisRow.createCell(col)
          cell.setCellStyle(styles(col - 1))

          val pair = series.data(rowNo - 1)
          if (pair.length == 2 && pair(1).isDefined) {
            cell.setCellValue(pair(1).get)
          }
          //val pOpt = series.data(rowNo-1)
          //if(pOpt.isDefined){
          //  cell.setCellValue(pOpt.get)
          //}

        }
      }
    } else {
      val rowMax = chart.series.map(s => s.data.length).max
      for (row <- 1 to rowMax) {
        val thisRow = sheet.createRow(row)
        val timeCell = thisRow.createCell(0)
        pos = 0
        for {
          col <- 1 to chart.series.length
          series = chart.series(col - 1)
        } {
          val cell = thisRow.createCell(pos + 1)
          pos += 1
          cell.setCellStyle(styles(col - 1))

          val pair = series.data(row - 1)
          if (col == 1) {
            val dt = new DateTime(pair(0).get.toLong)
            if (!showSec)
              timeCell.setCellValue(dt.toString("YYYY/MM/dd HH:mm"))
            else
              timeCell.setCellValue(dt.toString("YYYY/MM/dd HH:mm:ss"))
          }
          if (pair(1).isDefined) {
            cell.setCellValue(pair(1).get)
          }
        }
      }
    }

    finishExcel(reportFilePath, pkg, wb)
  }
  def getGroupAvg(sensorGroupResults: (MonitorGroup, mutable.Map[DateTime, mutable.Map[String, Double]], DateTime)) = {
    val (mg, timeRecordMap, dateTime) = sensorGroupResults
    val avgMap =
      timeRecordMap filter { p => p._2.size != 0 } map {
        pair =>
          pair._1 -> {
            val count = pair._2.size
            val avg = pair._2.values.toList.sum / count
            mutable.Map(mg._id -> avg)
          }
      }
    (MonitorGroup(mg._id, Seq(mg._id)), avgMap, dateTime)
  }

  def getOutstandingReport(monitorGroupListRecord: Seq[(MonitorGroup, mutable.Map[DateTime, mutable.Map[String, Double]], DateTime)])={
    val (reportFilePath, pkg, wb) = prepareTemplate("outstandingReport.xlsx")
    val evaluator = wb.getCreationHelper().createFormulaEvaluator()
    val format = wb.createDataFormat();

    def fillSheet(sheetIndex:Int, sensorGroupResults: Seq[(MonitorGroup, mutable.Map[DateTime, mutable.Map[String, Double]], DateTime)]): Unit = {
      val sheet = wb.getSheetAt(sheetIndex)
      val start = sensorGroupResults(0)._3

      // Fill time header
      val timeSeq = getPeriods(start, start + 1.month, 1.hour)
      for {(time, idx) <- timeSeq.zipWithIndex
           row = sheet.getOrCreateRow(idx + 2)
           } {
        val timeCell = row.getOrCreateCell(0)
        timeCell.setCellValue(time.toString(DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")))
      }
      // Fill average
      var groupOffset = 0
      for (((sensorGroup, recordMap, _), groupIdx) <- sensorGroupResults.zipWithIndex) {
        for ((monitor, idx) <- sensorGroup.member.zipWithIndex) {
          val cellIdx = idx + 1 + groupOffset
          if (groupIdx != 0)
            sheet.getOrCreateRow(0).createCell(cellIdx).setCellValue(monitor)

          val monitorName =
            if (monitorOp.map.contains(monitor) && monitorOp.map(monitor).shortCode.isDefined)
              monitorOp.map(monitor).shortCode.get
            else
              monitor

          sheet.getOrCreateRow(1).createCell(cellIdx).setCellValue(monitorName)

          for {(time, idx) <- timeSeq.zipWithIndex
               row = sheet.getOrCreateRow(idx + 2)
               } {
            if (recordMap.contains(time)) {
              val timeMap: mutable.Map[String, Double] = recordMap(time)
              if (timeMap.contains(monitor))
                row.getOrCreateCell(cellIdx).setCellValue(timeMap(monitor))
            }
          }
          /*
          for (r <- 746 to 750) {
            if (sheet.getRow(r) != null && sheet.getRow(r).getCell(cellIdx) != null)
              evaluator.evaluateFormulaCell(sheet.getRow(r).getCell(cellIdx))
          }*/
        }
        groupOffset = groupOffset + sensorGroup.member.length
      }
    }

    for((monitorGroupRecord, idx) <- monitorGroupListRecord.zipWithIndex){
      val average = getGroupAvg(monitorGroupRecord)
      fillSheet(1 + idx*2, Seq(average, monitorGroupRecord))
    }

    wb.setForceFormulaRecalculation(true)
    finishExcel(reportFilePath, pkg, wb)
  }

  def getDecayReport(county:String, thisMonthRecord: Seq[(MonitorGroup, mutable.Map[DateTime, mutable.Map[String, Double]], DateTime)],
                     historyRecordList: Seq[Seq[(MonitorGroup, mutable.Map[DateTime, mutable.Map[String, Double]], DateTime)]]) = {
    val (reportFilePath, pkg, wb) = county match{
      case "基隆市"=>
        prepareTemplate("decayReportKL.xlsx")
      case "屏東縣"=>
        prepareTemplate("decayReportPT.xlsx")
    }

    def fillSheet(sheet: Sheet,
                  sensorGroupResults: Seq[(MonitorGroup, mutable.Map[DateTime, mutable.Map[String, Double]], DateTime)],
                  period: Period, skipMonitorGroupRow: Boolean = false) = {
      val start = sensorGroupResults(0)._3

      // Fill time header
      val timeSeq = getPeriods(start, start + period, 1.hour)
      for {(time, idx) <- timeSeq.zipWithIndex
           row = sheet.createRow(idx + 2)
           } {
        val timeCell = row.createCell(0)
        timeCell.setCellValue(time.toString(DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")))
      }

      var groupOffset = 0
      for ((sensorGroup, recordMap, start) <- sensorGroupResults) {
        for ((monitor, idx) <- sensorGroup.member.zipWithIndex) {
          val cellIdx = idx + 1 + groupOffset
          if (!skipMonitorGroupRow)
            sheet.getOrCreateRow(0).createCell(cellIdx).setCellValue(sensorGroup._id)

          val monitorName =
            if (monitorOp.map.contains(monitor))
              monitorOp.map(monitor).desc
            else
              monitor

          sheet.getOrCreateRow(1).createCell(cellIdx).setCellValue(monitorName)

          for {(time, idx) <- timeSeq.zipWithIndex
               row = sheet.getOrCreateRow(idx + 2)
               } {
            if (recordMap.contains(time)) {
              val timeMap: mutable.Map[String, Double] = recordMap(time)
              if (timeMap.contains(monitor)) {
                val cell = {
                  val c = row.getOrCreateCell(cellIdx)
                  if (c != null)
                    c
                  else
                    row.createCell(cellIdx)
                }
                cell.setCellValue(timeMap(monitor))
              }
            }
          }
        }
        groupOffset = groupOffset + sensorGroup.member.length
      }
    } //end of sheet1


    def fillMonthlyReport(sheetIdx: Int, historyRecordList: Seq[Seq[SensorMonthReport]]) = {
      implicit val sheet = wb.getSheetAt(sheetIdx)

      for ((sensorReport, monthIdx) <- historyRecordList.zipWithIndex) {
        val report200 = sensorReport(0)
        val report210 = sensorReport(1)
        val row = sheet.getOrCreateRow(5 + monthIdx)

        def fillTab(report: SensorMonthReport, colOffset: Int) {
          val year = report.start.getYear - 1911
          val month = report.start.getMonthOfYear

          def fillCell(v: Option[Double], cell: Cell) {
            if (v.isDefined)
              cell.setCellValue(v.get)
            else
              cell.setCellValue("-")
          }

          row.getOrCreateCell(0 + colOffset).setCellValue(s"${year}年${month}月")
          fillCell(report.max, row.getOrCreateCell(1 + colOffset))
          fillCell(report.min, row.getOrCreateCell(2 + colOffset))
          fillCell(report.median, row.getOrCreateCell(3 + colOffset))
          fillCell(report.biasMax, row.getOrCreateCell(4 + colOffset))
          fillCell(report.biasMin, row.getOrCreateCell(5 + colOffset))
          fillCell(report.biasMedian, row.getOrCreateCell(6 + colOffset))
          fillCell(report.rr, row.getOrCreateCell(7 + colOffset))
        }

        fillTab(report200, 0)
        fillTab(report210, 9)
      }
    }

    def getMonitorGroupReport(last18month: Seq[Seq[(MonitorGroup, mutable.Map[DateTime, mutable.Map[String, Double]], DateTime)]]) = {
      for (recordTuple <- last18month) yield {
        for ((epaIdx, mgIdx) <- Seq((0, 1), (0, 2))) yield {
          val start = recordTuple(epaIdx)._3
          val timeSeq = getPeriods(start, start + 1.month, 1.hour)

          def records(idx: Int): Seq[Seq[Option[Double]]] = {
            val (mg, timeRecordMap, start) = recordTuple(idx)
            for {time <- timeSeq} yield {
              if (timeRecordMap.contains(time)) {
                val recordMap = timeRecordMap(time)
                mg.member map {
                  recordMap.get(_)
                }
              } else
                Seq.empty[Option[Double]]
            }
          }

          val biasRecord = {
            val (epaMG, epaTimeRecordMap, _) = recordTuple(epaIdx)
            val (mg, timeRecordMap, _) = recordTuple(mgIdx)
            val epaID = epaMG.member(0)
            for {time <- timeSeq} yield {
              if (timeRecordMap.contains(time) && epaTimeRecordMap.contains(time) &&
                epaTimeRecordMap(time).contains(epaID) &&
                epaTimeRecordMap(time)(epaID) != 0
              ) {
                val recordMap = timeRecordMap(time)
                val epaValue = epaTimeRecordMap(time)(epaID)
                mg.member map { sensor =>
                  for (v <- recordMap.get(sensor)) yield
                    (v - epaValue) / epaValue
                }
              } else
                Seq.empty[Option[Double]]
            }
          }

          val (min, max, median) = {
            val sensorRecords = records(mgIdx)
            val flattenRecords: Seq[Double] = sensorRecords flatMap { x => x flatMap (a => a) }
            val sorted = flattenRecords.sorted
            if (sorted.length != 0) {
              (Some(sorted.head), Some(sorted.reverse.head), Some(sorted(sorted.length / 2)))
            } else {
              (None, None, None)
            }
          }
          val (biasMin, biasMax, biasMedian) = {
            val flattenRecords = biasRecord flatMap { x => x flatMap (a => a) }
            val sorted = flattenRecords.sorted
            if (sorted.length != 0) {
              (Some(sorted.head), Some(sorted.reverse.head), Some(sorted(sorted.length / 2)))
            } else {
              (None, None, None)
            }
          }
          val rr: Option[Double] = {
            val rrRecord: Seq[Seq[Option[(Double, Double, Double, Double, Double)]]] = {
              val (_, epaTimeRecordMap, _) = recordTuple(epaIdx)
              val (mg, timeRecordMap, start) = recordTuple(mgIdx)
              for {time <- timeSeq} yield {
                if (timeRecordMap.contains(time) && epaTimeRecordMap.contains(time) && epaTimeRecordMap(time).size != 0) {
                  val recordMap = timeRecordMap(time)
                  val epaValue = epaTimeRecordMap(time).values.head
                  mg.member map { sensor =>
                    for (v <- recordMap.get(sensor)) yield
                      (v, epaValue, v * epaValue, v * v, epaValue * epaValue)
                  }
                } else
                  Seq(None)
              }
            }

            val flattenRecords: Seq[(Double, Double, Double, Double, Double)] = rrRecord flatMap { x => x flatMap (a => a) }
            val n = flattenRecords.length
            if (n == 0) {
              None
            } else {
              val sumX = flattenRecords map {
                _._1
              } sum
              val sumY = flattenRecords map {
                _._2
              } sum
              val sumXY = flattenRecords map {
                _._3
              } sum

              val sumXX = flattenRecords map {
                _._4
              } sum
              val sumYY = flattenRecords map {
                _._5
              } sum
              val r = (n * sumXY - sumX * sumY) /
                (Math.sqrt((n * sumXX -
                  sumX * sumX) * (n * sumYY -
                  sumY * sumY)))
              Some(r * r)
            }
          }

          SensorMonthReport(start, min, max, median, biasMin, biasMax, biasMedian, rr)
        }
      }
    }

    county match{
      case "基隆市"=>
        fillSheet(wb.getSheetAt(0), thisMonthRecord.take(3), 1.month)
        fillSheet(wb.getSheetAt(2), thisMonthRecord.take(1) ++ thisMonthRecord.drop(3).take(2), 1.month)

      case "屏東縣"=>
        // 屏東站, P0LO01, 潮州站, P0LO02, 恆春站, P0LO03, 屏東(枋寮), P0LO04, P0KM01, P0KM04
        fillSheet(wb.getSheetAt(0), thisMonthRecord.take(8), 1.month)
        fillSheet(wb.getSheetAt(2), Seq(thisMonthRecord(0), thisMonthRecord(8), thisMonthRecord(6), thisMonthRecord(9)) , 1.month)
    }

    val historyAvgRecords = for (monthRecord <- historyRecordList) yield
      for ((mgRecord, idx) <- monthRecord.zipWithIndex) yield
        if (idx == 0)
          mgRecord
        else
          getGroupAvg(mgRecord)

    fillSheet(wb.getSheetAt(3), historyAvgRecords(0), 1.month)
    val acendingHistoryRecords = historyAvgRecords.reverse
    val sensorMonthReportList = getMonitorGroupReport(acendingHistoryRecords)
    fillMonthlyReport(4, sensorMonthReportList)

    val last12Month = historyAvgRecords.take(12).reverse
    val last12MonthHistoryRecords = last12Month.foldLeft(last12Month(0))((a, b) => {
      a.zip(b) map {
        pair =>
          val a1 = pair._1
          val b1 = pair._2
          (a1._1, a1._2 ++ b1._2, a1._3)
      }
    })

    val saq200 = last12MonthHistoryRecords.take(2)
    val saq210 = last12MonthHistoryRecords.zipWithIndex.filter(p => {
      Seq(0, 2).contains(p._2)
    }) map {
      _._1
    }
    fillSheet(wb.getSheetAt(5), saq200, 12.month, true)
    fillSheet(wb.getSheetAt(6), saq210, 12.month, true)

    wb.setForceFormulaRecalculation(true)
    finishExcel(reportFilePath, pkg, wb)
  }

  private def prepareTemplate(templateFile: String) = {
    val templatePath = Paths.get(docRoot + templateFile)
    val reportFilePath = Files.createTempFile("temp", ".xlsx");

    Files.copy(templatePath, reportFilePath, StandardCopyOption.REPLACE_EXISTING)

    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(reportFilePath.toAbsolutePath().toString()))
    val wb = new XSSFWorkbook(pkg);

    (reportFilePath, pkg, wb)
  }

  def finishExcel(reportFilePath: Path, pkg: OPCPackage, wb: XSSFWorkbook) = {
    val out = new FileOutputStream(reportFilePath.toAbsolutePath().toString());
    wb.write(out);
    out.close();
    pkg.close();

    new File(reportFilePath.toAbsolutePath().toString())
  }
}