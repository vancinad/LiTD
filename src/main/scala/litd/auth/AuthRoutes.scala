package litd.auth

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.HttpCookie
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import io.circe.syntax.EncoderOps

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

final class AuthRoutes(
    config: AuthConfig,
    authService: AuthService
)(implicit ec: ExecutionContext) {

  /** API endpoint: GET /auth/lichess/start redirects user to Lichess OAuth authorization page. */
  private val startRoute: Route =
    path("auth" / "lichess" / "start") {
      get {
        redirect(authService.startOAuth(), StatusCodes.Found)
      }
    }

  /** API endpoint: GET /auth/lichess/callback exchanges OAuth code and creates authenticated session cookie. */
  private val callbackRoute: Route =
    path("auth" / "lichess" / "callback") {
      get {
        parameters("code".?, "state".?, "error".?, "error_description".?) { (codeOpt, stateOpt, errorOpt, errorDescOpt) =>
          errorOpt match {
            case Some(errorValue) =>
              val details = errorDescOpt.filter(_.trim.nonEmpty).getOrElse("No details provided")
              complete(
                StatusCodes.BadRequest -> Map("error" -> s"Lichess OAuth error: $errorValue ($details)").asJson.noSpaces
              )
            case None =>
              (codeOpt, stateOpt) match {
                case (Some(code), Some(state)) =>
                  onComplete(authService.finishOAuth(code, state)) {
                    case Success(Right(result)) =>
                      setCookie(
                        HttpCookie(
                          name = config.session.cookieName,
                          value = result.sessionToken,
                          httpOnly = true,
                          secure = config.session.secureCookie,
                          maxAge = Some(config.session.maxAgeSeconds.toLong),
                          path = Some("/")
                        )
                      ) {
                        complete(
                          StatusCodes.OK -> Map(
                            "status" -> "authenticated",
                            "lichessUserId" -> result.lichessUserId,
                            "sessionExpiresAt" -> result.expiresAt.map(_.toString).getOrElse("unknown")
                          ).asJson.noSpaces
                        )
                      }
                    case Success(Left(error)) =>
                      complete(error.status -> Map("error" -> error.message).asJson.noSpaces)
                    case Failure(ex) =>
                      complete(StatusCodes.BadGateway -> Map("error" -> ex.getMessage).asJson.noSpaces)
                  }
                case _ =>
                  complete(StatusCodes.BadRequest -> Map("error" -> "Missing OAuth callback parameters").asJson.noSpaces)
              }
          }
        }
      }
    }

  /** API endpoint: GET /auth/me returns authenticated user when session cookie is valid and team-gated. */
  private val meRoute: Route =
    path("auth" / "me") {
      get {
        optionalCookie(config.session.cookieName) {
          case None =>
            complete(StatusCodes.Unauthorized -> Map("error" -> "Missing auth session cookie").asJson.noSpaces)
          case Some(cookie) =>
            onComplete(authService.authenticate(cookie.value)) {
              case Success(Right(user)) =>
                complete(StatusCodes.OK -> Map("lichessUserId" -> user.lichessUserId).asJson.noSpaces)
              case Success(Left(error)) =>
                complete(error.status -> Map("error" -> error.message).asJson.noSpaces)
              case Failure(ex) =>
                complete(StatusCodes.BadGateway -> Map("error" -> ex.getMessage).asJson.noSpaces)
            }
        }
      }
    }

  val routes: Route = startRoute ~ callbackRoute ~ meRoute
}
