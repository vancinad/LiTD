package litd.tournament

import litd.auth.LichessApiClient

import scala.concurrent.{ExecutionContext, Future}

final case class IssuedChallenge(
    challengeId: String,
    status: String
)

trait ChallengeGateway {
  def issueChallenge(opponentLichessUserId: String, accessToken: String): Future[Either[String, IssuedChallenge]]
  def lookupGameId(challengeId: String, accessToken: String): Future[Either[String, Option[String]]]
}

final class LichessChallengeGateway(
    lichessApiClient: LichessApiClient
)(implicit ec: ExecutionContext)
    extends ChallengeGateway {
  override def issueChallenge(
      opponentLichessUserId: String,
      accessToken: String
  ): Future[Either[String, IssuedChallenge]] =
    lichessApiClient
      .issueChallenge(opponentLichessUserId, accessToken)
      .map(response => Right(IssuedChallenge(response.challengeId, response.status)))
      .recover { case ex => Left(ex.getMessage) }

  override def lookupGameId(challengeId: String, accessToken: String): Future[Either[String, Option[String]]] =
    lichessApiClient
      .lookupChallengeGameId(challengeId, accessToken)
      .map(Right(_))
      .recover { case ex => Left(ex.getMessage) }
}

object ChallengeGateway {
  val Disabled: ChallengeGateway = new ChallengeGateway {
    override def issueChallenge(opponentLichessUserId: String, accessToken: String): Future[Either[String, IssuedChallenge]] =
      Future.successful(Left("Challenge gateway is not configured"))

    override def lookupGameId(challengeId: String, accessToken: String): Future[Either[String, Option[String]]] =
      Future.successful(Left("Challenge gateway is not configured"))
  }
}
