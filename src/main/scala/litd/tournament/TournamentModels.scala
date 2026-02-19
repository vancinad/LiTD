package litd.tournament

import akka.http.scaladsl.model.StatusCodes

import scala.math.{ceil, log}

final case class CreateTournamentRequest(
    name: String,
    configuredMaxRounds: Int
)

final case class TournamentView(
    id: String,
    name: String,
    status: String,
    configuredMaxRounds: Int,
    effectiveMaxRounds: Int,
    createdAt: String,
    updatedAt: String
)

final case class RegistrationView(
    tournamentId: String,
    lichessUserId: String,
    status: String,
    effectiveRound: Int,
    createdAt: String
)

final case class GrantTdByeRequest(
    lichessUserId: String,
    scoreAwarded: Double
)

final case class GenerateRoundRequest(
    tdByes: Seq[GrantTdByeRequest] = Seq.empty
)

final case class PairingView(
    whiteLichessUserId: String,
    blackLichessUserId: String
)

final case class ByeView(
    lichessUserId: String,
    scoreAwarded: Double,
    reason: String
)

final case class GenerateRoundView(
    tournamentId: String,
    roundId: String,
    roundNumber: Int,
    effectiveMaxRounds: Int,
    pairings: Seq[PairingView],
    byes: Seq[ByeView],
    auditEventType: String
)

final case class IssueChallengeView(
    tournamentId: String,
    pairingId: String,
    roundNumber: Int,
    whiteLichessUserId: String,
    blackLichessUserId: String,
    challengeId: String,
    status: String
)

sealed trait TournamentError extends Product with Serializable {
  def status: akka.http.scaladsl.model.StatusCode
  def message: String
}

object TournamentError {
  final case class BadRequest(message: String) extends TournamentError {
    override val status: akka.http.scaladsl.model.StatusCode = StatusCodes.BadRequest
  }

  final case class NotFound(message: String) extends TournamentError {
    override val status: akka.http.scaladsl.model.StatusCode = StatusCodes.NotFound
  }

  final case class Conflict(message: String) extends TournamentError {
    override val status: akka.http.scaladsl.model.StatusCode = StatusCodes.Conflict
  }

  final case class External(message: String) extends TournamentError {
    override val status: akka.http.scaladsl.model.StatusCode = StatusCodes.BadGateway
  }
}

object TournamentRules {
  val MaxConfiguredRounds: Int = 15
  val ByeReasonOdd: String = "odd"
  val ByeReasonTdGrant: String = "td_grant"

  def isValidConfiguredMaxRounds(value: Int): Boolean = value > 0 && value <= MaxConfiguredRounds

  def nextEffectiveRound(latestRoundNumber: Option[Int]): Int = latestRoundNumber.getOrElse(0) + 1

  def computeEffectiveMaxRounds(configuredMaxRounds: Int, registeredPlayerCount: Int): Int = {
    val suggestedRounds =
      if (registeredPlayerCount <= 1) 1
      else ceil(log(registeredPlayerCount.toDouble) / log(2d)).toInt

    math.min(configuredMaxRounds, math.max(1, suggestedRounds))
  }
}

object RegistrationStatus {
  val Registered: String = "registered"
  val Withdrawn: String = "withdrawn"
  val Disqualified: String = "disqualified"

  def canTransition(from: String, to: String): Boolean =
    (from, to) match {
      case (Registered, Withdrawn) => true
      case (Withdrawn, Registered) => true
      case _                       => false
    }
}
