package models

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import org.mongodb.scala._
import org.mongodb.scala.bson.BsonDateTime
import play.api._

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global

case class MtRecord(mtName: String, value: Double, status: String)
case class TodaySummary(count: Int, max: Int, maxCount: Int, min: Int, minCount: Int, expected: Int, belowExpected: Int)
object RecordList {
  def apply(time: Date, mtDataList: Seq[MtRecord], monitor: String): RecordList =
    RecordList(time, mtDataList, monitor, RecordListID(time, monitor))
}

case class RecordList(time: Date, mtDataList: Seq[MtRecord], monitor: String, _id: RecordListID) {
  def mtMap = {
    val pairs =
      mtDataList map { data => data.mtName -> data }
    pairs.toMap

  }
}

case class RecordListID(time: Date, monitor: String)

case class Record(time: DateTime, value: Double, status: String, monitor: String)

case class MonitorRecord(time: Date, mtDataList: Seq[MtRecord], monitor: String) {
  def mtMap = {
    val pairs =
      mtDataList map { data => data.mtName -> data }
    pairs.toMap
  }
}
import javax.inject._

@Singleton
class RecordOp @Inject()(mongoDB: MongoDB, monitorTypeOp: MonitorTypeOp, monitorOp: MonitorOp) {

  import org.mongodb.scala.model._
  import play.api.libs.json._

  implicit val writer = Json.writes[Record]

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

  getCollection(HourCollection).createIndex(Indexes.descending("time", "monitor"), new IndexOptions().unique(true))
  getCollection(MinCollection).createIndex(Indexes.descending("time", "monitor"), new IndexOptions().unique(true))
  getCollection(SecCollection).createIndex(Indexes.descending("time", "monitor"), new IndexOptions().unique(true))

  def upgrade() = {
    Logger.info("upgrade record!")
    import org.mongodb.scala.model._
    val col = mongoDB.database.getCollection(HourCollection)
    val recordF = col.find(Filters.exists("_id")).toFuture()
    for (records <- recordF) {
      var i = 1
      for (doc <- records) {
        val newID = Document("time" -> doc.get("_id").get.asDateTime(), "monitor" -> monitorOp.SELF_ID)
        val newDoc = doc ++ Document("_id" -> newID,
          "time" -> doc.get("_id").get.asDateTime(),
          "monitor" -> monitorOp.SELF_ID)
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

  def toRecordList(dt: DateTime, dataList: List[(String, (Double, String))], monitor: String = monitorOp.SELF_ID) = {
    val mtDataList = dataList map { t => MtRecord(t._1, t._2._1, t._2._2) }
    RecordList(dt, mtDataList, monitor, RecordListID(dt, monitor))
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

  def updateRecordStatus(dt: Long, mt: String, status: String, monitor: String = monitorOp.SELF_ID)(colName: String) = {
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

  def getCollection(colName: String) = mongoDB.database.getCollection[RecordList](colName).withCodecRegistry(codecRegistry)

  def getRecord2Map(colName: String)(mtList: List[String], startTime: DateTime, endTime: DateTime, monitor: String = monitorOp.SELF_ID)
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

  implicit val mtRecordWrite = Json.writes[MtRecord]
  implicit val idWrite = Json.writes[RecordListID]
  implicit val recordListWrite = Json.writes[RecordList]

  def getRecordListFuture(colName: String)(startTime: DateTime, endTime: DateTime, monitors: Seq[String] = Seq(monitorOp.SELF_ID)) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    col.find(and(in("monitor", monitors: _*), gte("time", startTime.toDate()), lt("time", endTime.toDate())))
      .sort(ascending("time")).toFuture()
  }

  def getRecordWithLimitFuture(colName: String)(startTime: DateTime, endTime: DateTime, limit: Int, monitor: String = monitorOp.SELF_ID) = {
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
    import org.mongodb.scala.model.Sorts._
    import org.mongodb.scala.model.Projections._

    val sortFilter = Aggregates.sort(orderBy(descending("time"), descending("monitor")))
    val limitFilter = Aggregates.limit(1000)
    val latestFilter = Aggregates.group(id="$monitor", Accumulators.first("time", "$time"),
      Accumulators.first("mtDataList", "$mtDataList"))
    val removeIdStage = Aggregates.project(fields(Projections.include("time", "monitor", "id", "mtDataList")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, limitFilter, removeIdStage)).toFuture()
  }

  def getLast24HrCount(colName: String) = {

    val now = LocalDate.now()
    val todayFilter = Aggregates.`match`(Filters.and(Filters.gte("time", now.minusDays(1).toDate()),
      Filters.lt("time", now.toDate())))

    val groupByMonitorCount = Aggregates.group(id = "$monitor", Accumulators.sum("count", 1))
    val col = mongoDB.database.getCollection(colName)
    val f = col.aggregate(Seq(todayFilter, groupByMonitorCount)).toFuture()
    for (docList <- f) yield {
      val todayCounts =
        docList map { doc =>
          val count = doc("count").asInt32().getValue
          count
        }

      val count = todayCounts.length
      val (max:Int, maxCount:Int, min:Int, minCount:Int) =
        if (count != 0) {
          val max = todayCounts.max
          val maxCount = todayCounts.count(_ == max)
          val min = todayCounts.min
          val minCount = todayCounts.count(_ == min)
          (max, maxCount, min, minCount)
        } else {
          (0, 0, 0, 0)
        }

      val expected = 24*60 / 3 * 95 / 100
      val belowExpected = todayCounts.count(_ < expected)
      TodaySummary(count = count, max = max, maxCount = maxCount, min = min, minCount = minCount, expected = expected, belowExpected = belowExpected)
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

  implicit val summaryWrites = Json.writes[TodaySummary]
  implicit val monitorRecordWrite = Json.writes[MonitorRecord]
}