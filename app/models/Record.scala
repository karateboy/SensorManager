package models

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import org.mongodb.scala._
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.bson.{BsonArray, BsonDateTime, BsonInt32}
import play.api._
import play.api.libs.json.Json

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.postfixOps

case class RecordListID(time: Date, monitor: String)

object RecordListID {
  implicit val writes = Json.writes[RecordListID]
}

case class Record(time: DateTime, value: Double, status: String, monitor: String)

case class MonitorRecord(time: Date, mtDataList: Seq[MtRecord], _id: String, var location: Option[Seq[Double]],
                         count: Option[Int], pm25Max: Option[Double], pm25Min: Option[Double],
                         var shortCode: Option[String], var code: Option[String], var tags: Option[Seq[String]])

case class MtRecord(mtName: String, value: Double, status: String)

case class GroupSummary(name: String, count: Int, expected: Int, constant: Int)

case class DisconnectSummary(name: String, kl: Int, pt: Int, yl: Int, rest: Int)
case class SensorMonthReport(start:DateTime, min: Option[Double], max: Option[Double], median: Option[Double],
                             biasMin: Option[Double], biasMax: Option[Double], biasMedian: Option[Double], rr: Option[Double])

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

case class RecordList(time: Date, mtDataList: Seq[MtRecord], monitor: String, _id: RecordListID,
                      location: Option[Seq[Double]]) {
  def mtMap = {
    val pairs =
      mtDataList map { data => data.mtName -> data }
    pairs.toMap
  }
}

import javax.inject._

@Singleton
class RecordOp @Inject()(mongoDB: MongoDB, monitorTypeOp: MonitorTypeOp, monitorOp: MonitorOp, sensorOp: MqttSensorOp) {

  import org.mongodb.scala.model._
  import play.api.libs.json._

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

  def createDefaultIndex(colNames: Seq[String]) = {
    for (colName <- colNames) {
      val col = getCollection(colName)
      col.createIndex(Indexes.descending("time", "monitor"),
        new IndexOptions().unique(true)).toFuture()
      col.createIndex(Indexes.descending("monitor", "time"),
        new IndexOptions().unique(true)).toFuture()
      col.createIndex(Indexes.descending("time")).toFuture()
      col.createIndex(Indexes.geo2dsphere("location")).toFuture()
    }
  }

  createDefaultIndex(Seq(HourCollection, MinCollection))

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

  init

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

  def upsertManyRecord(docs: Seq[RecordList])(colName: String) = {
    val col = getCollection(colName)
    val writeModels = docs map {
      doc =>
        ReplaceOneModel(Filters.equal("_id", RecordListID(doc.time, doc.monitor)),
          doc, ReplaceOptions().upsert(true))
    }
    val f = col.bulkWrite(writeModels, BulkWriteOptions().ordered(false)).toFuture()
    f onFailure (errorHandler())
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

  def getCollection(colName: String) = mongoDB.database.getCollection[RecordList](colName).withCodecRegistry(codecRegistry)

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
      .sort(ascending("time")).toFuture()
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

  def getRecordListFuture(colName: String)
                         (startTime: DateTime, endTime: DateTime, monitors: Seq[String] = Seq(Monitor.SELF_ID)) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    val f = col.find(and(in("monitor", monitors: _*), gte("time", startTime.toDate()), lt("time", endTime.toDate())))
      .sort(ascending("time")).toFuture()

    f onFailure(errorHandler)
    f
  }

