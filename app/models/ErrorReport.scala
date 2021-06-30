package models

import org.mongodb.scala.model.{ReplaceOptions, UpdateOptions, Updates}
import org.mongodb.scala.result.UpdateResult
import play.api.libs.json.Json

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class EffectRate(_id: String, rate: Double)

case class ErrorReport(_id: Date, noErrorCode: Seq[String], powerError: Seq[String],
                       constant: Seq[String], inEffect: Seq[EffectRate])

object ErrorReport {
  implicit val writeRates = Json.writes[EffectRate]
  implicit val readRates = Json.reads[EffectRate]
  implicit val reads = Json.reads[ErrorReport]
  implicit val writes = Json.writes[ErrorReport]
}

import models.ModelHelper.{errorHandler, waitReadyResult}
import org.mongodb.scala.model.{Filters, Indexes}

import javax.inject._

@Singleton
class ErrorReportOp @Inject()(mongoDB: MongoDB) {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val colName = "errorReports"

  val codecRegistry = fromRegistries(fromProviders(classOf[ErrorReport], classOf[EffectRate]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[ErrorReport](colName).withCodecRegistry(codecRegistry)
  collection.createIndex(Indexes.ascending("powerError")).toFuture()
  collection.createIndex(Indexes.ascending("noErrorCode")).toFuture()

  init

  def init(): Unit = {
    val colNames = waitReadyResult(mongoDB.database.listCollectionNames().toFuture())
    if (!colNames.contains(colName)) {
      val f = mongoDB.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
    }
  }

  def upsert(report: ErrorReport) = {
    val f = collection.replaceOne(Filters.equal("_id", report._id), report, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def insertEmptyIfExist(date: Date) = {
    val emptyDoc = ErrorReport(date, Seq.empty[String], Seq.empty[String], Seq.empty[String], Seq.empty[EffectRate])
    collection.insertOne(emptyDoc).toFuture()
  }

  def initBefore(f:(Date, String)=>Future[UpdateResult])(date:Date, sensorID:String): Unit ={
    insertEmptyIfExist(date).andThen({
        case _ =>
          f(date, sensorID)
      })
  }

  def addNoErrorCodeSensor = initBefore(addNoErrorCodeSensor1) _
  def addNoErrorCodeSensor1(date: Date, sensorID: String): Future[UpdateResult] = {
    val ff = {
      insertEmptyIfExist(date).transform(s => {
        val updates = Updates.addToSet("noErrorCode", sensorID)
        val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
        f.onFailure(errorHandler())
        f
      }, ex => ex)
    }
    ff.flatMap(x=>x)
  }

  def removeNoErrorCodeSensor = initBefore(removeNoErrorCodeSensor1) _
  def removeNoErrorCodeSensor1(date: Date, sensorID: String) = {
    val updates = Updates.pull("noErrorCode", sensorID)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def addPowerErrorSensor = initBefore(addPowerErrorSensor1) _
  def addPowerErrorSensor1(date: Date, sensorID: String) = {
    val updates = Updates.addToSet("powerError", sensorID)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def removePowerErrorSensor = initBefore(removePowerErrorSensor1) _
  def removePowerErrorSensor1(date: Date, sensorID: String) = {
    val updates = Updates.pull("powerError", sensorID)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def get(_id: Date) = {
    val f = collection.find(Filters.equal("_id", _id)).toFuture()
    f.onFailure(errorHandler())
    f
  }
}

