package models
import com.mongodb.client.model.UpdateManyModel
import org.mongodb.scala.model.{ReplaceOneModel, ReplaceOptions}

import scala.concurrent.ExecutionContext.Implicits.global
case class MonitorGroup(_id:String, var member:Seq[String])

object MonitorGroup {

}

import models.ModelHelper.{errorHandler, waitReadyResult}
import org.mongodb.scala.model.{Filters, Indexes}


import javax.inject._
@Singleton
class MonitorGroupOp @Inject()(mongoDB: MongoDB) {
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val colName = "monitorGroups"

  val codecRegistry = fromRegistries(fromProviders(classOf[MonitorGroup]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[MonitorGroup](colName).withCodecRegistry(codecRegistry)
  collection.createIndex(Indexes.ascending("member")).toFuture()

  def init(): Unit = {
    val colNames = waitReadyResult(mongoDB.database.listCollectionNames().toFuture())
    if (!colNames.contains(colName)) {
      val f = mongoDB.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
    }
  }

  init

  def upsert(mg: MonitorGroup) = {
    val f = collection.replaceOne(Filters.equal("_id", mg._id), mg, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def get(_id:String) = {
    val f = collection.find(Filters.equal("_id", _id)).first().toFuture()
    f.onFailure(errorHandler())
    f
  }

  def upsertMany(mgList:Seq[MonitorGroup])={
    val updateModels: Seq[ReplaceOneModel[MonitorGroup]] = mgList map {
      mg =>
        ReplaceOptions().upsert(true)
        ReplaceOneModel(Filters.equal("_id", mg._id), mg, ReplaceOptions().upsert(true))
    }
    val f = collection.bulkWrite(updateModels).toFuture()
    f onFailure (errorHandler)
    f
  }

  def getList() = {
    val f = collection.find(Filters.exists("_id")).toFuture()
    f onFailure(errorHandler())
    f
  }

  def delete(_id: String) = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f onFailure(errorHandler())
    f
  }
}

