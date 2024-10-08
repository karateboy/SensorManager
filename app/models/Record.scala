package models

import com.github.nscala_time.time.Imports._
import com.github.tototoshi.csv.CSVReader
import com.mongodb.bulk.BulkWriteResult
import models.ModelHelper._
import org.apache.commons.io.FileUtils
import org.mongodb.scala._
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.bson.{BsonArray, BsonDateTime, BsonInt32}
import org.mongodb.scala.result.DeleteResult
import play.api._
import play.api.libs.json.Json

import java.io.File
import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success}

case class RecordListID(time: Date, monitor: String)

object RecordListID {
  implicit val writes = Json.writes[RecordListID]
}

case class Record(time: DateTime, value: Double, status: String, monitor: String)

case class MonitorRecord(time: Date, mtDataList: Seq[MtRecord], _id: String, var location: Option[Seq[Double]],
                         count: Option[Int], pm25Max: Option[Double], pm25Min: Option[Double],
                         var shortCode: Option[String], var code: Option[String], var tags: Option[Seq[String]],
                         var locationDesc: Option[String])

case class MtRecord(mtName: String, value: Double, status: String)

case class CountByCounty(kl: Int, pt: Int, yl: Int, rest: Int)

case class GroupSummary(name: String, totalCount: CountByCounty,
                        count: CountByCounty,
                        lessThanExpected: CountByCounty,
                        constant: CountByCounty,
                        disconnected: CountByCounty,
                        powerError: CountByCounty)

case class DisconnectSummary(name: String, kl: Int, pt: Int, yl: Int, rest: Int)

case class SensorMonthReport(start: DateTime, min: Option[Double], max: Option[Double], median: Option[Double],
                             errorMin: Option[Double], errorMax: Option[Double], errorMedian: Option[Double], rr: Option[Double])

object RecordList {
  def apply(time: Date, monitor: String, mtDataList: Seq[MtRecord]): RecordList = {
    val location: Option[Seq[Double]] = {
      val latOpt = mtDataList.find(p => p.mtName == MonitorType.LAT)
      val lngOpt = mtDataList.find(p => p.mtName == MonitorType.LNG)
      for {lat <- latOpt
           lng <- lngOpt
           } yield
        Seq(lng.value, lat.value)
    }
    RecordList(time, mtDataList, monitor, RecordListID(time, monitor), location = location)
  }

  def apply(time: Date, monitor: String, location: Option[Seq[Double]], mtDataList: Seq[MtRecord]): RecordList = {
    RecordList(time, mtDataList, monitor, RecordListID(time, monitor), location = location)
  }
}

case class RecordList(time: Date, mtDataList: Seq[MtRecord], monitor: String, var _id: RecordListID,
                      location: Option[Seq[Double]]) {
  def getMtOrdered(mt: String) = {
    new Ordered[RecordList] {
      override def compare(that: RecordList): Int = {
        val a = mtMap(mt).value
        val b = that.mtMap(mt).value
        if (a < b)
          -1
        else if (a == b)
          0
        else
          1
      }
    }
  }

  def mtMap = {
    val pairs =
      mtDataList map { data => data.mtName -> data }
    pairs.toMap
  }
}

import javax.inject._

