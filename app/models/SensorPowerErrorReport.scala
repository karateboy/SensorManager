package models

import org.mongodb.scala.model.{ReplaceOneModel, ReplaceOptions}

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global

case class PowerErrorReport(_id:Date, var sensors:Seq[String])

object PowerErrorReport {

}

import models.ModelHelper.{errorHandler, waitReadyResult}
import org.mongodb.scala.model.{Filters, Indexes}

import javax.inject._

@Singleton
class PowerErrorReportOp @Inject()(mongoDB: MongoDB, recordOp: RecordOp) {
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val colName = "powerErrorReports"

  val codecRegistry = fromRegistries(fromProviders(classOf[PowerErrorReport]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[PowerErrorReport](colName).withCodecRegistry(codecRegistry)
  collection.createIndex(Indexes.ascending("sensors")).toFuture()

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

  def get(_id:Date) = {
    val f = collection.find(Filters.equal("_id", _id)).first().toFuture()
    f.onFailure(errorHandler())
    f
  }
}

