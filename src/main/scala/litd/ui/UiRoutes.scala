package litd.ui

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

final class UiRoutes {

  private val appRoutes: Route =
    pathSingleSlash {
      getFromResource("ui/index.html")
    } ~
      path("auth" / "callback") {
        getFromResource("ui/index.html")
      } ~
      path("tournaments" / Segment) { _ =>
        getFromResource("ui/index.html")
      } ~
      path("tournaments" / Segment / "standings") { _ =>
        getFromResource("ui/index.html")
      } ~
      path("tournaments" / Segment / "crosstable") { _ =>
        getFromResource("ui/index.html")
      } ~
      path("tournaments" / Segment / "rounds" / IntNumber) { (_, _) =>
        getFromResource("ui/index.html")
      } ~
      path("tournaments" / Segment / "admin") { _ =>
        getFromResource("ui/index.html")
      }

  private val assetRoutes: Route =
    pathPrefix("ui") {
      getFromResourceDirectory("ui")
    }

  val routes: Route = assetRoutes ~ appRoutes
}
