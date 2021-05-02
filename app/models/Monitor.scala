package models

import models.ModelHelper._
import org.mongodb.scala.model._
import play.api._
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global

case class Monitor(_id: String, desc: String, monitorTypes: Seq[String], var tags: Seq[String],
                   location: Option[Seq[Double]] = None, var shortCode:Option[String]=None,
                   var code:Option[String]=None, var county:Option[String]=None, var district:Option[String]=None)
object Monitor {
  implicit val mWrite = Json.writes[Monitor]
  implicit val mRead = Json.reads[Monitor]

  val SELF_ID = ""
  val selfMonitor = Monitor(SELF_ID, "本站", monitorTypes=Seq.empty[String], tags=Seq.empty[String])
  def epaID(id:String) = s"epa$id"
}

import javax.inject._

object MonitorTag {
  val SENSOR = "sensor"
  val EPA = "EPA"
  val ID = "ID"
  val OT = "OT"
  val CO = "CO"
  val LO = "LO"
  val MO = "MO"
}


@Singleton
class MonitorOp @Inject()(mongoDB: MongoDB, config: Configuration) {


  import Monitor._
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val colName = "monitors"
  mongoDB.database.createCollection(colName).toFuture()

  val codecRegistry = fromRegistries(fromProviders(classOf[Monitor]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[Monitor](colName).withCodecRegistry(codecRegistry)
  collection.createIndex(Indexes.ascending("monitorTypes")).toFuture()
  collection.createIndex(Indexes.ascending("tags")).toFuture()
  collection.createIndex(Indexes.geo2dsphere("location")).toFuture()

  val hasSelfMonitor = config.getBoolean("selfMonitor").getOrElse(false)

  var map: Map[String, Monitor] = {
    val pairs =
      for (m <- mList) yield {
        m._id -> m
      }
    pairs.toMap
  }

  def init(): Unit = {
    val colNames = waitReadyResult(mongoDB.database.listCollectionNames().toFuture())
    if (!colNames.contains(colName)) {
      val f = mongoDB.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
    }

    val ret = waitReadyResult(collection.countDocuments(Filters.equal("_id", "")).toFuture())
    if (ret == 0) {
      waitReadyResult(collection.insertOne(selfMonitor).toFuture())
    }
  }

  init

  refresh

  def upgrade = {
    // upgrade if no monitorTypes
    val f = mongoDB.database.getCollection(colName).updateMany(
      Filters.exists("tags", false), Updates.set("tags", Seq.empty[String])).toFuture()

    waitReadyResult(f)
  }

  def mvList = mList.map(_._id).filter({
    p =>
      hasSelfMonitor || p != SELF_ID
  })

  def ensureMonitor(_id: String, desc:String, monitorTypes:Seq[String], tags:Seq[String]): Monitor = {
    if (!map.contains(_id)) {
      val monitor = Monitor(_id, desc, monitorTypes, tags)
      newMonitor(monitor)
      monitor
    }else
      map(_id)
  }

  def newMonitor(m: Monitor) = {
    Logger.debug(s"Create monitor value ${m._id}!")
    map = map + (m._id -> m)


    val f = collection.insertOne(m).toFuture()
    f.onFailure(errorHandler)

    f.onSuccess({
      case _: Seq[t] =>
    })
    m._id
  }

  def format(v: Option[Double]) = {
    if (v.isEmpty)
      "-"
    else
      v.get.toString
  }

  def upsert(m: Monitor) = {
    val f = collection.replaceOne(Filters.equal("_id", m._id), m, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    waitReadyResult(f)
    refresh
  }

  def refresh {
    val pairs =
      for (m <- mList) yield {
        m._id -> m
      }
    map = pairs.toMap
  }

  private def mList: List[Monitor] = {
    val f = collection.find().sort(Sorts.ascending("_id")).toFuture()
    val ret = waitReadyResult(f)
    ret.toList
  }

  def upsertMany(monitors: Seq[Monitor]) = {
    val updateModels: Seq[ReplaceOneModel[Monitor]] = monitors map {
      m =>
        ReplaceOptions().upsert(true)
        ReplaceOneModel(Filters.equal("_id", m._id), m, ReplaceOptions().upsert(true))
    }
    val pairs = monitors map { m => m._id -> m }
    map = map ++ pairs
    val f= collection.bulkWrite(updateModels).toFuture()
    f onFailure(errorHandler)
    f
  }
}