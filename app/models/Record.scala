package models

import com.github.nscala_time.time.Imports._
import com.mongodb.client.model.WriteModel
import models.ModelHelper._
import org.mongodb.scala._
import org.mongodb.scala.bson.BsonDateTime
import play.api._
import play.api.libs.json.{JsString, JsValue, Json, Writes}

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global

case class RecordListID(time: Date, monitor: String)
object RecordListID{
  implicit val writes = Json.writes[RecordListID]
}

case class Record(time: DateTime, value: Double, status: String, monitor: String)

case class MonitorRecord(time: Date, mtDataList: Seq[MtRecord], _id: String, location:Option[Seq[Double]])

case class MtRecord(mtName: String, value: Double, status: String)
case class GroupSummary(name:String, count: Int, expected: Int, below: Int)

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
    RecordList(time, mtDataList, monitor, RecordListID(time, monitor), location= location)
  }

  def apply(time: Date, monitor: String, location: Option[Seq[Double]], mtDataList: Seq[MtRecord]): RecordList = {
    RecordList(time, mtDataList, monitor, RecordListID(time, monitor), location= location)
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

  def createDefaultIndex(colNames:Seq[String]) = {
    for(colName <- colNames){
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
    f onFailure(errorHandler())
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

  def getRecord2Map(colName: String)
                   (mtList: List[String], startTime: DateTime, endTime: DateTime, monitor: String = Monitor.SELF_ID)
                   (skip: Int = 0, limit: Int = 500) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    val f = col.find(and(equal("monitor", monitor), gte("time", startTime.toDate()), lt("time", endTime.toDate())))
      .sort(ascending("time")).skip(skip).limit(limit).toFuture()
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

  /*
  implicit val pointReads : Reads[Point] = new Reads[Point] {
    override def reads(json: JsValue): JsResult[Point] = {
      json match {
        case JsObject(underlying) =>
          try{
            val lat = underlying("lat").validate[Double].get
            val lon = underlying("lon").validate[Double].get
            JsSuccess(Point(Position(lat, lon)))
          }catch {
            case _ :
              NoSuchElementException => JsError(s"Invalid format")
          }

      }
    }
  }*/

  def getRecordListFuture(colName: String)
                         (startTime: DateTime, endTime: DateTime, monitors: Seq[String] = Seq(Monitor.SELF_ID)) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    col.find(and(in("monitor", monitors: _*), gte("time", startTime.toDate()), lt("time", endTime.toDate())))
      .sort(ascending("time")).toFuture()
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

  def getLatestRecordSummary(colName: String) = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val limitFilter = Aggregates.limit(1000)
    val latestFilter = Aggregates.group(id = "$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"), Accumulators.first("location", "$location"))
    val removeIdStage = Aggregates.project(fields(Projections.include("time", "monitor", "id", "mtDataList", "location")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, limitFilter, latestFilter, removeIdStage)).toFuture()
  }


  def getLast24HrCount(colName: String) = {

    val now = DateTime.now
    val todayFilter = Aggregates.`match`(Filters.and(Filters.gte("time", now.minusDays(1).toDate),
      Filters.lt("time", now.toDate)))

    val groupByMonitorCount = Aggregates.group(id = "$monitor", Accumulators.sum("count", 1))
    val col = mongoDB.database.getCollection(colName)
    val f1 = col.aggregate(Seq(todayFilter, groupByMonitorCount)).toFuture()
    val f2 = sensorOp.getFullSensorMap

    for {docList <- f1
         sensorMap <- f2
         } yield {
      var todaySensorRecordCount = Map.empty[String, Seq[Int]]
      Logger.debug(s"Total ${docList.length}")
      docList foreach { doc =>
        val count = doc("count").asInt32().getValue
        val monitor = doc("_id").asString().getValue
        if(sensorMap.contains(monitor)){
          val group = sensorMap(monitor).group
          val monitorCount = todaySensorRecordCount.getOrElse(group, List.empty[Int])
          todaySensorRecordCount = todaySensorRecordCount + (group -> monitorCount.:+(count))
        }
      }

      val expectedCount = 24 * 60 / 3 * 95 / 100
      val groupSummaryList =
        for (group <- todaySensorRecordCount.keys.toList) yield {
          val groupMonitorCount = sensorMap.values.count(_.group == group)
          val groupRecordCount = todaySensorRecordCount(group)
          val expected = groupRecordCount.count(p => p >= expectedCount)
          val below = groupRecordCount.count(p => p < expectedCount)
          GroupSummary(group, groupMonitorCount, expected, below)
        }
      groupSummaryList
    }
  } /*
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