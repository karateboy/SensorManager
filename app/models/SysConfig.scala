package models
import com.github.nscala_time.time.Imports.LocalTime
import models.ModelHelper._
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions
import org.mongodb.scala.model._
import org.mongodb.scala.bson._
import org.mongodb.scala.result.UpdateResult

import java.util.Date
import javax.inject._
import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.Future

object SysConfig {
  val valueKey = "value"
  val MonitorTypeVer = "Version"
  val EpaLastDataTime = "EpaLastDateTime"
  val SensorGPS = "SensorGPS"
  val AlertEmailTaget = "AlertEmailTarget"
  val ConstantCheckTime = "ConstantCheckTime"
  val MoveRecord = "MoveRecord"
}
@Singleton
class SysConfig @Inject()(mongoDB: MongoDB){
  val ColName = "sysConfig"
  val collection = mongoDB.database.getCollection(ColName)

  import SysConfig._
  val defaultConfig:Map[String, Document] = Map(
    MonitorTypeVer -> Document(valueKey -> 1),
    EpaLastDataTime -> Document(valueKey -> DateTime.parse("2021-4-28").toDate),
    SensorGPS-> Document(valueKey->false),
    AlertEmailTaget -> Document(valueKey -> Seq("karateboy.tw@gmail.com")),
    ConstantCheckTime->Document(valueKey -> "07:00"),
    MoveRecord->Document(valueKey->false)
  )

  def init() {
    for(colNames <- mongoDB.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(ColName)) {
        val f = mongoDB.database.createCollection(ColName).toFuture()
        f.onFailure(errorHandler)
        waitReadyResult(f)
      }
    }
    val idSet = defaultConfig.keySet

    //Clean up unused
    val f1 = collection.deleteMany(Filters.not(Filters.in("_id", idSet.toList: _*))).toFuture()
    f1.onFailure(errorHandler)
    val updateModels =
      for ((k, defaultDoc) <- defaultConfig) yield {
        UpdateOneModel(
          Filters.eq("_id", k),
          Updates.setOnInsert(valueKey, defaultDoc(valueKey)), UpdateOptions().upsert(true))
      }

    val f2 = collection.bulkWrite(updateModels.toList, BulkWriteOptions().ordered(false)).toFuture()

    import scala.concurrent._
    val f = Future.sequence(List(f1, f2))
    waitReadyResult(f)
  }
  init

  def upsert(_id: String, doc: Document) = {
    val uo = new ReplaceOptions().upsert(true)
    val f = collection.replaceOne(Filters.equal("_id", _id), doc, uo).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def get(_id: String) = {
    val f = collection.find(Filters.eq("_id", _id.toString())).headOption()
    f.onFailure(errorHandler)
    for (ret <- f) yield {
      val doc = ret.getOrElse(defaultConfig(_id))
      doc("value")
    }
  }

  def set(_id: String, v: Date) = upsert(_id, Document(valueKey -> v))
  def set(_id: String, v: Boolean) = upsert(_id, Document(valueKey -> v))
  def set(_id: String, v: String) = upsert(_id, Document(valueKey -> v))
  def set(_id: String, v: Seq[String]) = upsert(_id, Document(valueKey -> v))

  def getEpaLastDataTime(): Future[Date] =
    for(v<-get(EpaLastDataTime)) yield
      v.asDateTime().toDate

  def setEpaLastDataTime(time:Date) = set(EpaLastDataTime, time)

  def getAlertEmailTarget() =
    for(v<-get(AlertEmailTaget)) yield
      v.asArray().toSeq.map(_.asString().getValue)

  def setAlertEmailTarget(emails: Seq[String]) = set(AlertEmailTaget, emails)

  def setConstantCheckTime(localTime: LocalTime) = set(ConstantCheckTime, localTime.toString)
  def getConstantCheckTime() = get(ConstantCheckTime).map(v=>LocalTime.parse(v.asString().getValue))

  def getMoveRecord(): Future[Boolean] = get(MoveRecord).map(_.asBoolean().getValue)
  def setMoveRecord(v:Boolean): Future[UpdateResult] = set(MoveRecord, v)
}