  def getRecordMapFuture(colName: String)
                  (monitor: String, mtList: Seq[String], startTime: DateTime, endTime: DateTime): Future[Map[String, Seq[Record]]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    val f = col.find(and(equal("monitor", monitor), gte("time", startTime.toDate()), lt("time", endTime.toDate())))
      .sort(ascending("time")).toFuture()
    for(docs <- f) yield {
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
      .limit(limit).sort(ascending("time")).toFuture()

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

    val targetMonitors = monitorOp.map.values.filter(m =>
      m.tags.contains(MonitorTag.SENSOR)
    ).filter(m => {
      if (county == "")
        true
      else
        m.county == Some(county)
    }).filter(m =>{
      if(district == "")
        true
      else
        m.district == Some(district)
    }).filter(m => {
      if (sensorType == "")
        true
      else
        m.tags.contains(sensorType)
    }) map {
      _._id
    } toList

    val monitorFilter = Aggregates.filter(Filters.in("monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val timeFrameFilter = Aggregates.filter(Filters.and(Filters.gt("time", DateTime.now.minusMinutes(10).toDate)))

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
      if(pm25Filter.isEmpty)
        Seq(sortFilter, timeFrameFilter, monitorFilter, addPm25DataStage, addPm25ValueStage, latestFilter, projectStage)
      else
        Seq(sortFilter, timeFrameFilter, monitorFilter, addPm25DataStage, addPm25ValueStage, latestFilter, pm25Filter.get, projectStage)

    col.aggregate(pipeline).toFuture()
  }

  def getLatestConstantSensor(colName: String)
                             (county: String, district: String, sensorType: String) = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val targetMonitors = monitorOp.map.values.filter(m =>
      m.tags.contains(MonitorTag.SENSOR)
    ).filter(m => {
      if (county == "")
        true
      else
        m.county == Some(county)
    }).filter(m =>{
      if(district == "")
        true
      else
        m.district == Some(district)
    }).filter(m => {
      if (sensorType == "")
        true
      else
        m.tags.contains(sensorType)
    }) map {
      _._id
    } toList

    val monitorFilter =
      Aggregates.filter(Filters.in("monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val timeFrameFilter = Aggregates.filter(Filters.and(Filters.gt("time", DateTime.now.minusMinutes(10).toDate)))


    val addPm25ValueStage = Aggregates.addFields(Field("pm25", "$pm25Data.value"))
    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"), Accumulators.first("location", "$location"),
      Accumulators.sum("count", 1),
      Accumulators.max("pm25Max", "$pm25"),
      Accumulators.min("pm25Min", "$pm25"))
    val constantFilter = Aggregates.filter(Filters.and(Filters.gte("count", 3),
      Filters.expr(Document("$eq" -> Seq("$pm25Max", "$pm25Min")))
    ))
    val projectStage = Aggregates.project(fields(
      Projections.include("time", "monitor", "id", "mtDataList", "location", "count", "pm25Max", "pm25Min")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, addPm25DataStage, addPm25ValueStage, latestFilter, constantFilter, projectStage)).toFuture()
  }

  def getLessThan95Sensor(colName: String)
                             (county: String, district: String, sensorType: String) = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val targetMonitors = monitorOp.map.values.filter(m =>
      m.tags.contains(MonitorTag.SENSOR)
    ).filter(m => {
      if (county == "")
        true
      else
        m.county == Some(county)
    }).filter(m =>{
      if(district == "")
        true
      else
        m.district == Some(district)
    }).filter(m => {
      if (sensorType == "")
        true
      else
        m.tags.contains(sensorType)
    }) map {
      _._id
    } toList

    val monitorFilter =
      Aggregates.filter(Filters.in("monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val timeFrameFilter = Aggregates.filter(Filters.and(Filters.gt("time", DateTime.now.minusDays(1).toDate)))

    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"), Accumulators.first("location", "$location"),
      Accumulators.sum("count", 1))

    val lessThan95Filter = Aggregates.filter(Filters.lt("count", 24*60*95/100))
    val projectStage = Aggregates.project(fields(
      Projections.include("time", "monitor", "id", "mtDataList", "location", "count")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, addPm25DataStage, latestFilter, lessThan95Filter, projectStage)).toFuture()
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
    col.aggregate(Seq(sortFilter, timeFrameFilter, monitorStage, latestFilter, removeIdStage)).toFuture()
  }

  def getSensorDisconnected(colName: String)
                           (county: String, district: String, sensorType: String): Future[Set[String]] = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val timeFrameFilter = {
      DateTime.now().minusMinutes(10).toDate
      Aggregates.filter(Filters.gt("time", DateTime.now().minusMinutes(10).toDate))
    }

    val targetMonitors = monitorOp.map.values.filter(m => m.tags.contains(MonitorTag.SENSOR))
      .filter(m => m.enabled.getOrElse(true))
      .filter(m => {
        if (county == "")
          true
        else
          m.county == Some(county)
      }).filter(m =>{
      if(district == "")
        true
      else
        m.district == Some(district)
    }).filter(m => {
      if (sensorType == "")
        true
      else
        m.tags.contains(sensorType)
    }) map {
      _._id
    } toList

    val monitorFilter =
      Aggregates.filter(Filters.in("monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"), Accumulators.first("location", "$location"))
    val removeIdStage = Aggregates.project(fields(Projections.include("time", "monitor", "id", "mtDataList", "location")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    val f = col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, latestFilter, removeIdStage)).toFuture()
    f.transform(ret => {
      val targetSet: Set[String] = targetMonitors.toSet
      val connected = ret.map(_._id)
      targetSet -- connected
    }, ex => ex)
  }

  def getLastestSensorSummary(colName: String) = {

    val now = DateTime.now
    val todayFilter = Aggregates.filter(Filters.and(Filters.gte("time", now.minusDays(1).toDate),
      Filters.lt("time", now.toDate)))

    val groupByMonitorCount = Aggregates.group(id = "$monitor", Accumulators.sum("count", 1))
    val col = mongoDB.database.getCollection(colName)
    val f1 = col.aggregate(Seq(todayFilter, groupByMonitorCount)).toFuture()
    val f3 = getLast10MinConstantSensor(colName)
    for {docList <- f1
         constantMonitors <- f3
         } yield {
      var todaySensorRecordCount = Map.empty[String, Seq[Int]]
      docList foreach { doc =>
        val count = doc("count").asInt32().getValue
        val monitorId = doc("_id").asString().getValue
        if (monitorOp.map.contains(monitorId)) {
          for {detail <- monitorOp.map(monitorId).sensorDetail
               group = detail.sensorType
               } {
            val monitorCount = todaySensorRecordCount.getOrElse(group, List.empty[Int])
            todaySensorRecordCount = todaySensorRecordCount + (group -> monitorCount.:+(count))
          }
        }
      }
      var groupConstantCount = Map.empty[String, Int]
      constantMonitors foreach ({
        monitorRecord =>
          if (monitorOp.map.contains(monitorRecord._id)) {
            for {
              detail <- monitorOp.map(monitorRecord._id).sensorDetail
              group = detail.sensorType
            } {
              val constant = groupConstantCount.getOrElse(group, 0)
              groupConstantCount = groupConstantCount + (group -> (constant + 1))
            }
          } else {
            Logger.info(s"unknown sensor ${monitorRecord._id}")
          }
      })

      val expectedCount = 24 * 60  * 95 / 100

      val groupSummaryList =
        for (group <- todaySensorRecordCount.keys.toList.sorted) yield {
          val groupMonitorCount = monitorOp.map.values.count(m => {
            if (m.sensorDetail.isDefined)
              m.sensorDetail.get.sensorType == group
            else
              false
          })
          val groupRecordCount = todaySensorRecordCount(group)
          val expected = groupRecordCount.count(p => p >= expectedCount)
          val constant = groupConstantCount.getOrElse(group, 0)
          GroupSummary(group, groupMonitorCount, expected, constant)
        }
      groupSummaryList
    }
  }

  /*
  * val addPm25DataStage = Aggregates.addFields(Field("pm25Data",
      Document("$first" ->
        Document("$filter" -> Document(
          "input" -> "$mtDataList",
          "as" -> "mtData",
          "cond" -> Document(
            "$eq" -> Seq("$$mtData.mtName", "PM25")
          )
        )
        ))))
  * */
  val addPm25DataStage: Bson = {
    val filterDoc = Document("$filter" -> Document(
      "input" -> "$mtDataList",
      "as" -> "mtData",
      "cond" -> Document(
        "$eq" -> Seq("$$mtData.mtName", "PM25")
      )))
    val bsonArray = BsonArray(filterDoc.toBsonDocument, new BsonInt32(0))
    Aggregates.addFields(Field("pm25Data",
      Document("$arrayElemAt" -> bsonArray)))
  }

  def getLast10MinConstantSensor(colName: String) = {
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
    val timeFrameFilter = Aggregates.filter(Filters.and(Filters.gt("time", DateTime.now.minusMinutes(10).toDate)))

    val addPm25ValueStage = Aggregates.addFields(Field("pm25", "$pm25Data.value"))
    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"), Accumulators.first("location", "$location"),
      Accumulators.sum("count", 1),
      Accumulators.max("pm25Max", "$pm25"),
      Accumulators.min("pm25Min", "$pm25"))
    val constantFilter = Aggregates.filter(Filters.and(Filters.gte("count", 3),
      Filters.expr(Document("$eq" -> Seq("$pm25Max", "$pm25Min")))
    ))
    val projectStage = Aggregates.project(fields(
      Projections.include("time", "monitor", "id", "mtDataList", "location", "count", "pm25Max", "pm25Min")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, addPm25DataStage, addPm25ValueStage, latestFilter, constantFilter, projectStage)).toFuture()
  }

  def getLast10MinDisconnectSummary(colName: String) = {

    val f1 = getLast10MinDisconnected(colName)
    for {idList <- f1
         } yield {
      var groupCountMap = Map.empty[String, Int]
      var countyGroupMap = Map.empty[String, Map[String, Int]]
      idList foreach { monitorId =>
        if (monitorOp.map.contains(monitorId)) {
          val monitor = monitorOp.map(monitorId)
          for {detail <- monitor.sensorDetail
               county <- monitor.county
               group = detail.sensorType
               } {
            val groupCount = groupCountMap.getOrElse(group, 0)
            groupCountMap = groupCountMap + (group -> (groupCount + 1))
            val countyMap = countyGroupMap.getOrElse(group, Map.empty[String, Int])
            val countyCount = countyMap.getOrElse(county, 0)
            val newCountMap = countyMap + (county -> (countyCount + 1))
            countyGroupMap = countyGroupMap + (group -> newCountMap)
          }
        }
      }

      val groupSummaryList =
        for (group <- countyGroupMap.keys.toList.sorted) yield {
          val total = groupCountMap(group)
          val kl = countyGroupMap(group).getOrElse("基隆市", 0)
          val pt = countyGroupMap(group).getOrElse("屏東縣", 0)
          val yl = countyGroupMap(group).getOrElse("宜蘭縣", 0)
          val rest = total - kl - pt - yl
          DisconnectSummary(group, kl = kl, pt = pt, yl = yl, rest = rest)
        }
      groupSummaryList
    }
  }

  def getLast10MinDisconnected(colName: String): Future[Set[String]] = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val timeFrameFilter = {
      DateTime.now().minusMinutes(10).toDate
      Aggregates.filter(Filters.gt("time", DateTime.now().minusMinutes(10).toDate))
    }

    val targetMonitors = monitorOp.map.values.filter(m => m.tags.contains(MonitorTag.SENSOR))
      .filter(m => m.enabled.getOrElse(true)).map {
      _._id
    } toList

    val monitorFilter =
      Aggregates.filter(Filters.in("monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"), Accumulators.first("location", "$location"))
    val removeIdStage = Aggregates.project(fields(Projections.include("time", "monitor", "id", "mtDataList", "location")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    val f = col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, latestFilter, removeIdStage)).toFuture()
    f.transform(ret => {
      val targetSet: Set[String] = targetMonitors.toSet
      val connected = ret.map(_._id)
      targetSet -- connected
    }, ex => ex)
  }
  /*
  def updateMtRecord(colName: String)(mtName: String, updateList: Seq[(DateTime, Double)], monitor: String = monitorOp.SELF_ID) = {
    import org.mongodb.scala.bson._
    import org.mongodb.scala.model._
    val col = getCollection(colName)
    val seqF =
      for (update <- updateList) yield {
        val btime: BsonDateTime = update._1
        col.updateOne(and(equal("time", btime)), and(Updates.set(mtName + ".v", update._2), Updates.setOnInsert(mtName + ".s", "010")), UpdateOptions().upsert(true)).toFuture()
      }

    import scala.concurrent._
    Future.sequence(seqF)
  } */

}