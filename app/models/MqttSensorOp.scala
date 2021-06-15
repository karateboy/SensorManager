package models

import models.ModelHelper.errorHandler

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global


case class Sensor(id: String, topic: String, monitor: String, group: String, powerUsageError:Option[Boolean] = Some(false))

@Singleton
class MqttSensorOp @Inject()(mongoDB: MongoDB) {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val ColName = "sensors"
  val codecRegistry = fromRegistries(fromProviders(classOf[Sensor]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[Sensor](ColName).withCodecRegistry(codecRegistry)

  import org.mongodb.scala.model._

  collection.createIndex(Indexes.descending("group"))
  collection.createIndex(Indexes.descending("topic"))
  collection.createIndex(Indexes.descending("monitor"))
  collection.createIndex(Indexes.descending("powerUsageError"))

  def getSensorMap(group: String) = {
    for (sensorList <- getSensorList(group)) yield {
      val pairs =
        for (sensor <- sensorList) yield
          sensor.id -> sensor

      pairs.toMap
    }
  }

  def getSensorList(group: String) = {
    val f = collection.find(Filters.eq("group", group)).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def getFullSensorMap = {
    val f = collection.find(Filters.exists("_id")).toFuture()
    f onFailure (errorHandler())
    for (sensors <- f) yield {
      val pairs = for (sensor <- sensors) yield
        sensor.id -> sensor

      pairs.toMap
    }
  }

  def newSensor(sensor: Sensor) = {
    val f = collection.insertOne(sensor).toFuture()
    f onFailure (errorHandler)
    f
  }

  def upsertSensor(sensor:Sensor) = {
    val f = collection.replaceOne(Filters.equal("_id", sensor.id), sensor).toFuture()
    f onFailure(errorHandler())
    f
  }

  def updatePowerUsageError(_id:String, powerUsageError:Boolean) = {
    val f = collection.updateOne(Filters.equal("id", _id), Updates.set("powerUsageError", powerUsageError)).toFuture()
    f onFailure(errorHandler())
    f
  }

  def getPowerUsageErrorSensors() = {
    val f = collection.find(Filters.equal("powerUsageError", true)).toFuture()
    f onFailure(errorHandler())
    f
  }
}