@Singleton
class RecordOp @Inject()(mongoDB: MongoDB,
                         monitorTypeOp: MonitorTypeOp,
                         monitorOp: MonitorOp,
                         powerErrorReportOp: ErrorReportOp,
                         mqttSensorOp: MqttSensorOp,
                         environment: Environment) {

  import org.mongodb.scala.model._
  import play.api.libs.json._

  implicit val ccWrite = Json.writes[CountByCounty]
  implicit val mtRecordWrites = Json.writes[MtRecord]
  implicit val recordListWrite = Json.writes[RecordList]
  implicit val summaryWrites = Json.writes[GroupSummary]
  implicit val monitorRecordWrite = Json.writes[MonitorRecord]


  val HourCollection = "hour_data"
  val MinCollection = "min_data"
  val SecCollection = "sec_data"


  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val codecRegistry = fromRegistries(fromProviders(classOf[RecordList], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)

  private def addMtDataStage(mt: String): Bson = {
    val filterDoc = Document("$filter" -> Document(
      "input" -> "$mtDataList",
      "as" -> "mtData",
      "cond" -> Document(
        "$eq" -> Seq("$$mtData.mtName", mt)
      )))
    val bsonArray = BsonArray(filterDoc.toBsonDocument, new BsonInt32(0))
    Aggregates.addFields(Field(s"${mt.toLowerCase}Data",
      Document("$arrayElemAt" -> bsonArray)))
  }

  private val addPm25DataStage: Bson = addMtDataStage("PM25")

  def init() {
    for (colNames <- mongoDB.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(HourCollection)) {
        val f = mongoDB.database.createCollection(HourCollection).toFuture()
        f.onFailure(errorHandler)
      }

      if (!colNames.contains(MinCollection)) {
        val f = mongoDB.database.createCollection(MinCollection).toFuture()
        f.onFailure(errorHandler)
      }

      if (!colNames.contains(SecCollection)) {
        val f = mongoDB.database.createCollection(SecCollection).toFuture()
        f.onFailure(errorHandler)
      }
    }
  }

  createDefaultIndex(Seq(HourCollection, MinCollection))

  def createDefaultIndex(colNames: Seq[String]) = {
    for (colName <- colNames) {
      val col = getCollection(colName)
      col.createIndex(Indexes.descending("monitor", "time"),
        IndexOptions().unique(true)).toFuture()
    }
  }

  init

  def upgrade() = {
    Logger.info("upgrade record!")
    import org.mongodb.scala.model._
    val col = mongoDB.database.getCollection(HourCollection)
    val recordF = col.find(Filters.exists("_id")).toFuture()
    for (records <- recordF) {
      var i = 1
      for (doc <- records) {
        val newID = Document("time" -> doc.get("_id").get.asDateTime(), "monitor" -> Monitor.SELF_ID)
        val newDoc = doc ++ Document("_id" -> newID,
          "time" -> doc.get("_id").get.asDateTime(),
          "monitor" -> Monitor.SELF_ID)
        val f = col.deleteOne(Filters.equal("_id", doc("_id"))).toFuture()
        val f2 = col.insertOne(newDoc).toFuture()

        waitReadyResult(f)
        waitReadyResult(f2)
        Logger.info(s"$i/${records.length}")
        i += 1
      }
    }
  }

  def upgrade2() = {
    Logger.info("upgrade record!")
    import org.mongodb.scala.model._
    val col = mongoDB.database.getCollection(HourCollection)
    val recordF = col.find(Filters.exists("_id")).toFuture()
    for (records <- recordF) {
      var i = 1
      for (doc <- records) {
        val _id = doc("_id").asDocument()
        val time = doc("time").asDateTime()
        val monitor = doc("monitor").asString().getValue
        val mtDataList =
          for {
            mt <- monitorTypeOp.allMtvList
            mtBFName = monitorTypeOp.BFName(mt)
            mtDocOpt = doc.get(mtBFName) if mtDocOpt.isDefined && mtDocOpt.get.isDocument()
            mtDoc = mtDocOpt.get.asDocument()
            v = mtDoc.get("v") if v.isDouble()
            s = mtDoc.get("s") if s.isString()
          } yield {
            Document("mtName" -> mt, "value" -> v.asDouble(), "status" -> s.asString())
          }

        val newDoc = Document("_id" -> _id,
          "time" -> time,
          "monitor" -> monitor, "mtDataList" -> mtDataList)
        val f = col.replaceOne(Filters.equal("_id", _id), newDoc).toFuture()
        waitReadyResult(f)

        Logger.info(s"$i/${records.length}")
        i += 1
      }
    }
  }

  def insertManyRecord(docs: Seq[RecordList])(colName: String) = {
    val col = getCollection(colName)
    val f = col.insertMany(docs).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def findAndUpdate(dt: DateTime, dataList: List[(String, (Double, String))])(colName: String) = {
    import org.mongodb.scala.bson._
    import org.mongodb.scala.model._

    val bdt: BsonDateTime = dt

    val updates =
      for {
        data <- dataList
        mt = data._1
        (v, s) = data._2
      } yield {
        Updates.set(monitorTypeOp.BFName(mt), Document("v" -> v, "s" -> s))
      }
    Updates.combine(updates: _*)

    val col = getCollection(colName)
    val f = col.findOneAndUpdate(Filters.equal("time", bdt), Updates.combine(updates: _*),
      FindOneAndUpdateOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def upsertRecord(doc: RecordList)(colName: String) = {
    import org.mongodb.scala.model.ReplaceOptions

    val col = getCollection(colName)

    val f = col.replaceOne(Filters.equal("_id", RecordListID(doc.time, doc.monitor)), doc, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def updateRecordStatus(dt: Long, mt: String, status: String, monitor: String = Monitor.SELF_ID)(colName: String) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._

    val col = getCollection(colName)

    val f = col.updateOne(
      and(equal("_id", RecordListID(new DateTime(dt), monitor)),
        equal("mtDataList.mtName", mt)), set("mtDataList.$.status", status)).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def getID(time: Long, monitor: String) = Document("time" -> new BsonDateTime(time), "monitor" -> monitor)

  def getRecordMap(colName: String)
                  (monitor: String, mtList: Seq[String], startTime: DateTime, endTime: DateTime) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    val f = col.find(and(equal("monitor", monitor), gte("time", startTime.toDate()), lt("time", endTime.toDate())))
      .sort(ascending("time")).allowDiskUse(true).toFuture()
    val docs = waitReadyResult(f)
    val pairs =
      for {
        mt <- mtList
      } yield {
        val list =
          for {
            doc <- docs
            time = doc.time
            mtMap = doc.mtMap if mtMap.contains(mt)
          } yield {
            Record(new DateTime(time.getTime), mtMap(mt).value, mtMap(mt).status, monitor)
          }

        mt -> list
      }
    Map(pairs: _*)
  }

  def getMonitorRecordListFuture(colName: String)
                                (startTime: DateTime, endTime: DateTime, monitor: String): Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    val f = col.find(and(equal("monitor", monitor), gte("time", startTime.toDate()), lt("time", endTime.toDate())))
      .sort(ascending("time")).allowDiskUse(true).toFuture()

    f onFailure (errorHandler)
    f
  }

  def getRecordListFuture(colName: String)
                         (startTime: DateTime, endTime: DateTime, monitors: Seq[String] = Seq(Monitor.SELF_ID)) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    val f = col.find(and(in("monitor", monitors: _*), gte("time", startTime.toDate), lt("time", endTime.toDate)))
      .sort(ascending("time")).allowDiskUse(true).toFuture()

    f onFailure (errorHandler)
    f
  }

  def getRecordMapFuture(colName: String)
                        (monitor: String, mtList: Seq[String], startTime: DateTime, endTime: DateTime): Future[Map[String, Seq[Record]]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    val f = col.find(and(equal("monitor", monitor), gte("time", startTime.toDate()), lt("time", endTime.toDate())))
      .sort(ascending("time")).allowDiskUse(true).toFuture()
    for (docs <- f) yield {
      val pairs =
        for {
          mt <- mtList
        } yield {
          val list =
            for {
              doc <- docs
              time = doc.time
              mtMap = doc.mtMap if mtMap.contains(mt)
            } yield {
              Record(new DateTime(time.getTime), mtMap(mt).value, mtMap(mt).status, monitor)
            }

          mt -> list
        }
      Map(pairs: _*)
    }
  }

  def getRecordWithLimitFuture(colName: String)(startTime: DateTime, endTime: DateTime, limit: Int,
                                                monitor: String = Monitor.SELF_ID) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    col.find(and(equal("monitor", monitor), gte("time", startTime.toDate()), lt("time", endTime.toDate())))
      .limit(limit).sort(ascending("time")).allowDiskUse(true).toFuture()

  }

  def getLatestRecordFuture(colName: String)(monitor: String) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    col.find(equal("monitor", monitor))
      .sort(descending("time")).limit(1).toFuture()
  }

  def getLatestSensorStatus(colName: String)
                           (pm25Threshold: String, county: String, district: String, sensorType: String) = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._
    val pm25Filter = try {
      val v = pm25Threshold.toInt
      if (v < 0) {
        Some(Aggregates.filter(Filters.elemMatch("mtDataList", Filters.and(Filters.equal("mtName", "PM25"),
          Filters.lt("value", Math.abs(v))))))
      } else {
        Some(Aggregates.filter(Filters.elemMatch("mtDataList", Filters.and(Filters.equal("mtName", "PM25"),
          Filters.gt("value", Math.abs(v))))))
      }
    } catch {
      case _: Throwable =>
        None
    }

    val targetMonitors = getTargetMonitor(county, district, sensorType)
    val monitorFilter = Aggregates.filter(Filters.in("monitor", targetMonitors: _*))
    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val timeFrameFilter = Aggregates.filter(Filters.and(Filters.gt("time", DateTime.now.minusMinutes(90).toDate)))

    val addPm25ValueStage = Aggregates.addFields(Field("pm25", "$pm25Data.value"))
    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"), Accumulators.first("location", "$location"),
      Accumulators.sum("count", 1),
      Accumulators.max("pm25Max", "$pm25"),
      Accumulators.min("pm25Min", "$pm25"))
    val projectStage = Aggregates.project(fields(
      Projections.include("time", "monitor", "id", "mtDataList", "location", "count", "pm25Max", "pm25Min")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    val pipeline =
      if (pm25Filter.isEmpty)
        Seq(sortFilter, timeFrameFilter, monitorFilter, addPm25DataStage, addPm25ValueStage, latestFilter, projectStage)
      else
        Seq(sortFilter, timeFrameFilter, monitorFilter, addPm25DataStage, addPm25ValueStage, latestFilter, pm25Filter.get, projectStage)

    col.aggregate(pipeline).allowDiskUse(true).toFuture()
  }

  def getSensorCount(colName: String)
                    (county: String, district: String, sensorType: String,
                     start: DateTime = DateTime.now()): Future[Seq[MonitorRecord]] = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val targetMonitors = getTargetMonitor(county, district, sensorType)
    val monitorFilter =
      Aggregates.filter(Filters.in("monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val begin = start.minusDays(1)
    val end = start
    val timeFrameFilter = Aggregates.filter(Filters.and(
      Filters.gte("time", begin.toDate),
      Filters.lt("time", end.toDate)))

    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"), Accumulators.first("location", "$location"),
      Accumulators.sum("count", 1))

    val projectStage = Aggregates.project(fields(
      Projections.include("time", "monitor", "id", "mtDataList", "location", "count")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, addPm25DataStage, latestFilter, projectStage))
      .allowDiskUse(true).toFuture()
  }

  def getLatestEpaStatus(colName: String) = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val epaMonitors = monitorOp.map.values.filter(m => {
      m.tags.contains(MonitorTag.EPA)
    }) map {
      _._id
    } toList

    val monitorFilter =
      Filters.in("monitor", epaMonitors: _*)

    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val timeFrameFilter = Aggregates.filter(Filters.and(Filters.gt("time", DateTime.now.minusHours(2).toDate)))
    val monitorStage = Aggregates.filter(monitorFilter)
    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"), Accumulators.first("location", "$location"))
    val removeIdStage = Aggregates.project(fields(Projections.include("time", "monitor", "id", "mtDataList", "location")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, timeFrameFilter, monitorStage, latestFilter, removeIdStage))
      .allowDiskUse(true).toFuture()
  }

  def getSensorDisconnected(colName: String)
                           (county: String, district: String, sensorType: String): Future[Set[String]] = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val timeFrameFilter =
      Aggregates.filter(Filters.gt("time", DateTime.now().minusMinutes(10).toDate))
    val targetMonitors = getTargetMonitor(county, district, sensorType)
    val monitorFilter =
      Aggregates.filter(Filters.in("monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"), Accumulators.first("location", "$location"))
    val removeIdStage = Aggregates.project(fields(Projections.include("time", "monitor", "id", "mtDataList", "location")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    val f = col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, latestFilter, removeIdStage))
      .allowDiskUse(true).toFuture()
    f.transform(ret => {
      val targetSet: Set[String] = targetMonitors.toSet
      val connected = ret.map(_._id)
      targetSet -- connected
    }, ex => ex)
  }

  def getTargetMonitor(county: String, district: String, sensorType: String): List[String] = {
    Logger.info(s"monitor map #=${monitorOp.map.size}")
    monitorOp.map.values.filter(m => m.tags.contains(MonitorTag.SENSOR))
      .filter(m => m.enabled.getOrElse(true))
      .filter(m => {
        if (county == "")
          true
        else
          m.county.contains(county)
      }).filter(m => {
      if (district == "")
        true
      else
        m.district.contains(district)
    }).filter(m => {
      if (sensorType == "")
        true
      else
        m.tags.contains(sensorType)
    }) map {
      _._id
    } toList
  }

  def getLastestSensorSummary(colName: String) = {
    val today = DateTime.now().withMillisOfDay(0)
    val groupList = List("SAQ200", "SAQ210")
    val f4 = powerErrorReportOp.get(today)
    for {
      errorReports <- f4
    } yield {

      val groupSummaryList =
        for (group <- groupList) yield {
          def getCountGroupByCounty(ids: Set[String]) = {
            var (kl, pt, yl, rest) = (0, 0, 0, 0)
            ids.foreach(id => {
              val m = monitorOp.map(id)
              for {enabled <- m.enabled if enabled
                   detail <- m.sensorDetail if detail.sensorType == group
                   county <- m.county
                   } {
                county match {
                  case "基隆市" =>
                    kl = kl + 1
                  case "屏東縣" =>
                    pt = pt + 1
                  case "宜蘭縣" =>
                    yl = yl + 1
                  case _ =>
                    rest = rest + 1
                }
              }
            })
            CountByCounty(kl = kl, pt = pt, yl = yl, rest = rest)
          }

          val groupMonitorCount =
            getCountGroupByCounty(monitorOp.map.keys.toSet)

          val lt90Count = {
            val lt90MonitorID: Seq[String] = {
              if (errorReports.isEmpty)
                Seq.empty[String]
              else {
                for (ineffect <- errorReports(0).ineffective if monitorOp.map.contains(ineffect._id)) yield
                  monitorOp.map(ineffect._id)._id
              }
            }
            getCountGroupByCounty(lt90MonitorID.toSet)
          }

          val disconnected = {
            val constantMonitorID: Seq[String] = {
              if (errorReports.isEmpty)
                Seq.empty[String]
              else {
                for (sensorID <- errorReports(0).disconnect if monitorOp.map.contains(sensorID)) yield
                  monitorOp.map(sensorID)._id
              }
            }
            getCountGroupByCounty(constantMonitorID.toSet)
          }

          val receivedCount = {
            val constantMonitorID: Seq[String] = {
              if (errorReports.isEmpty)
                Seq.empty[String]
              else {
                for (sensorID <- errorReports(0).disconnect if monitorOp.map.contains(sensorID)) yield
                  monitorOp.map(sensorID)._id
              }
            }
            getCountGroupByCounty(monitorOp.map.keys.toSet -- constantMonitorID.toSet)
          }

          val constant = {
            val constantMonitorID: Seq[String] = {
              if (errorReports.isEmpty)
                Seq.empty[String]
              else {
                for (sensorID <- errorReports(0).constant if monitorOp.map.contains(sensorID)) yield
                  monitorOp.map(sensorID)._id
              }
            }
            getCountGroupByCounty(constantMonitorID.toSet)
          }


          val powerError = {
            val powerErrorMonitorID: Seq[String] = {
              if (errorReports.isEmpty)
                Seq.empty[String]
              else {
                for (sensorID <- errorReports(0).powerError if monitorOp.map.contains(sensorID)) yield
                  monitorOp.map(sensorID)._id
              }
            }
            getCountGroupByCounty(powerErrorMonitorID.toSet)
          }

          GroupSummary(group, groupMonitorCount, receivedCount, lt90Count, constant, disconnected, powerError)
        }
      groupSummaryList
    }
  }

  def getLast30MinPm25ConstantSensor(colName: String, current:DateTime): Future[Seq[MonitorRecord]] = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val targetMonitors = monitorOp.map.values.filter(m =>
      m.tags.contains(MonitorTag.SENSOR)
    ) map {
      _._id
    } toList

    val monitorFilter =
      Aggregates.filter(Filters.in("monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val timeFrameFilter = Aggregates.filter(Filters.and(
      Filters.gt("time", current.minusMinutes(30).toDate), Filters.lt("time", current.toDate)))

    val addPm25ValueStage = Aggregates.addFields(Field("pm25", "$pm25Data.value"))
    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"), Accumulators.first("location", "$location"),
      Accumulators.sum("count", 1),
      Accumulators.max("pm25Max", "$pm25"),
      Accumulators.min("pm25Min", "$pm25"))
    val constantFilter = Aggregates.filter(Filters.and(Filters.gte("count", 8),
      Filters.expr(Document("$eq" -> Seq("$pm25Max", "$pm25Min")))
    ))
    val projectStage = Aggregates.project(fields(
      Projections.include("time", "monitor", "id", "mtDataList", "location", "count", "pm25Max", "pm25Min")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, addPm25DataStage,
      addPm25ValueStage, latestFilter, constantFilter, projectStage))
      .allowDiskUse(true).toFuture()
  }

  case class ConstantSensor(_id:String)
  def getLast30MinMtConstantSensor(colName: String, mt:String, current:DateTime): Future[Seq[ConstantSensor]] = {
    val targetMonitors = monitorOp.map.values.filter(m =>
      m.tags.contains(MonitorTag.SENSOR)
    ) map {
      _._id
    } toList

    val mtLower = mt.toLowerCase()

    val monitorFilter =
      Aggregates.filter(Filters.in("monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(Sorts.orderBy(Sorts.descending("time"), Sorts.descending("monitor")))
    val timeFrameFilter = Aggregates.filter(
      Filters.and(Filters.gt("time", current.minusMinutes(30).toDate),
      Filters.lt("time", current.toDate)))

    val addMtValueStage = Aggregates.addFields(Field(mtLower, "$" + s"${mtLower}Data.value"))
    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.sum(s"${mtLower}_count", 1),
      Accumulators.max(s"${mtLower}_max", s"$$$mtLower"),
      Accumulators.min(s"${mtLower}_min", s"$$$mtLower"))

    val constantFilter = Aggregates.filter(Filters.and(Filters.notEqual(s"${mtLower}_max", null),
      Filters.expr(Document("$eq" -> Seq(s"$$${mtLower}_max", s"$$${mtLower}_min")))
    ))

    val codecRegistry = fromRegistries(fromProviders(classOf[ConstantSensor]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[ConstantSensor](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, addMtDataStage(mt),
        addMtValueStage, latestFilter, constantFilter))
      .allowDiskUse(true).toFuture()
  }

  def delete45dayAgoRecord(colName: String): Future[DeleteResult] = {
    val date = DateTime.now().withMillisOfDay(0).minusDays(45).toDate
    val f = getCollection(colName).deleteMany(Filters.lt("time", date)).toFuture()
    f onFailure (errorHandler)
    f
  }

  // import CSV record if any file in importCSV
  def importCSV(dataCollectManagerOp: DataCollectManagerOp): Unit = {
    val mtMap = Map[String, String](
      "pm2_5" -> MonitorType.PM25,
      "pm10" -> MonitorType.PM10,
      "humidity" -> MonitorType.HUMID,
      "o3" -> MonitorType.O3,
      "temperature" -> MonitorType.TEMP,
      "voc" -> MonitorType.VOC,
      "no2" -> MonitorType.NO2,
      "h2s" -> MonitorType.H2S,
      "nh3" -> MonitorType.NH3)

    val sensorMap = waitReadyResult(mqttSensorOp.getFullSensorMap)
    import collection.JavaConverters._
    val files = FileUtils.listFiles(new File(environment.rootPath + "/importCSV"), Array("csv"), true)
    Logger.info(s"total record csv #=${files.size()}")

    for (file <- files.iterator().asScala) {
      val reader = CSVReader.open(file)
      val optDocs =
        for (record <- reader.allWithHeaders()) yield {
          val id = record("id")
          val time = DateTime.parse(record("time"), DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss"))
          val mtData =
            for (mtKey <- record.keys.filter(key => key != "id" && key != "time") if mtMap.contains(mtKey)) yield {
              val mt = mtMap(mtKey)
              try {
                Some(MtRecord(mt, record(mtKey).toDouble, MonitorStatus.NormalStat))
              } catch {
                case _: Throwable =>
                  None
              }
            }

          val mtDataList: Seq[MtRecord] = mtData.flatten.toSeq
          if (sensorMap.contains(id)) {
            val sensor = sensorMap(id)
            Some(RecordList(time.toDate, mtDataList,
              sensor.monitor,
              RecordListID(time.toDate, sensor.monitor),
              location = None))
          } else {
            Logger.warn(s"sensorMap don't contain $id")
            None
          }
        }
      reader.close()
      file.delete()
      val docs = optDocs.flatten
      Logger.info(s"Total ${docs.length} records to be upserted")
      if (docs.nonEmpty) {
        val f = upsertManyRecord(docs = docs)(MinCollection)
        f onFailure errorHandler
        f onComplete {
          case Success(_) =>
            val start = new DateTime(docs.map(_.time).min)
            val monitor = docs.map(_._id.monitor).head
            for (current <- getPeriods(start, start.plusDays(1), 1.hour))
              dataCollectManagerOp.recalculateHourData(monitor, current, false, true)(mtMap.keys.toList)
          case Failure(exception) =>
            Logger.error(s"failed to upsert ${file.getAbsolutePath} file", exception)
        }
        waitReadyResult(f)
        Logger.info(s"${file.getAbsolutePath} successfully upserted")
      }
    }
  }

  def moveRecord(originalID: String, newID: String): Unit = {
    Logger.info(s"moving $originalID records to $newID")

    def moveHelper(collectionName: String): Unit = {
      val theCollection = getCollection(collectionName)
      val minF = theCollection.find(Filters.equal("_id.monitor", originalID)).toFuture()
      for (docs <- minF if docs.nonEmpty) {
        val newDocs = docs.map(doc => RecordList(doc._id.time, newID, doc.mtDataList))
        val f = insertManyRecord(newDocs)(collectionName)
        f onComplete {
          case Success(_) =>
            Logger.info(s"Successfully move $originalID  ${docs.length} records to $newID")
            theCollection.deleteMany(Filters.equal("_id.monitor", originalID)).toFuture()
          case Failure(ex) =>
            Logger.error("failed to upsertNewRecords", ex)
        }
      }
    }

    moveHelper(MinCollection)
    moveHelper(HourCollection)
  }

  def upsertManyRecord(docs: Seq[RecordList])(colName: String): Future[BulkWriteResult] = {
    val col = getCollection(colName)
    val writeModels = docs map {
      doc =>
        ReplaceOneModel(Filters.equal("_id", RecordListID(doc.time, doc.monitor)),
          doc, ReplaceOptions().upsert(true))
    }
    val f = col.bulkWrite(writeModels, BulkWriteOptions().ordered(false)).toFuture()
    f onFailure errorHandler()
    f
  }

  def updateMtRecords(docs: Seq[RecordList])(colName: String): Future[BulkWriteResult] = {
    val col = getCollection(colName)
    val writeModels = docs flatMap {
      recordList => {
        recordList.mtDataList map {
          mtData => {
            val dt = new DateTime(recordList.time)
            val start = dt.toDate
            val end = dt.plusMinutes(1).toDate
            val filter = Filters.and(Filters.gte("time", start),
              Filters.lt("time", end),
              Filters.equal("mtDataList.mtName", mtData.mtName))
            val updates = Updates.set("mtDataList.$[].value", mtData.value)
            UpdateOneModel(filter, updates)
          }
        }
      }
    }
    val p = Promise[BulkWriteResult]
    val f = p.future
    col.bulkWrite(writeModels, BulkWriteOptions().ordered(false)).subscribe(
      (doOnNext: BulkWriteResult) =>
        Logger.info(s"${doOnNext.getModifiedCount} updated"),
      (ex: Throwable) => p.failure(ex),
      () => p.success(BulkWriteResult.unacknowledged()))
    f onFailure errorHandler()
    f
  }

  def removeOneMtDataRecord(docs: Seq[RecordList])(colName: String): Future[BulkWriteResult] = {
    val col = getCollection(colName)
    val writeModels = docs map {
      recordList => {
        val dt = new DateTime(recordList.time)
        val start = dt.toDate
        val end = dt.plusMinutes(1).toDate
        val filter = Filters.and(Filters.gte("time", start),
          Filters.equal("monitor", recordList.monitor),
          Filters.lt("time", end),
          Filters.size("mtDataList", 1)
        )
        DeleteOneModel(filter)
      }
    }
    val p = Promise[BulkWriteResult]
    col.bulkWrite(writeModels, BulkWriteOptions().ordered(false)).subscribe(
      (doOnNext: BulkWriteResult) =>
        Logger.info(s"${doOnNext.getModifiedCount} deleted"),
      (ex: Throwable) => p.failure(ex)
      ,
      () => p.success(BulkWriteResult.unacknowledged()))
    p.future
  }

  def getCollection(colName: String): MongoCollection[RecordList] =
    mongoDB.database.getCollection[RecordList](colName).withCodecRegistry(codecRegistry)
}