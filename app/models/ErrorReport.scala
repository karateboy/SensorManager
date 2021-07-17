package models

import org.joda.time.DateTime
import org.mongodb.scala.model.{ReplaceOptions, Updates}
import org.mongodb.scala.result.UpdateResult
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.mailer.{Email, MailerClient}

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class EffectiveRate(_id: String, rate: Double)

case class ErrorReport(_id: Date, noErrorCode: Seq[String], powerError: Seq[String],
                       constant: Seq[String], ineffective: Seq[EffectiveRate], dailyChecked: Boolean = false)

object ErrorReport {
  implicit val writeRates = Json.writes[EffectiveRate]
  implicit val readRates = Json.reads[EffectiveRate]
  implicit val reads = Json.reads[ErrorReport]
  implicit val writes = Json.writes[ErrorReport]
}

import models.ModelHelper.{errorHandler, waitReadyResult}
import org.mongodb.scala.model.{Filters, Indexes}

import javax.inject._

@Singleton
class ErrorReportOp @Inject()(mongoDB: MongoDB, mailerClient: MailerClient, monitorOp: MonitorOp) {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val colName = "errorReports"

  val codecRegistry = fromRegistries(fromProviders(classOf[ErrorReport], classOf[EffectiveRate]), DEFAULT_CODEC_REGISTRY)
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
    ff.flatMap(x => x)
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

  def addConstantSensor = initBefore(addConstantSensor1) _

  def addConstantSensor1(date: Date, sensorID: String) = {
    val updates = Updates.addToSet("constant", sensorID)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def addLessThan90Sensor = initBefore(addLessThan90Sensor1) _

  def initBefore[T](f: (Date, T) => Future[UpdateResult])(date: Date, sensorID: T): Unit = {
    insertEmptyIfExist(date).andThen({
      case _ =>
        f(date, sensorID)
    })
  }

  def insertEmptyIfExist(date: Date) = {
    val emptyDoc = ErrorReport(date, Seq.empty[String], Seq.empty[String], Seq.empty[String], Seq.empty[EffectiveRate])
    collection.insertOne(emptyDoc).toFuture()
  }

  def addLessThan90Sensor1(date: Date, effectRateList: Seq[EffectiveRate]) = {
    Logger.info(s"lt90 #=${effectRateList.size}")
    val updates = Updates.combine(
      Updates.addEachToSet("ineffective", effectRateList: _*),
      Updates.set("dailyChecked", true))
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def sendEmail(receiverEmails: Seq[String]) = {
    val today = DateTime.now.withMillisOfDay(0)
    val f = get(today.toDate)
    f onFailure (errorHandler())
    for (reports <- f) yield {
      val (kl, pt, yl) =
        if (reports.isEmpty) {
          Logger.info("Emtpy report!")
          (Seq.empty[Monitor], Seq.empty[Monitor], Seq.empty[Monitor])
        } else {
          val report = reports(0)
          val monitors = report.powerError.map(monitorOp.map)
          val kl = monitors.filter(_.county == Some("基隆市"))
          val pt = monitors.filter(_.county == Some("屏東縣"))
          val yl = monitors.filter(_.county == Some("宜蘭縣"))
          (kl, pt, yl)
        }
      val htmlBody = views.html.errorReport(today.toString("yyyy/MM/dd"), kl, pt, yl).body
      val mail = Email(
        subject = s"${today.toString("yyyy/MM/dd")}電力異常設備",
        from = "Aragorn <karateboy@sagainfo.com.tw>",
        to = receiverEmails,
        bodyHtml = Some(htmlBody)
      )
      try {
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader())
        mailerClient.send(mail)
      } catch {
        case ex: Exception =>
          Logger.error("Failed to send email", ex)
      }

    }
  }

  def get(_id: Date) = {
    val f = collection.find(Filters.equal("_id", _id)).toFuture()
    f.onFailure(errorHandler())
    f
  }
}

