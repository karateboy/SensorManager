package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports._
import models.SensorPowerChecker.CheckPowerUsage
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object SensorPowerChecker {

  case object CheckPowerUsage
}

class SensorPowerChecker @Inject()(powerErrorReportOp: PowerErrorReportOp) extends Actor {

  def receive = handler

  def handler: Receive = {
    case CheckPowerUsage =>

  }

}