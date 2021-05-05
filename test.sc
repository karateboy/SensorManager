import com.mongodb.client.model.geojson.Point
import play.api.libs.functional.FunctionalBuilder
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{JsObject, JsPath, JsValue, Json, OWrites, Reads}

val topic = "WECC/SAQ200/861108035980803/sensor"
val topic1 = "861108035980803/sensor"
val pattern = "WECC/SAQ200/([0-9]+)/sensor".r
val pattern1 = "WECC/SAQ200/([0-9]+)/.*".r
val pattern1(a) =  topic

implicit val w1: OWrites[Point] = new OWrites[Point] {
  override def writes(o: Point):JsObject = {
    val build: FunctionalBuilder[OWrites]#CanBuild2[Double, Double] = (
      (JsPath\"lat").write[Double] and
        (JsPath\"lon").write[Double])
    build((0,0))
  }
}

implicit val r1: Reads[Point] = new Reads[Point] {
  override def reads(json: JsValue) = ???
}