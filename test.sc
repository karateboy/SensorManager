import com.mongodb.client.model.geojson.Point
import play.api.libs.functional.FunctionalBuilder
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{JsObject, JsPath, JsValue, Json, OWrites, Reads}

val topic = "WECC/SAQ200/861108035980803/sensor"
val topic1 = "861108035980803/sensor"
val pattern = "WECC/SAQ200/([0-9]+)/sensor".r
val pattern1 = "WECC/SAQ200/([0-9]+)/.*".r
val pattern1(a) =  topic
val d = "蘇澳鎮(SA)"
val district1 = d.dropWhile(_ != '(').drop(1).takeWhile(_ != ')')

