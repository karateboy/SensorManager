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

@Singleton
class ExcelUtility @Inject()
(environment: play.api.Environment, monitorTypeOp: MonitorTypeOp) {
  val docRoot = environment.rootPath + "/report_template/"

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

  def getDecayReport(start: DateTime,
                     monitorGroupResultList: Seq[(MonitorGroup, mutable.Map[DateTime, mutable.Map[String, Double]], DateTime)],
                     sensorReportList: Seq[Seq[SensorMonthReport]]) = {
    val (reportFilePath, pkg, wb) = prepareTemplate("decayReport.xlsx")
    val evaluator = wb.getCreationHelper().createFormulaEvaluator()
    val format = wb.createDataFormat();

    val mgNames = for (result <- monitorGroupResultList) yield
      result._1._id

    def fillSheet(sheet: Sheet, sensorGroupResults: Seq[(MonitorGroup, mutable.Map[DateTime, mutable.Map[String, Double]], DateTime)]) = {
      // Fill time header
      val timeSeq = getPeriods(start, start + 1.month, 1.hour)
      for {(time, idx) <- timeSeq.zipWithIndex
           row = sheet.getRow(idx + 2)
           } {
        val timeCell = row.createCell(0)
        timeCell.setCellValue(time.toString(DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")))
      }

      var groupOffset = 0
      for ((sensorGroup, recordMap, start) <- sensorGroupResults) {
        for ((monitor, idx) <- sensorGroup.member.zipWithIndex) {
          val cellIdx = idx + 1 + groupOffset
          sheet.getRow(0).createCell(cellIdx).setCellValue(sensorGroup._id)
          sheet.getRow(1).createCell(cellIdx).setCellValue(monitor)

          for {(time, idx) <- timeSeq.zipWithIndex
               row = sheet.getRow(idx + 2)
               } {
            if (recordMap.contains(time)) {
              val timeMap: mutable.Map[String, Double] = recordMap(time)
              if (timeMap.contains(monitor)) {
                val cell = {
                  val c = row.getCell(cellIdx)
                  if (c != null)
                    c
                  else
                    row.createCell(cellIdx)
                }
                cell.setCellValue(timeMap(monitor))
              }
            }
          }
          for (r <- 746 to 751) {
            if (sheet.getRow(r) != null && sheet.getRow(r).getCell(cellIdx) != null)
              evaluator.evaluateFormulaCell(sheet.getRow(r).getCell(cellIdx))
          }
        }
        groupOffset = groupOffset + sensorGroup.member.length
      }
    } //end of sheet1

    def fillMonthlyReport = {
      val sheet = wb.getSheetAt(2)
      for ((sensorReport, monthIdx) <- sensorReportList.reverse.zipWithIndex) {
        val report200 = sensorReport(0)
        val report210 = sensorReport(1)
        val row = sheet.createRow(5 + monthIdx)
        def fillTab(report: SensorMonthReport, colOffset: Int) {
          val year = report.start.getYear -1911
          val month = report.start.getMonthOfYear
          def fillCell(v:Option[Double], cell:Cell){
            if(v.isDefined)
              cell.setCellValue(v.get)
            else
              cell.setCellValue("-")
          }
          row.createCell(0 + colOffset).setCellValue(s"${year}年${month}月")
          fillCell(report.max, row.createCell(1 + colOffset))
          fillCell(report.min, row.createCell(2 + colOffset))
          fillCell(report.median, row.createCell(3 + colOffset))
          fillCell(report.biasMax, row.createCell(4 + colOffset))
          fillCell(report.biasMin, row.createCell(5 + colOffset))
          fillCell(report.biasMedian, row.createCell(6 + colOffset))
          fillCell(report.rr, row.createCell(7 + colOffset))
        }

        fillTab(report200, 0)
        fillTab(report210, 9)
      }
    }

    fillSheet(wb.getSheetAt(0), monitorGroupResultList.take(3))
    fillSheet(wb.getSheetAt(1), monitorGroupResultList.take(1) ++ monitorGroupResultList.drop(3).take(2))
    fillMonthlyReport

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