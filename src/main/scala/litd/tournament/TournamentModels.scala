package litd.tournament

import akka.http.scaladsl.model.StatusCodes

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
}

object TournamentRules {
  val MaxConfiguredRounds: Int = 15

  def isValidConfiguredMaxRounds(value: Int): Boolean = value > 0 && value <= MaxConfiguredRounds

  def nextEffectiveRound(latestRoundNumber: Option[Int]): Int = latestRoundNumber.getOrElse(0) + 1
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
