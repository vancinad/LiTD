package litd.tournament

import akka.http.scaladsl.model.StatusCodes

import scala.math.{ceil, log}

final case class CreateTournamentRequest(
    name: String,
    configuredMaxRounds: Int,
    teamId: String,
    timeControlInitialSeconds: Int,
    timeControlIncrementSeconds: Int,
    rated: Boolean
)

final case class TournamentView(
    id: String,
    name: String,
    teamId: String,
    timeControlInitialSeconds: Int,
    timeControlIncrementSeconds: Int,
    rated: Boolean,
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

final case class RefreshRoundResultsView(
    tournamentId: String,
    roundNumber: Int,
    refreshedPairings: Int
)

final case class EndRoundView(
    tournamentId: String,
    roundNumber: Int,
    completedPairings: Int,
    doubleForfeitCount: Int,
    roundStatus: String
)

final case class OverridePairingResultRequest(
    result: String,
    reason: String
)

final case class OverridePairingResultView(
    tournamentId: String,
    pairingId: String,
    roundNumber: Int,
    result: String,
    reason: String,
    appliedBy: String
)

final case class StandingsEntryView(
    rank: Int,
    lichessUserId: String,
    points: Double,
    gamesPlayed: Int,
    buchholz: Double,
    sonnebornBerger: Double
)

final case class StandingsView(
    tournamentId: String,
    roundCount: Int,
    entries: Seq[StandingsEntryView]
)

final case class CrosstableCellView(
    opponentLichessUserId: String,
    roundNumber: Int,
    color: String,
    result: String,
    score: Double
)

final case class CrosstableByeView(
    roundNumber: Int,
    scoreAwarded: Double,
    reason: String
)

final case class CrosstableRowView(
    lichessUserId: String,
    points: Double,
    gamesPlayed: Int,
    games: Seq[CrosstableCellView],
    byes: Seq[CrosstableByeView]
)

final case class CrosstableView(
    tournamentId: String,
    roundCount: Int,
    rows: Seq[CrosstableRowView]
)

final case class PublicTournamentCardView(
    id: String,
    name: String,
    status: String,
    configuredMaxRounds: Int,
    effectiveMaxRounds: Int,
    currentRoundNumber: Int,
    createdAt: String,
    updatedAt: String
)

final case class PublicTournamentListView(
    tournaments: Seq[PublicTournamentCardView]
)

final case class RoundProgressView(
    roundNumber: Int,
    roundStatus: String,
    completedPairings: Int,
    unresolvedPairings: Int,
    byeCount: Int
)

final case class TournamentHubView(
    tournament: TournamentView,
    currentRoundNumber: Int,
    currentRoundStatus: String,
    roundProgress: Option[RoundProgressView]
)

final case class MyPairingEntryView(
    pairingId: String,
    roundNumber: Int,
    opponentLichessUserId: String,
    color: String,
    challengeStatus: String,
    gameId: Option[String],
    result: Option[String],
    isOfficial: Boolean,
    lastUpdateAt: String
)

final case class MyPairingsView(
    tournamentId: String,
    lichessUserId: String,
    entries: Seq[MyPairingEntryView]
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
  val ResultWhite: String = "white"
  val ResultBlack: String = "black"
  val ResultDraw: String = "draw"
  val ResultForfeit: String = "forfeit"
  val AllowedResultValues: Set[String] = Set(ResultWhite, ResultBlack, ResultDraw, ResultForfeit)
  val MinTimeControlInitialSeconds: Int = 10
  val MaxTimeControlInitialSeconds: Int = 10800
  val MinTimeControlIncrementSeconds: Int = 0
  val MaxTimeControlIncrementSeconds: Int = 180

  def isValidConfiguredMaxRounds(value: Int): Boolean = value > 0 && value <= MaxConfiguredRounds

  def isValidTimeControlInitialSeconds(value: Int): Boolean =
    value >= MinTimeControlInitialSeconds && value <= MaxTimeControlInitialSeconds

  def isValidTimeControlIncrementSeconds(value: Int): Boolean =
    value >= MinTimeControlIncrementSeconds && value <= MaxTimeControlIncrementSeconds

  def isValidResultValue(value: String): Boolean = AllowedResultValues.contains(value)

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
