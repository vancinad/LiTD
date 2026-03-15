package litd.tournament

import litd.auth.LichessApiClient

import scala.concurrent.{ExecutionContext, Future}

final case class IssuedChallenge(
    challengeId: String,
    status: String
)

trait ChallengeGateway {
  def issueChallenge(
      opponentLichessUserId: String,
      accessToken: String,
      initialSeconds: Int,
      incrementSeconds: Int,
      challengerColor: String
  ): Future[Either[String, IssuedChallenge]]
  def lookupGameResults(gameIds: Seq[String]): Future[Either[String, Map[String, String]]]
}

final class LichessChallengeGateway(
    lichessApiClient: LichessApiClient
)(implicit ec: ExecutionContext)
    extends ChallengeGateway {
  override def issueChallenge(
      opponentLichessUserId: String,
      accessToken: String,
      initialSeconds: Int,
      incrementSeconds: Int,
      challengerColor: String
  ): Future[Either[String, IssuedChallenge]] =
    lichessApiClient
      .issueChallenge(opponentLichessUserId, accessToken, initialSeconds, incrementSeconds, challengerColor)
      .map(response => Right(IssuedChallenge(response.challengeId, response.status)))
      .recover { case ex => Left(ex.getMessage) }

  override def lookupGameResults(gameIds: Seq[String]): Future[Either[String, Map[String, String]]] =
    lichessApiClient
      .lookupGameResults(gameIds)
      .map(Right(_))
      .recover { case ex => Left(ex.getMessage) }
}

object ChallengeGateway {
  val Disabled: ChallengeGateway = new ChallengeGateway {
    override def issueChallenge(
        opponentLichessUserId: String,
        accessToken: String,
        initialSeconds: Int,
        incrementSeconds: Int,
        challengerColor: String
    ): Future[Either[String, IssuedChallenge]] =
      Future.successful(Left("Challenge gateway is not configured"))

    override def lookupGameResults(gameIds: Seq[String]): Future[Either[String, Map[String, String]]] =
      Future.successful(Left("Challenge gateway is not configured"))
  }
}
