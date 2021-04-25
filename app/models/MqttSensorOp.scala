package models

import models.ModelHelper.errorHandler

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global


case class Sensor(id: String, topic: String, monitor: String, group: String)

@Singleton
class MqttSensorOp @Inject()(mongoDB: MongoDB, instrumentOp: InstrumentOp, instrumentTypeOp: InstrumentTypeOp) {

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

  /*
  def upgrade = {
    val count = waitReadyResult(collection.countDocuments().toFuture())
    if(count == 0){
      val mqttList = instrumentOp.getInstrumentList().filter(p => p.instType == instrumentTypeOp.MQTT_CLIENT)
      val sensorList = mqttList map { m =>
        val config = MqttCollector.validateParam(m.param)
        val topicPattern = "WECC/SAQ200/([0-9]+)/.*".r
        val topicPattern(id) = config.topic
        Sensor(id, config.topic, config.monitor, MqttCollector2.defaultGroup)
      }
      collection.insertMany(sensorList, InsertManyOptions().ordered(true)).toFuture()
    }
  }*/

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
}
