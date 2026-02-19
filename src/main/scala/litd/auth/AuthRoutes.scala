package litd.auth

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.HttpCookie
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.EncoderOps

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

final class AuthRoutes(
    config: AuthConfig,
    authService: AuthService
)(implicit ec: ExecutionContext) {
  private implicit val lichessTeamViewEncoder: Encoder[LichessTeamView] = deriveEncoder[LichessTeamView]
  private def redirectToLandingWithError(message: String): Route =
    redirect(
      s"/?authError=${URLEncoder.encode(message, StandardCharsets.UTF_8.name())}",
      StatusCodes.Found
    )

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
              redirectToLandingWithError(s"Lichess OAuth error: $errorValue ($details)")
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
                        redirect("/", StatusCodes.Found)
                      }
                    case Success(Left(error)) =>
                      redirectToLandingWithError(error.message)
                    case Failure(ex) =>
                      redirectToLandingWithError(ex.getMessage)
                  }
                case _ =>
                  redirectToLandingWithError("Missing OAuth callback parameters")
              }
          }
        }
      }
    }

  /** API endpoint: GET /auth/me returns authenticated user when session cookie is valid. */
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

  /** API endpoint: GET /auth/teams returns teams for the authenticated Lichess user. */
  private val teamsRoute: Route =
    path("auth" / "teams") {
      get {
        optionalCookie(config.session.cookieName) {
          case None =>
            complete(StatusCodes.Unauthorized -> Map("error" -> "Missing auth session cookie").asJson.noSpaces)
          case Some(cookie) =>
            onComplete(authService.authenticate(cookie.value)) {
              case Success(Right(user)) =>
                onComplete(authService.listTeams(user)) {
                  case Success(Right(teams)) => complete(StatusCodes.OK -> Map("teams" -> teams).asJson.noSpaces)
                  case Success(Left(error))  => complete(error.status -> Map("error" -> error.message).asJson.noSpaces)
                  case Failure(ex) => complete(StatusCodes.BadGateway -> Map("error" -> ex.getMessage).asJson.noSpaces)
                }
              case Success(Left(error)) =>
                complete(error.status -> Map("error" -> error.message).asJson.noSpaces)
              case Failure(ex) =>
                complete(StatusCodes.BadGateway -> Map("error" -> ex.getMessage).asJson.noSpaces)
            }
        }
      }
    }

  /** API endpoint: POST /auth/logout revokes current session token and clears auth cookie. */
  private val logoutRoute: Route =
    path("auth" / "logout") {
      post {
        optionalCookie(config.session.cookieName) {
          case None =>
            setCookie(
              HttpCookie(
                name = config.session.cookieName,
                value = "deleted",
                httpOnly = true,
                secure = config.session.secureCookie,
                maxAge = Some(0L),
                path = Some("/")
              )
            ) {
              complete(StatusCodes.OK -> Map("status" -> "logged_out").asJson.noSpaces)
            }
          case Some(cookie) =>
            onComplete(authService.logout(cookie.value)) {
              case Success(_) =>
                setCookie(
                  HttpCookie(
                    name = config.session.cookieName,
                    value = "deleted",
                    httpOnly = true,
                    secure = config.session.secureCookie,
                    maxAge = Some(0L),
                    path = Some("/")
                  )
                ) {
                  complete(StatusCodes.OK -> Map("status" -> "logged_out").asJson.noSpaces)
                }
              case Failure(ex) =>
                complete(StatusCodes.BadGateway -> Map("error" -> ex.getMessage).asJson.noSpaces)
            }
        }
      }
    }

  val routes: Route = startRoute ~ callbackRoute ~ meRoute ~ teamsRoute ~ logoutRoute
}
