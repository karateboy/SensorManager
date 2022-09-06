package models

import models.ModelHelper._
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.model.{Filters, Updates}
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions


case class Ability(action: String, subject: String)

case class Group(_id: String, name: String, monitors: Seq[String], monitorTypes: Seq[String],
                 admin: Boolean, abilities: Seq[Ability])

import javax.inject._

@Singleton
class GroupOp @Inject()(mongoDB: MongoDB, monitorOp: MonitorOp) {

  import org.mongodb.scala._

  val ColName = "groups"
  val codecRegistry = fromRegistries(fromProviders(classOf[Group], classOf[Ability], DEFAULT_CODEC_REGISTRY))
  val collection: MongoCollection[Group] = mongoDB.database.withCodecRegistry(codecRegistry).getCollection(ColName)

  implicit val readAbility = Json.reads[Ability]
  implicit val writeAbility = Json.writes[Ability]
  implicit val read = Json.reads[Group]
  implicit val write = Json.writes[Group]
  val PLATFORM_ADMIN = "platformAdmin"
  val PLATFORM_USER = "platformUser"

  val ACTION_READ = "read"
  val ACTION_MANAGE = "manage"
  val ACTION_SET = "set"

  val SUBJECT_ALL = "all"
  val SUBJECT_DASHBOARD = "Dashboard"
  val SUBJECT_DATA = "Data"
  val SUBJECT_ALARM = "Alarm"

  val defaultGroup: Seq[Group] =
    Seq(
      Group(_id = PLATFORM_ADMIN, "平台管理團隊", Seq.empty[String], Seq.empty[String],
        true, Seq(Ability(ACTION_MANAGE, SUBJECT_ALL))),
      Group(_id = PLATFORM_USER, "平台使用者", Seq.empty[String], Seq.empty[String],
        false, Seq(Ability(ACTION_READ, SUBJECT_DASHBOARD),
          Ability(ACTION_READ, SUBJECT_DATA),
          Ability(ACTION_SET, SUBJECT_ALARM)))
    )

  def init() {
    for (colNames <- mongoDB.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(ColName)) {
        val f = mongoDB.database.createCollection(ColName).toFuture()
        f.onFailure(errorHandler)
      }
    }
    createDefaultGroup
  }

  def createDefaultGroup = {
    for (group <- defaultGroup) yield {
      val f = collection.insertOne(group).toFuture()
      f
    }
  }

  init

  def upgrade(): Unit = {
    val updates = Updates.combine(Updates.set("monitors", Seq.empty[String]), Updates.set("monitorTypes", Seq.empty[String]))
    collection.updateMany(Filters.not(Filters.exists("monitors")), updates).toFuture()
  }

  def newGroup(group: Group) = {
    val f = collection.insertOne(group).toFuture()
    waitReadyResult(f)
  }

  import org.mongodb.scala.model.Filters._

  def deleteGroup(_id: String) = {
    val f = collection.deleteOne(equal("_id", _id)).toFuture()
    waitReadyResult(f)
  }

  def updateGroup(group: Group) = {
    val f = collection.replaceOne(equal("_id", group._id), group).toFuture()
    waitReadyResult(f)
  }

  def getGroupByID(_id: String) = {
    val f = collection.find(equal("_id", _id)).first().toFuture()
    f.onFailure {
      errorHandler
    }
    val group = waitReadyResult(f)
    if (group != null)
      Some(group)
    else
      None
  }

  def getGroupByIdAsync(_id: String): Future[Option[Group]] = {
    val f = collection.find(equal("_id", _id)).toFuture()
    f.onFailure {
      errorHandler
    }
    for (rets <- f) yield
      if (rets.nonEmpty)
        Some(rets(0))
      else
        None
  }

  def getAllGroups() = {
    val f = collection.find().toFuture()
    f.onFailure {
      errorHandler
    }
    waitReadyResult(f)
  }
}
