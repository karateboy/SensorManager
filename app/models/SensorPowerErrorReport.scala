package models

import org.mongodb.scala.model.{ReplaceOptions, UpdateOptions, Updates}
import play.api.libs.json.Json

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global

case class PowerErrorReport(_id: Date, var noErrorCodeSensors: Seq[String], var powerErrorSensors: Seq[String])

object PowerErrorReport {
  implicit val reads = Json.reads[PowerErrorReport]
  implicit val writes = Json.writes[PowerErrorReport]
}

import models.ModelHelper.{errorHandler, waitReadyResult}
import org.mongodb.scala.model.{Filters, Indexes}

import javax.inject._

@Singleton
class PowerErrorReportOp @Inject()(mongoDB: MongoDB) {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val colName = "powerErrorReports"

  val codecRegistry = fromRegistries(fromProviders(classOf[PowerErrorReport]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[PowerErrorReport](colName).withCodecRegistry(codecRegistry)
  collection.createIndex(Indexes.ascending("powerErrorSensors")).toFuture()
  collection.createIndex(Indexes.ascending("noErrorCodeSensors")).toFuture()

  def init(): Unit = {
    val colNames = waitReadyResult(mongoDB.database.listCollectionNames().toFuture())
    if (!colNames.contains(colName)) {
      val f = mongoDB.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
    }
  }

  init

  def upsert(report: PowerErrorReport) = {
    val f = collection.replaceOne(Filters.equal("_id", report._id), report, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def addNoErrorCodeSensor(date: Date, sensorID: String) = {
    val updates = Updates.combine(Updates.addToSet("noErrorCodeSensors", sensorID),
      Updates.setOnInsert("powerErrorSensors", Seq.empty[String]))
    val f = collection.updateOne(Filters.equal("_id", date), updates,
      UpdateOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def addPowerErrorSensor(date: Date, sensorID: String) = {
    val updates = Updates.combine(Updates.addToSet("powerErrorSensors", sensorID),
      Updates.setOnInsert("noErrorCodeSensors", Seq.empty[String]))
    val f = collection.updateOne(Filters.equal("_id", date), updates,
      UpdateOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def get(_id: Date) = {
    val f = collection.find(Filters.equal("_id", _id)).toFuture()
    f.onFailure(errorHandler())
    f
  }
}

