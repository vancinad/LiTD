package litd.tournament

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax.EncoderOps
import io.circe.parser.decode
import litd.auth.{AuthConfig, AuthenticatedUser, AuthService}
import org.bson.types.ObjectId

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

final class TournamentRoutes(
    authConfig: AuthConfig,
    authService: AuthService,
    tournamentService: TournamentService
)(implicit ec: ExecutionContext) {

  private implicit val createTournamentRequestDecoder: Decoder[CreateTournamentRequest] =
    deriveDecoder[CreateTournamentRequest]
  private implicit val generateRoundRequestDecoder: Decoder[GenerateRoundRequest] =
    deriveDecoder[GenerateRoundRequest]
  private implicit val grantTdByeRequestDecoder: Decoder[GrantTdByeRequest] =
    deriveDecoder[GrantTdByeRequest]
  private implicit val tournamentViewEncoder: Encoder[TournamentView] = deriveEncoder[TournamentView]
  private implicit val registrationViewEncoder: Encoder[RegistrationView] = deriveEncoder[RegistrationView]
  private implicit val pairingViewEncoder: Encoder[PairingView] = deriveEncoder[PairingView]
  private implicit val byeViewEncoder: Encoder[ByeView] = deriveEncoder[ByeView]
  private implicit val generateRoundViewEncoder: Encoder[GenerateRoundView] = deriveEncoder[GenerateRoundView]

  private def withAuthenticatedUser(inner: AuthenticatedUser => Route): Route =
    optionalCookie(authConfig.session.cookieName) {
      case None =>
        complete(StatusCodes.Unauthorized -> Map("error" -> "Missing auth session cookie").asJson.noSpaces)
      case Some(cookie) =>
        onComplete(authService.authenticate(cookie.value)) {
          case Success(Right(user)) => inner(user)
          case Success(Left(error)) => complete(error.status -> Map("error" -> error.message).asJson.noSpaces)
          case Failure(ex)          => complete(StatusCodes.BadGateway -> Map("error" -> ex.getMessage).asJson.noSpaces)
        }
    }

  private def parseTournamentId(id: String): Either[TournamentError, ObjectId] =
    if (ObjectId.isValid(id)) Right(new ObjectId(id))
    else Left(TournamentError.BadRequest(s"Invalid tournament id '$id'"))

  private def completeDomainError(error: TournamentError): Route =
    complete(error.status -> Map("error" -> error.message).asJson.noSpaces)

  private def decodeBody[T: Decoder](rawBody: String): Either[TournamentError, T] =
    decode[T](rawBody).left.map(err => TournamentError.BadRequest(s"Invalid request body: ${err.getMessage}"))

  /** API endpoint: POST /tournaments creates a tournament for authenticated team member sessions. */
  private val createTournamentRoute: Route =
    path("tournaments") {
      post {
        withAuthenticatedUser { _ =>
          entity(as[String]) { rawBody =>
            decodeBody[CreateTournamentRequest](rawBody) match {
              case Left(error) => completeDomainError(error)
              case Right(request) =>
                onComplete(tournamentService.createTournament(request)) {
                  case Success(Right(created)) => complete(StatusCodes.Created -> created.asJson.noSpaces)
                  case Success(Left(error))    => completeDomainError(error)
                  case Failure(ex)             => complete(StatusCodes.InternalServerError -> Map("error" -> ex.getMessage).asJson.noSpaces)
                }
            }
          }
        }
      }
    }

  /** API endpoint: POST /tournaments/{tournamentId}/registrations registers authenticated player with late-registration effectiveRound logic. */
  private val registerRoute: Route =
    path("tournaments" / Segment / "registrations") { tournamentIdRaw =>
      post {
        withAuthenticatedUser { user =>
          parseTournamentId(tournamentIdRaw) match {
            case Left(error) => completeDomainError(error)
            case Right(tournamentId) =>
              onComplete(tournamentService.registerPlayer(tournamentId, user.lichessUserId)) {
                case Success(Right(registered)) => complete(StatusCodes.Created -> registered.asJson.noSpaces)
                case Success(Left(error))       => completeDomainError(error)
                case Failure(ex)                => complete(StatusCodes.InternalServerError -> Map("error" -> ex.getMessage).asJson.noSpaces)
              }
          }
        }
      }
    }

  /** API endpoint: POST /tournaments/{tournamentId}/registrations/withdraw withdraws authenticated player effective next round. */
  private val withdrawRoute: Route =
    path("tournaments" / Segment / "registrations" / "withdraw") { tournamentIdRaw =>
      post {
        withAuthenticatedUser { user =>
          parseTournamentId(tournamentIdRaw) match {
            case Left(error) => completeDomainError(error)
            case Right(tournamentId) =>
              onComplete(tournamentService.withdrawPlayer(tournamentId, user.lichessUserId)) {
                case Success(Right(updated)) => complete(StatusCodes.OK -> updated.asJson.noSpaces)
                case Success(Left(error))    => completeDomainError(error)
                case Failure(ex)             => complete(StatusCodes.InternalServerError -> Map("error" -> ex.getMessage).asJson.noSpaces)
              }
          }
        }
      }
    }

  /** API endpoint: POST /tournaments/{tournamentId}/registrations/reactivate reactivates authenticated player effective next round. */
  private val reactivateRoute: Route =
    path("tournaments" / Segment / "registrations" / "reactivate") { tournamentIdRaw =>
      post {
        withAuthenticatedUser { user =>
          parseTournamentId(tournamentIdRaw) match {
            case Left(error) => completeDomainError(error)
            case Right(tournamentId) =>
              onComplete(tournamentService.reactivatePlayer(tournamentId, user.lichessUserId)) {
                case Success(Right(updated)) => complete(StatusCodes.OK -> updated.asJson.noSpaces)
                case Success(Left(error))    => completeDomainError(error)
                case Failure(ex)             => complete(StatusCodes.InternalServerError -> Map("error" -> ex.getMessage).asJson.noSpaces)
              }
          }
        }
      }
    }

  /** API endpoint: POST /tournaments/{tournamentId}/rounds/generate generates next round transactionally with pairings/byes and audit event. */
  private val generateRoundRoute: Route =
    path("tournaments" / Segment / "rounds" / "generate") { tournamentIdRaw =>
      post {
        withAuthenticatedUser { _ =>
          parseTournamentId(tournamentIdRaw) match {
            case Left(error) => completeDomainError(error)
            case Right(tournamentId) =>
              entity(as[String]) { rawBody =>
                decodeBody[GenerateRoundRequest](rawBody) match {
                  case Left(error) => completeDomainError(error)
                  case Right(request) =>
                    onComplete(tournamentService.generateNextRound(tournamentId, request)) {
                      case Success(Right(result)) => complete(StatusCodes.Created -> result.asJson.noSpaces)
                      case Success(Left(error))   => completeDomainError(error)
                      case Failure(ex)            => complete(StatusCodes.InternalServerError -> Map("error" -> ex.getMessage).asJson.noSpaces)
                    }
                }
              }
          }
        }
      }
    }

  /** API endpoint: POST /tournaments/{tournamentId}/rounds/{roundNumber}/byes/td grants a TD bye for an existing round if user has no pairing/bye yet. */
  private val grantTdByeRoute: Route =
    path("tournaments" / Segment / "rounds" / IntNumber / "byes" / "td") { (tournamentIdRaw, roundNumber) =>
      post {
        withAuthenticatedUser { _ =>
          parseTournamentId(tournamentIdRaw) match {
            case Left(error) => completeDomainError(error)
            case Right(tournamentId) =>
              entity(as[String]) { rawBody =>
                decodeBody[GrantTdByeRequest](rawBody) match {
                  case Left(error) => completeDomainError(error)
                  case Right(request) =>
                    onComplete(tournamentService.grantTdBye(tournamentId, roundNumber, request)) {
                      case Success(Right(result)) => complete(StatusCodes.Created -> result.asJson.noSpaces)
                      case Success(Left(error))   => completeDomainError(error)
                      case Failure(ex)            => complete(StatusCodes.InternalServerError -> Map("error" -> ex.getMessage).asJson.noSpaces)
                    }
                }
              }
          }
        }
      }
    }

  val routes: Route =
    createTournamentRoute ~
      registerRoute ~
      withdrawRoute ~
      reactivateRoute ~
      generateRoundRoute ~
      grantTdByeRoute
}
