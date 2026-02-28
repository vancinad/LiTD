package litd.tournament

import litd.domain.{
  AuditEventDocument,
  ByeDocument,
  OverrideDocument,
  PairingDocument,
  PlayerTournamentStateDocument,
  RegistrationDocument,
  RoundDocument,
  TiebreaksDocument,
  TournamentDocument
}
import litd.auth.AuthenticatedUser
import litd.mongo.repository.{
  AuditEventRepository,
  ByeRepository,
  OverrideRepository,
  PairingRepository,
  PlayerTournamentStateRepository,
  RegistrationRepository,
  RoundRepository,
  TournamentRepository
}
import litd.tournament.TournamentError.{BadRequest, Conflict, External, NotFound}
import com.mongodb.{MongoBulkWriteException, MongoWriteException}
import org.bson.Document
import org.bson.types.ObjectId
import org.mongodb.scala._

import java.util.Date
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

import litd.tournament.TournamentService.{PlannedBye, RoundPlan, StateAccumulator}

final class TournamentService(
    tournamentRepository: TournamentRepository,
    registrationRepository: RegistrationRepository,
    roundRepository: RoundRepository,
    pairingRepository: PairingRepository,
    byeRepository: ByeRepository,
    playerTournamentStateRepository: PlayerTournamentStateRepository,
    overrideRepository: OverrideRepository,
    auditEventRepository: AuditEventRepository,
    mongoClient: MongoClient,
    challengeGateway: ChallengeGateway = ChallengeGateway.Disabled
)(implicit ec: ExecutionContext) {

  /** Creates a tournament with spec-capped configured rounds and draft status. */
  def createTournament(request: CreateTournamentRequest): Future[Either[TournamentError, TournamentView]] = {
    val trimmedName = request.name.trim
    val trimmedTeamId = request.teamId.trim

    if (trimmedName.isEmpty) {
      Future.successful(Left(BadRequest("Tournament name must not be empty")))
    } else if (trimmedTeamId.isEmpty) {
      Future.successful(Left(BadRequest("teamId must not be empty")))
    } else if (!TournamentRules.isValidConfiguredMaxRounds(request.configuredMaxRounds)) {
      Future.successful(
        Left(BadRequest(s"configuredMaxRounds must be between 1 and ${TournamentRules.MaxConfiguredRounds}"))
      )
    } else if (!TournamentRules.isValidTimeControlInitialSeconds(request.timeControlInitialSeconds)) {
      Future.successful(
        Left(
          BadRequest(
            s"timeControlInitialSeconds must be between ${TournamentRules.MinTimeControlInitialSeconds} and ${TournamentRules.MaxTimeControlInitialSeconds}"
          )
        )
      )
    } else if (!TournamentRules.isValidTimeControlIncrementSeconds(request.timeControlIncrementSeconds)) {
      Future.successful(
        Left(
          BadRequest(
            s"timeControlIncrementSeconds must be between ${TournamentRules.MinTimeControlIncrementSeconds} and ${TournamentRules.MaxTimeControlIncrementSeconds}"
          )
        )
      )
    } else {
      val now = new Date()
      val document = TournamentDocument(
        _id = Some(new ObjectId()),
        name = trimmedName,
        teamId = trimmedTeamId,
        timeControlInitialSeconds = request.timeControlInitialSeconds,
        timeControlIncrementSeconds = request.timeControlIncrementSeconds,
        rated = request.rated,
        status = "draft",
        configuredMaxRounds = request.configuredMaxRounds,
        effectiveMaxRounds = request.configuredMaxRounds,
        createdAt = now,
        updatedAt = now
      )

      tournamentRepository.insert(document).map { inserted =>
        Right(
          toTournamentView(inserted)
        )
      }
    }
  }

  /** Registers a player; late registrations become effective starting the next round boundary. */
  def registerPlayer(tournamentId: ObjectId, lichessUserId: String): Future[Either[TournamentError, RegistrationView]] =
    for {
      tournament <- tournamentRepository.findByIdOption(tournamentId)
      result <- tournament match {
        case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
        case Some(_) => upsertForRegistration(tournamentId, lichessUserId)
      }
    } yield result

  def withdrawPlayer(tournamentId: ObjectId, lichessUserId: String): Future[Either[TournamentError, RegistrationView]] =
    updateRegistrationStatus(tournamentId, lichessUserId, targetStatus = RegistrationStatus.Withdrawn)

  /** Reactivation follows the same next-round effective semantics as withdrawals. */
  def reactivatePlayer(tournamentId: ObjectId, lichessUserId: String): Future[Either[TournamentError, RegistrationView]] =
    updateRegistrationStatus(tournamentId, lichessUserId, targetStatus = RegistrationStatus.Registered)

  /** API command: generate next Swiss round transactionally with pairings/byes and audit event. */
  def generateNextRound(
      tournamentId: ObjectId,
      request: GenerateRoundRequest
  ): Future[Either[TournamentError, GenerateRoundView]] =
    inTransactionEither { session =>
      for {
        tournamentOpt <- tournamentRepository.findByIdOption(session, tournamentId)
        result <- tournamentOpt match {
          case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
          case Some(tournament) => generateRoundInTransaction(session, tournament, request)
        }
      } yield result
    }

  /** API command: manually grant a TD bye for an existing round when player has no pairing yet. */
  def grantTdBye(
      tournamentId: ObjectId,
      roundNumber: Int,
      request: GrantTdByeRequest
  ): Future[Either[TournamentError, ByeView]] = {
    val userId = request.lichessUserId.trim

    if (roundNumber <= 0) {
      Future.successful(Left(BadRequest("roundNumber must be positive")))
    } else if (userId.isEmpty) {
      Future.successful(Left(BadRequest("lichessUserId must not be empty")))
    } else if (request.scoreAwarded < 0d || request.scoreAwarded > 1d) {
      Future.successful(Left(BadRequest("scoreAwarded must be between 0.0 and 1.0")))
    } else {
      inTransactionEither { session =>
        for {
          tournamentOpt <- tournamentRepository.findByIdOption(session, tournamentId)
          result <- tournamentOpt match {
            case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
            case Some(_) => grantTdByeInTransaction(session, tournamentId, roundNumber, userId, request.scoreAwarded)
          }
        } yield result
      }
    }
  }

  /** API command: issue an external Lichess challenge for a pairing and persist challengeId on the pairing. */
  def issueChallenge(
      tournamentId: ObjectId,
      pairingId: ObjectId,
      user: AuthenticatedUser
  ): Future[Either[TournamentError, IssueChallengeView]] =
    for {
      tournamentOpt <- tournamentRepository.findByIdOption(tournamentId)
      result <- tournamentOpt match {
        case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
        case Some(tournament) =>
          pairingRepository.findByTournamentAndId(tournamentId, pairingId).flatMap {
            case None => Future.successful(Left(NotFound(s"Pairing '${pairingId.toHexString}' not found in tournament")))
            case Some(pairing) if pairing.whiteLichessUserId != user.lichessUserId =>
              Future.successful(Left(Conflict("Only the white player can issue the challenge for this pairing")))
            case Some(pairing) if pairing.gameId.nonEmpty =>
              Future.successful(Left(Conflict("Pairing already has an associated gameId")))
            case Some(pairing) if pairing.challengeId.nonEmpty =>
              // Milestone 8 hardening: replaying the same request returns the persisted challenge.
              Future.successful(
                Right(
                  toIssueChallengeView(
                    tournamentId = tournamentId,
                    pairing = pairing,
                    challengeId = pairing.challengeId.get,
                    status = "already_issued"
                  )
                )
              )
            case Some(pairing) =>
              challengeGateway
                .issueChallenge(
                  opponentLichessUserId = pairing.blackLichessUserId,
                  accessToken = user.accessToken,
                  initialSeconds = tournament.timeControlInitialSeconds,
                  incrementSeconds = tournament.timeControlIncrementSeconds
                )
                .flatMap {
                  case Left(errorMessage) => Future.successful(Left(External(s"Challenge issuance failed: $errorMessage")))
                  case Right(issued) =>
                    persistIssuedChallenge(tournamentId, pairing, issued)
                }
          }
      }
    } yield result

  /** API command: refresh unresolved pairing results for an active round using Lichess game exports. */
  def refreshRoundResults(
      tournamentId: ObjectId,
      roundNumber: Int,
      user: AuthenticatedUser
  ): Future[Either[TournamentError, RefreshRoundResultsView]] =
    if (roundNumber <= 0) {
      Future.successful(Left(BadRequest("roundNumber must be positive")))
    } else {
      for {
        tournamentOpt <- tournamentRepository.findByIdOption(tournamentId)
        result <- tournamentOpt match {
          case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
          case Some(_) =>
            refreshRoundResultsForTournament(tournamentId, roundNumber, user)
        }
      } yield result
    }

  /** API command: end an active round transactionally by applying double-forfeits and finalizing official results. */
  def endRound(tournamentId: ObjectId, roundNumber: Int): Future[Either[TournamentError, EndRoundView]] =
    if (roundNumber <= 0) {
      Future.successful(Left(BadRequest("roundNumber must be positive")))
    } else {
      inTransactionEither { session =>
        for {
          tournamentOpt <- tournamentRepository.findByIdOption(session, tournamentId)
          result <- tournamentOpt match {
            case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
            case Some(tournament) =>
              endRoundInTransaction(session, tournament, roundNumber)
          }
        } yield result
      }
    }

  /** API command: override a pairing result transactionally and store immutable override history. */
  def overridePairingResult(
      tournamentId: ObjectId,
      pairingId: ObjectId,
      request: OverridePairingResultRequest,
      user: AuthenticatedUser
  ): Future[Either[TournamentError, OverridePairingResultView]] = {
    val normalizedResult = request.result.trim.toLowerCase
    val normalizedReason = request.reason.trim

    if (!TournamentRules.isValidResultValue(normalizedResult)) {
      Future.successful(Left(BadRequest("result must be one of: white, black, draw, forfeit")))
    } else if (normalizedReason.isEmpty) {
      Future.successful(Left(BadRequest("reason must not be empty")))
    } else {
      inTransactionEither { session =>
        for {
          tournamentOpt <- tournamentRepository.findByIdOption(session, tournamentId)
          result <- tournamentOpt match {
            case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
            case Some(_) =>
              overridePairingInTransaction(
                session = session,
                tournamentId = tournamentId,
                pairingId = pairingId,
                result = normalizedResult,
                reason = normalizedReason,
                appliedBy = user.lichessUserId
              )
          }
        } yield result
      }
    }
  }

  /** API query: compute tournament standings read model from player state plus official pairings/byes. */
  def getStandings(tournamentId: ObjectId): Future[Either[TournamentError, StandingsView]] =
    for {
      tournamentOpt <- tournamentRepository.findByIdOption(tournamentId)
      result <- tournamentOpt match {
        case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
        case Some(_) =>
          for {
            states <- playerTournamentStateRepository.listByTournament(tournamentId)
            pairings <- pairingRepository.listByTournament(tournamentId)
            latestRoundNumber <- roundRepository.latestRoundNumberForTournament(tournamentId)
          } yield {
            val officialPairings = pairings.filter(pairing => pairing.isOfficial && pairing.result.nonEmpty)
            // Milestone 7 read model: standings are sorted by points then Swiss tiebreaks.
            val tiebreaksByUser = computeTiebreaks(states, officialPairings)
            val ranked = rankStandingsEntries(states, tiebreaksByUser)
            Right(
              StandingsView(
                tournamentId = tournamentId.toHexString,
                roundCount = latestRoundNumber.getOrElse(0),
                entries = ranked
              )
            )
          }
      }
    } yield result

  /** API query: list public tournaments suitable for landing page discovery. */
  def listPublicTournaments(limit: Int = 30): Future[PublicTournamentListView] =
    tournamentRepository.listByStatuses(statuses = Seq("draft", "active", "completed"), limit = limit).flatMap {
      tournaments => Future.sequence(tournaments.map(toPublicTournamentCard)).map(items => PublicTournamentListView(items))
    }

  /** API query: fetch a tournament by id for authorization and hub composition. */
  def getTournament(tournamentId: ObjectId): Future[Either[TournamentError, TournamentView]] =
    tournamentRepository.findByIdOption(tournamentId).map {
      case None => Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found"))
      case Some(tournament) => Right(toTournamentView(tournament))
    }

  /** API query: list tournaments visible to a user based on their team memberships. */
  def listTournamentsByTeams(teamIds: Seq[String], limit: Int = 30): Future[PublicTournamentListView] =
    tournamentRepository
      .listByTeamIdsAndStatuses(teamIds.distinct, statuses = Seq("draft", "active", "completed"), limit = limit)
      .flatMap(tournaments => Future.sequence(tournaments.map(toPublicTournamentCard)).map(items => PublicTournamentListView(items)))

  /** API query: list tournaments where a user has a registration record. */
  def listMyTournaments(lichessUserId: String, limit: Int = 30): Future[PublicTournamentListView] =
    registrationRepository.listByUser(lichessUserId, limit = limit).flatMap { registrations =>
      val tournamentIds = registrations.map(_.tournamentId).distinct
      Future
        .sequence(
          tournamentIds.map { tournamentId =>
            tournamentRepository.findByIdOption(tournamentId).flatMap {
              case Some(tournament) => toPublicTournamentCard(tournament).map(Some(_))
              case None => Future.successful(None)
            }
          }
        )
        .map(cards => PublicTournamentListView(cards.flatten))
    }

  /** API query: return tournament hub summary with current round progress counters. */
  def getTournamentHub(tournamentId: ObjectId): Future[Either[TournamentError, TournamentHubView]] =
    for {
      tournamentOpt <- tournamentRepository.findByIdOption(tournamentId)
      result <- tournamentOpt match {
        case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
        case Some(tournament) =>
          roundRepository.latestRoundForTournament(tournamentId).flatMap {
            case None =>
              Future.successful(
                Right(
                  TournamentHubView(
                    tournament = toTournamentView(tournament),
                    currentRoundNumber = 0,
                    currentRoundStatus = "pending",
                    roundProgress = None
                  )
                )
              )
            case Some(round) =>
              for {
                pairings <- pairingRepository.listByRound(round._id.get)
                byes <- byeRepository.listByRound(round._id.get)
              } yield {
                val completed = pairings.count(pairing => pairing.isOfficial && pairing.result.nonEmpty)
                val unresolved = pairings.size - completed
                Right(
                  TournamentHubView(
                    tournament = toTournamentView(tournament),
                    currentRoundNumber = round.roundNumber,
                    currentRoundStatus = round.status,
                    roundProgress = Some(
                      RoundProgressView(
                        roundNumber = round.roundNumber,
                        roundStatus = round.status,
                        completedPairings = completed,
                        unresolvedPairings = unresolved,
                        byeCount = byes.size
                      )
                    )
                  )
                )
              }
          }
      }
    } yield result

  /** API query: list authenticated user's pairings in a tournament for challenge and status tracking. */
  def getMyPairings(tournamentId: ObjectId, lichessUserId: String): Future[Either[TournamentError, MyPairingsView]] =
    for {
      tournamentOpt <- tournamentRepository.findByIdOption(tournamentId)
      result <- tournamentOpt match {
        case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
        case Some(_) =>
          pairingRepository.listByTournamentAndUser(tournamentId, lichessUserId).map { pairings =>
            Right(
              MyPairingsView(
                tournamentId = tournamentId.toHexString,
                lichessUserId = lichessUserId,
                entries = pairings.map(pairing => toMyPairingEntryView(pairing, lichessUserId))
              )
            )
          }
      }
    } yield result

  /** API query: compute tournament crosstable read model from official pairings and byes. */
  def getCrosstable(tournamentId: ObjectId): Future[Either[TournamentError, CrosstableView]] =
    for {
      tournamentOpt <- tournamentRepository.findByIdOption(tournamentId)
      result <- tournamentOpt match {
        case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
        case Some(_) =>
          for {
            states <- playerTournamentStateRepository.listByTournament(tournamentId)
            pairings <- pairingRepository.listByTournament(tournamentId)
            byes <- byeRepository.listByTournament(tournamentId)
            latestRoundNumber <- roundRepository.latestRoundNumberForTournament(tournamentId)
          } yield {
            val officialPairings = pairings.filter(pairing => pairing.isOfficial && pairing.result.nonEmpty)
            val tiebreaksByUser = computeTiebreaks(states, officialPairings)
            val sortedUserIds = rankStandingsEntries(states, tiebreaksByUser).map(_.lichessUserId)
            // Build per-player game cells from official results only; unfinished games stay out of crosstable.
            val pairingsByUser = officialPairings.foldLeft(Map.empty[String, Vector[CrosstableCellView]]) {
              (acc, pairing) =>
                val result = pairing.result.get
                val whiteScore = resultScoreForWhite(result)
                val blackScore = resultScoreForBlack(result)
                val whiteCell = CrosstableCellView(
                  opponentLichessUserId = pairing.blackLichessUserId,
                  roundNumber = pairing.roundNumber,
                  color = "white",
                  result = result,
                  score = whiteScore
                )
                val blackCell = CrosstableCellView(
                  opponentLichessUserId = pairing.whiteLichessUserId,
                  roundNumber = pairing.roundNumber,
                  color = "black",
                  result = result,
                  score = blackScore
                )
                acc
                  .updated(pairing.whiteLichessUserId, acc.getOrElse(pairing.whiteLichessUserId, Vector.empty) :+ whiteCell)
                  .updated(pairing.blackLichessUserId, acc.getOrElse(pairing.blackLichessUserId, Vector.empty) :+ blackCell)
            }
            val byesByUser = byes.foldLeft(Map.empty[String, Vector[CrosstableByeView]]) { (acc, bye) =>
              val view = CrosstableByeView(
                roundNumber = bye.roundNumber,
                scoreAwarded = bye.scoreAwarded,
                reason = bye.reason
              )
              acc.updated(bye.lichessUserId, acc.getOrElse(bye.lichessUserId, Vector.empty) :+ view)
            }
            val stateByUser = states.map(state => state.lichessUserId -> state).toMap
            val rows = sortedUserIds.map { userId =>
              val state = stateByUser.getOrElse(
                userId,
                PlayerTournamentStateDocument(
                  _id = None,
                  tournamentId = tournamentId,
                  lichessUserId = userId,
                  points = 0d,
                  gamesPlayed = 0,
                  opponents = Seq.empty,
                  colors = Seq.empty,
                  resultsByRound = Map.empty,
                  tiebreaks = TiebreaksDocument(0d, 0d),
                  updatedAt = new Date(0L)
                )
              )
              CrosstableRowView(
                lichessUserId = userId,
                points = state.points,
                gamesPlayed = state.gamesPlayed,
                games = pairingsByUser.getOrElse(userId, Vector.empty).sortBy(_.roundNumber),
                byes = byesByUser.getOrElse(userId, Vector.empty).sortBy(_.roundNumber)
              )
            }
            Right(
              CrosstableView(
                tournamentId = tournamentId.toHexString,
                roundCount = latestRoundNumber.getOrElse(0),
                rows = rows
              )
            )
          }
      }
    } yield result

  private def generateRoundInTransaction(
      session: ClientSession,
      tournament: TournamentDocument,
      request: GenerateRoundRequest
  ): Future[Either[TournamentError, GenerateRoundView]] =
    for {
      latestRound <- roundRepository.latestRoundForTournament(session, tournament._id.get)
      validation <- validateRoundTransition(tournament, latestRound)
      result <- validation match {
        case Left(error) => Future.successful(Left(error))
        case Right(roundNumber) =>
          for {
            eligibleRegistrations <- registrationRepository.listEligibleForRound(session, tournament._id.get, roundNumber)
            buildResult <- buildRoundPlan(
              session = session,
              tournament = tournament,
              roundNumber = roundNumber,
              eligibleRegistrations = eligibleRegistrations,
              tdByes = request.tdByes
            )
            persisted <- buildResult match {
              case Left(error) => Future.successful(Left(error))
              case Right(plan) =>
                persistRoundPlan(
                  session = session,
                  tournament = tournament,
                  roundNumber = roundNumber,
                  eligibleRegistrations = eligibleRegistrations,
                  plan = plan
                )
            }
          } yield persisted
      }
    } yield result

  private def validateRoundTransition(
      tournament: TournamentDocument,
      latestRound: Option[RoundDocument]
  ): Future[Either[TournamentError, Int]] = {
    val nextRound = latestRound.map(_.roundNumber + 1).getOrElse(1)

    if (latestRound.exists(_.status != "completed")) {
      Future.successful(Left(Conflict("Cannot generate next round while previous round is not completed")))
    } else if (nextRound > tournament.effectiveMaxRounds) {
      Future.successful(Left(Conflict("Tournament already reached effectiveMaxRounds")))
    } else {
      Future.successful(Right(nextRound))
    }
  }

  private def buildRoundPlan(
      session: ClientSession,
      tournament: TournamentDocument,
      roundNumber: Int,
      eligibleRegistrations: Seq[RegistrationDocument],
      tdByes: Seq[GrantTdByeRequest]
  ): Future[Either[TournamentError, RoundPlan]] = {
    val normalizedTdByes = tdByes.map(b => b.copy(lichessUserId = b.lichessUserId.trim))
    val tdByeUsers = normalizedTdByes.map(_.lichessUserId)

    if (eligibleRegistrations.size < 2) {
      Future.successful(Left(Conflict("At least 2 eligible players are required to generate a round")))
    } else if (tdByeUsers.exists(_.isEmpty)) {
      Future.successful(Left(BadRequest("tdByes contains empty lichessUserId")))
    } else if (tdByeUsers.distinct.size != tdByeUsers.size) {
      Future.successful(Left(BadRequest("tdByes contains duplicate lichessUserId values")))
    } else if (normalizedTdByes.exists(bye => bye.scoreAwarded < 0d || bye.scoreAwarded > 1d)) {
      Future.successful(Left(BadRequest("All tdByes scoreAwarded values must be between 0.0 and 1.0")))
    } else {
      val eligibleUsers = eligibleRegistrations.map(_.lichessUserId).sorted
      val ineligibleTdByes = tdByeUsers.filterNot(eligibleUsers.toSet)

      if (ineligibleTdByes.nonEmpty) {
        Future.successful(Left(Conflict(s"TD byes include ineligible players: ${ineligibleTdByes.mkString(", ")}")))
      } else {
        for {
          historicalPairings <- pairingRepository.listByTournament(session, tournament._id.get)
          historicalByes <- byeRepository.listByTournament(session, tournament._id.get)
        } yield {
          val byeCounts = historicalByes.groupMapReduce(_.lichessUserId)(_ => 1)(_ + _)
          val tdByeDocs = normalizedTdByes.map { tdBye =>
            PlannedBye(tdBye.lichessUserId, tdBye.scoreAwarded, TournamentRules.ByeReasonTdGrant)
          }

          val pairingPool = eligibleUsers.diff(tdByeUsers)
          val oddBye = if (pairingPool.size % 2 == 1) {
            val selected = pairingPool.minBy(user => (byeCounts.getOrElse(user, 0), user))
            Seq(PlannedBye(selected, 1.0d, TournamentRules.ByeReasonOdd))
          } else {
            Seq.empty
          }
          val finalPairingPool = pairingPool.diff(oddBye.map(_.lichessUserId))
          val previousOpponents = buildOpponentMap(historicalPairings)
          val plannedPairings = buildPairings(finalPairingPool, previousOpponents)
          val allByes = (tdByeDocs ++ oddBye).sortBy(_.lichessUserId)

          val computedEffectiveMax =
            if (roundNumber == 1)
              TournamentRules.computeEffectiveMaxRounds(tournament.configuredMaxRounds, eligibleUsers.size)
            else tournament.effectiveMaxRounds

          if (roundNumber > computedEffectiveMax) {
            Left(Conflict("Round exceeds effectiveMaxRounds based on current registration size"))
          } else {
            Right(RoundPlan(plannedPairings, allByes, computedEffectiveMax))
          }
        }
      }
    }
  }

  private def persistRoundPlan(
      session: ClientSession,
      tournament: TournamentDocument,
      roundNumber: Int,
      eligibleRegistrations: Seq[RegistrationDocument],
      plan: RoundPlan
  ): Future[Either[TournamentError, GenerateRoundView]] = {
    val now = new Date()
    val tournamentId = tournament._id.get
    val maybeUpdatedTournament =
      if (roundNumber == 1 || tournament.status == "draft") {
        Some(
          tournament.copy(
            status = "active",
            effectiveMaxRounds = plan.computedEffectiveMaxRounds,
            updatedAt = now
          )
        )
      } else {
        None
      }

    val roundId = new ObjectId()
    val roundDocument = RoundDocument(
      _id = Some(roundId),
      tournamentId = tournamentId,
      roundNumber = roundNumber,
      status = "active",
      createdAt = now,
      completedAt = None
    )
    val pairingDocuments = plan.pairings.map { pairing =>
      PairingDocument(
        _id = Some(new ObjectId()),
        tournamentId = tournamentId,
        roundId = roundId,
        roundNumber = roundNumber,
        gameId = "",
        whiteLichessUserId = pairing.whiteLichessUserId,
        blackLichessUserId = pairing.blackLichessUserId,
        playerIds = Seq(pairing.whiteLichessUserId, pairing.blackLichessUserId).sorted,
        result = None,
        isOfficial = false,
        createdAt = now
      )
    }
    val byeDocuments = plan.byes.map { bye =>
      ByeDocument(
        _id = Some(new ObjectId()),
        tournamentId = tournamentId,
        roundId = roundId,
        roundNumber = roundNumber,
        lichessUserId = bye.lichessUserId,
        scoreAwarded = bye.scoreAwarded,
        reason = bye.reason,
        createdAt = now
      )
    }
    val firstRoundStateDocs =
      if (roundNumber == 1) {
        eligibleRegistrations.map { registration =>
          PlayerTournamentStateDocument(
            _id = Some(new ObjectId()),
            tournamentId = tournamentId,
            lichessUserId = registration.lichessUserId,
            points = 0d,
            gamesPlayed = 0,
            opponents = Seq.empty,
            colors = Seq.empty,
            resultsByRound = Map.empty,
            tiebreaks = TiebreaksDocument(buchholz = 0d, sonnebornBerger = 0d),
            updatedAt = now
          )
        }
      } else {
        Seq.empty
      }

    val auditEventType = "round_generated"
    val payload = new Document()
      .append("roundNumber", roundNumber)
      .append("pairingCount", pairingDocuments.size)
      .append("byeCount", byeDocuments.size)
      .append("effectiveMaxRounds", plan.computedEffectiveMaxRounds)

    for {
      _ <- maybeUpdatedTournament
        .map(updated => tournamentRepository.replaceById(session, tournamentId, updated))
        .getOrElse(Future.successful(true))
      _ <- roundRepository.insert(session, roundDocument)
      _ <- pairingRepository.insertMany(session, pairingDocuments)
      _ <- byeRepository.insertMany(session, byeDocuments)
      _ <- playerTournamentStateRepository.insertMany(session, firstRoundStateDocs)
      _ <- auditEventRepository.insert(
        session,
        AuditEventDocument(
          _id = Some(new ObjectId()),
          tournamentId = tournamentId,
          `type` = auditEventType,
          payload = payload,
          createdAt = now
        )
      )
    } yield Right(
      GenerateRoundView(
        tournamentId = tournamentId.toHexString,
        roundId = roundId.toHexString,
        roundNumber = roundNumber,
        effectiveMaxRounds = plan.computedEffectiveMaxRounds,
        pairings = plan.pairings,
        byes = plan.byes.map(bye => ByeView(bye.lichessUserId, bye.scoreAwarded, bye.reason)),
        auditEventType = auditEventType
      )
    )
  }

  private def grantTdByeInTransaction(
      session: ClientSession,
      tournamentId: ObjectId,
      roundNumber: Int,
      lichessUserId: String,
      scoreAwarded: Double
  ): Future[Either[TournamentError, ByeView]] =
    for {
      roundOpt <- roundRepository.findByTournamentAndRoundNumber(session, tournamentId, roundNumber)
      result <- roundOpt match {
        case None => Future.successful(Left(NotFound(s"Round $roundNumber not found")))
        case Some(round) =>
          for {
            registrationOpt <- registrationRepository.findByTournamentAndUser(session, tournamentId, lichessUserId)
            domainResult <- registrationOpt match {
              case None => Future.successful(Left(Conflict(s"User '$lichessUserId' is not registered in this tournament")))
              case Some(registration)
                  if registration.status != RegistrationStatus.Registered || registration.effectiveRound > roundNumber =>
                Future.successful(Left(Conflict(s"User '$lichessUserId' is not eligible for round $roundNumber")))
              case Some(_) =>
                for {
                  existingPairing <- pairingRepository.findByRoundAndUser(session, round._id.get, lichessUserId)
                  existingBye <- byeRepository.findByRoundAndUser(session, round._id.get, lichessUserId)
                  inserted <- (existingPairing, existingBye) match {
                    case (Some(_), _) =>
                      Future.successful(Left(Conflict(s"User '$lichessUserId' already has a pairing in round $roundNumber")))
                    case (_, Some(_)) =>
                      Future.successful(Left(Conflict(s"User '$lichessUserId' already has a bye in round $roundNumber")))
                    case (None, None) =>
                      val now = new Date()
                      val bye = ByeDocument(
                        _id = Some(new ObjectId()),
                        tournamentId = tournamentId,
                        roundId = round._id.get,
                        roundNumber = roundNumber,
                        lichessUserId = lichessUserId,
                        scoreAwarded = scoreAwarded,
                        reason = TournamentRules.ByeReasonTdGrant,
                        createdAt = now
                      )
                      val payload = new Document()
                        .append("roundNumber", roundNumber)
                        .append("lichessUserId", lichessUserId)
                        .append("scoreAwarded", scoreAwarded)

                      for {
                        _ <- byeRepository.insert(session, bye)
                        _ <- auditEventRepository.insert(
                          session,
                          AuditEventDocument(
                            _id = Some(new ObjectId()),
                            tournamentId = tournamentId,
                            `type` = "td_bye_granted",
                            payload = payload,
                            createdAt = now
                          )
                        )
                      } yield Right(ByeView(lichessUserId, scoreAwarded, TournamentRules.ByeReasonTdGrant))
                  }
                } yield inserted
            }
          } yield domainResult
      }
    } yield result

  private def persistIssuedChallenge(
      tournamentId: ObjectId,
      pairing: PairingDocument,
      issuedChallenge: IssuedChallenge
  ): Future[Either[TournamentError, IssueChallengeView]] =
    inTransactionEither { session =>
      val now = new Date()
      val pairingId = pairing._id.get
      val payload = new Document()
        .append("pairingId", pairingId.toHexString)
        .append("roundNumber", pairing.roundNumber)
        .append("challengeId", issuedChallenge.challengeId)
        .append("whiteLichessUserId", pairing.whiteLichessUserId)
        .append("blackLichessUserId", pairing.blackLichessUserId)

      pairingRepository
        .setChallengeIssued(
          session = session,
          pairingId = pairingId,
          challengeId = issuedChallenge.challengeId,
          challengeIssuedAt = now
        )
        .flatMap {
          case false =>
            pairingRepository.findByTournamentAndId(session, tournamentId, pairingId).map {
              case Some(updatedPairing) if updatedPairing.challengeId.nonEmpty =>
                Right(
                  toIssueChallengeView(
                    tournamentId = tournamentId,
                    pairing = updatedPairing,
                    challengeId = updatedPairing.challengeId.get,
                    status = "already_issued"
                  )
                )
              case _ =>
                Left(Conflict("Challenge was already issued by another request"))
            }
          case true =>
            auditEventRepository
              .insert(
                session,
                AuditEventDocument(
                  _id = Some(new ObjectId()),
                  tournamentId = tournamentId,
                  `type` = "challenge_issued",
                  payload = payload,
                  createdAt = now
                )
              )
              .map(_ => Right(toIssueChallengeView(tournamentId, pairing, issuedChallenge.challengeId, issuedChallenge.status)))
        }
    }

  private def toIssueChallengeView(
      tournamentId: ObjectId,
      pairing: PairingDocument,
      challengeId: String,
      status: String
  ): IssueChallengeView =
    IssueChallengeView(
      tournamentId = tournamentId.toHexString,
      pairingId = pairing._id.get.toHexString,
      roundNumber = pairing.roundNumber,
      whiteLichessUserId = pairing.whiteLichessUserId,
      blackLichessUserId = pairing.blackLichessUserId,
      challengeId = challengeId,
      status = status
    )

  private def refreshRoundResultsForTournament(
      tournamentId: ObjectId,
      roundNumber: Int,
      user: AuthenticatedUser
  ): Future[Either[TournamentError, RefreshRoundResultsView]] =
    roundRepository.findByTournamentAndRoundNumber(tournamentId, roundNumber).flatMap {
      case None => Future.successful(Left(NotFound(s"Round $roundNumber not found")))
      case Some(round) if round.status != "active" =>
        Future.successful(Left(Conflict(s"Round $roundNumber must be active to refresh results")))
      case Some(round) =>
        pairingRepository.listByRound(round._id.get).flatMap { pairings =>
          val candidatePairings = pairings.filter(pairing => pairing.gameId.nonEmpty && !pairing.isOfficial)
          Future
            .traverse(candidatePairings) { pairing =>
              challengeGateway.lookupGameResult(pairing.gameId, user.accessToken).flatMap {
                case Left(_) => Future.successful(false)
                case Right(Some(result)) if pairing.result.contains(result) => Future.successful(false)
                case Right(Some(result)) =>
                  pairingRepository.updateResult(pairing._id.get, result, isOfficial = false)
                case Right(None) => Future.successful(false)
              }
            }
            .flatMap { updates =>
              val refreshedCount = updates.count(identity)
              val now = new Date()
              val payload = new Document()
                .append("roundNumber", roundNumber)
                .append("refreshedPairings", refreshedCount)

              auditEventRepository
                .insert(
                  AuditEventDocument(
                    _id = Some(new ObjectId()),
                    tournamentId = tournamentId,
                    `type` = "round_results_refreshed",
                    payload = payload,
                    createdAt = now
                  )
                )
                .map(_ =>
                  Right(
                    RefreshRoundResultsView(
                      tournamentId = tournamentId.toHexString,
                      roundNumber = roundNumber,
                      refreshedPairings = refreshedCount
                    )
                  )
                )
            }
        }
    }

  private def endRoundInTransaction(
      session: ClientSession,
      tournament: TournamentDocument,
      roundNumber: Int
  ): Future[Either[TournamentError, EndRoundView]] = {
    val tournamentId = tournament._id.get

    roundRepository.findByTournamentAndRoundNumber(session, tournamentId, roundNumber).flatMap {
      case None => Future.successful(Left(NotFound(s"Round $roundNumber not found")))
      case Some(round) if round.status == "completed" =>
        Future.successful(Left(Conflict(s"Round $roundNumber is already completed")))
      case Some(round) if round.status != "active" =>
        Future.successful(Left(Conflict(s"Round $roundNumber must be active to end the round")))
      case Some(round) =>
        for {
          pairings <- pairingRepository.listByRound(session, round._id.get)
          _ <- if (pairings.isEmpty) Future.successful(()) else finalizeRoundPairingResults(session, pairings)
          _ <- roundRepository.markCompleted(session, round._id.get, new Date())
          _ <- updateTournamentStatusAfterRoundCompletion(session, tournament, roundNumber)
          _ <- recomputePlayerTournamentStates(session, tournamentId, new Date())
          _ <- auditEventRepository.insert(
            session,
            AuditEventDocument(
              _id = Some(new ObjectId()),
              tournamentId = tournamentId,
              `type` = "round_completed",
              payload = new Document()
                .append("roundNumber", roundNumber)
                .append("pairingCount", pairings.size)
                .append("doubleForfeitCount", pairings.count(_.result.isEmpty)),
              createdAt = new Date()
            )
          )
        } yield Right(
          EndRoundView(
            tournamentId = tournamentId.toHexString,
            roundNumber = roundNumber,
            completedPairings = pairings.size,
            doubleForfeitCount = pairings.count(_.result.isEmpty),
            roundStatus = "completed"
          )
        )
    }
  }

  private def overridePairingInTransaction(
      session: ClientSession,
      tournamentId: ObjectId,
      pairingId: ObjectId,
      result: String,
      reason: String,
      appliedBy: String
  ): Future[Either[TournamentError, OverridePairingResultView]] =
    pairingRepository.findByTournamentAndId(session, tournamentId, pairingId).flatMap {
      case None => Future.successful(Left(NotFound(s"Pairing '${pairingId.toHexString}' not found in tournament")))
      case Some(pairing) =>
        val now = new Date()
        val overrideDocument = OverrideDocument(
          _id = Some(new ObjectId()),
          pairingId = pairingId,
          reason = reason,
          appliedBy = appliedBy,
          createdAt = now
        )
        for {
          _ <- pairingRepository.updateResult(session, pairingId, result, isOfficial = true)
          _ <- overrideRepository.insert(session, overrideDocument)
          _ <- recomputePlayerTournamentStates(session, tournamentId, now)
          _ <- auditEventRepository.insert(
            session,
            AuditEventDocument(
              _id = Some(new ObjectId()),
              tournamentId = tournamentId,
              `type` = "pairing_result_overridden",
              payload = new Document()
                .append("pairingId", pairingId.toHexString)
                .append("roundNumber", pairing.roundNumber)
                .append("result", result)
                .append("reason", reason)
                .append("appliedBy", appliedBy),
              createdAt = now
            )
          )
        } yield Right(
          OverridePairingResultView(
            tournamentId = tournamentId.toHexString,
            pairingId = pairingId.toHexString,
            roundNumber = pairing.roundNumber,
            result = result,
            reason = reason,
            appliedBy = appliedBy
          )
        )
    }

  private def finalizeRoundPairingResults(
      session: ClientSession,
      pairings: Seq[PairingDocument]
  ): Future[Unit] =
    Future
      .traverse(pairings) { pairing =>
        pairingRepository.updateResult(
          session = session,
          pairingId = pairing._id.get,
          result = pairing.result.getOrElse(TournamentRules.ResultForfeit),
          isOfficial = true
        )
      }
      .map(_ => ())

  private def updateTournamentStatusAfterRoundCompletion(
      session: ClientSession,
      tournament: TournamentDocument,
      roundNumber: Int
  ): Future[Unit] = {
    val now = new Date()
    val newStatus =
      if (roundNumber >= tournament.effectiveMaxRounds) "completed"
      else if (tournament.status == "draft") "active"
      else tournament.status

    if (newStatus == tournament.status) {
      Future.unit
    } else {
      tournamentRepository
        .replaceById(
          session,
          tournament._id.get,
          tournament.copy(status = newStatus, updatedAt = now)
        )
        .map(_ => ())
    }
  }

  private def recomputePlayerTournamentStates(
      session: ClientSession,
      tournamentId: ObjectId,
      now: Date
  ): Future[Unit] =
    for {
      registrations <- registrationRepository.listByTournament(session, tournamentId)
      existingStates <- playerTournamentStateRepository.listByTournament(session, tournamentId)
      pairings <- pairingRepository.listByTournament(session, tournamentId)
      byes <- byeRepository.listByTournament(session, tournamentId)
      _ <- {
        val participantIds = registrations.map(_.lichessUserId).distinct.sorted
        val stateIdsByUser = existingStates.flatMap(state => state._id.map(id => state.lichessUserId -> id)).toMap
        val initialAccumulators = participantIds.map(userId => userId -> StateAccumulator.empty).toMap

        val pairingsByRound = pairings
          .filter(pairing => pairing.isOfficial && pairing.result.nonEmpty)
          .sortBy(pairing => (pairing.roundNumber, pairing.createdAt.getTime))
        val byesByRound = byes.sortBy(bye => (bye.roundNumber, bye.createdAt.getTime))

        val afterPairings = pairingsByRound.foldLeft(initialAccumulators) { (acc, pairing) =>
          val result = pairing.result.get
          val whiteScore = resultScoreForWhite(result)
          val blackScore = resultScoreForBlack(result)
          val whiteRoundResult = playerRoundResultForWhite(result)
          val blackRoundResult = playerRoundResultForBlack(result)
          val countAsGame = result != TournamentRules.ResultForfeit

          updateAccumulator(
            updateAccumulator(
              acc = acc,
              userId = pairing.whiteLichessUserId,
              score = whiteScore,
              opponent = Some(pairing.blackLichessUserId),
              color = Some("white"),
              roundNumber = pairing.roundNumber,
              roundResult = whiteRoundResult,
              addGame = countAsGame
            ),
            userId = pairing.blackLichessUserId,
            score = blackScore,
            opponent = Some(pairing.whiteLichessUserId),
            color = Some("black"),
            roundNumber = pairing.roundNumber,
            roundResult = blackRoundResult,
            addGame = countAsGame
          )
        }

        val finalState = byesByRound.foldLeft(afterPairings) { (acc, bye) =>
          updateAccumulator(
            acc = acc,
            userId = bye.lichessUserId,
            score = bye.scoreAwarded,
            opponent = None,
            color = None,
            roundNumber = bye.roundNumber,
            roundResult = "bye",
            addGame = false
          )
        }

        val stateDocuments = participantIds.map { userId =>
          val state = finalState.getOrElse(userId, StateAccumulator.empty)
          PlayerTournamentStateDocument(
            _id = Some(stateIdsByUser.getOrElse(userId, new ObjectId())),
            tournamentId = tournamentId,
            lichessUserId = userId,
            points = state.points,
            gamesPlayed = state.gamesPlayed,
            opponents = state.opponents,
            colors = state.colors,
            resultsByRound = state.resultsByRound,
            tiebreaks = TiebreaksDocument(0d, 0d),
            updatedAt = now
          )
        }

        Future
          .traverse(stateDocuments)(doc => playerTournamentStateRepository.upsertByTournamentAndUser(session, doc))
          .map(_ => ())
      }
    } yield ()

  private def updateAccumulator(
      acc: Map[String, StateAccumulator],
      userId: String,
      score: Double,
      opponent: Option[String],
      color: Option[String],
      roundNumber: Int,
      roundResult: String,
      addGame: Boolean
  ): Map[String, StateAccumulator] = {
    val current = acc.getOrElse(userId, StateAccumulator.empty)
    val updated = current.copy(
      points = current.points + score,
      gamesPlayed = current.gamesPlayed + (if (addGame) 1 else 0),
      opponents = opponent.map(value => current.opponents :+ value).getOrElse(current.opponents),
      colors = color.map(value => current.colors :+ value).getOrElse(current.colors),
      resultsByRound = current.resultsByRound.updated(roundNumber.toString, roundResult)
    )
    acc.updated(userId, updated)
  }

  private def resultScoreForWhite(result: String): Double =
    result match {
      case TournamentRules.ResultWhite => 1d
      case TournamentRules.ResultDraw  => 0.5d
      case _                           => 0d
    }

  private def resultScoreForBlack(result: String): Double =
    result match {
      case TournamentRules.ResultBlack => 1d
      case TournamentRules.ResultDraw  => 0.5d
      case _                           => 0d
    }

  private def playerRoundResultForWhite(result: String): String =
    result match {
      case TournamentRules.ResultBlack => TournamentRules.ResultBlack
      case TournamentRules.ResultDraw  => TournamentRules.ResultDraw
      case TournamentRules.ResultForfeit => TournamentRules.ResultForfeit
      case _ => TournamentRules.ResultWhite
    }

  private def playerRoundResultForBlack(result: String): String =
    result match {
      case TournamentRules.ResultWhite => TournamentRules.ResultWhite
      case TournamentRules.ResultDraw  => TournamentRules.ResultDraw
      case TournamentRules.ResultForfeit => TournamentRules.ResultForfeit
      case _ => TournamentRules.ResultBlack
    }

  private def computeTiebreaks(
      states: Seq[PlayerTournamentStateDocument],
      officialPairings: Seq[PairingDocument]
  ): Map[String, TiebreaksDocument] = {
    // Buchholz = sum(opponents' final points); SB = game score vs opponent * opponent points.
    val pointsByUser = states.map(state => state.lichessUserId -> state.points).toMap
    val buchholzByUser = states.map { state =>
      val buchholz = state.opponents.map(opponent => pointsByUser.getOrElse(opponent, 0d)).sum
      state.lichessUserId -> buchholz
    }.toMap

    val sonnebornBergerByUser = officialPairings.foldLeft(Map.empty[String, Double].withDefaultValue(0d)) {
      (acc, pairing) =>
        val result = pairing.result.get
        val whiteOpponentPoints = pointsByUser.getOrElse(pairing.blackLichessUserId, 0d)
        val blackOpponentPoints = pointsByUser.getOrElse(pairing.whiteLichessUserId, 0d)
        val whiteContribution = resultScoreForWhite(result) * whiteOpponentPoints
        val blackContribution = resultScoreForBlack(result) * blackOpponentPoints

        acc
          .updated(pairing.whiteLichessUserId, acc(pairing.whiteLichessUserId) + whiteContribution)
          .updated(pairing.blackLichessUserId, acc(pairing.blackLichessUserId) + blackContribution)
    }

    states.map { state =>
      state.lichessUserId -> TiebreaksDocument(
        buchholz = buchholzByUser.getOrElse(state.lichessUserId, 0d),
        sonnebornBerger = sonnebornBergerByUser(state.lichessUserId)
      )
    }.toMap
  }

  private def rankStandingsEntries(
      states: Seq[PlayerTournamentStateDocument],
      tiebreaksByUser: Map[String, TiebreaksDocument]
  ): Seq[StandingsEntryView] = {
    val sorted = states.sortBy { state =>
      val tiebreaks = tiebreaksByUser.getOrElse(state.lichessUserId, TiebreaksDocument(0d, 0d))
      (-state.points, -tiebreaks.buchholz, -tiebreaks.sonnebornBerger, state.lichessUserId)
    }

    sorted.zipWithIndex.foldLeft(Vector.empty[StandingsEntryView]) { case (acc, (state, index)) =>
      val tiebreaks = tiebreaksByUser.getOrElse(state.lichessUserId, TiebreaksDocument(0d, 0d))
      val currentKey = (state.points, tiebreaks.buchholz, tiebreaks.sonnebornBerger)
      val previousRank = acc.lastOption.map(_.rank).getOrElse(1)
      val previousKey = acc.lastOption.map(entry => (entry.points, entry.buchholz, entry.sonnebornBerger))
      val rank =
        if (previousKey.contains(currentKey)) previousRank
        else index + 1

      acc :+ StandingsEntryView(
        rank = rank,
        lichessUserId = state.lichessUserId,
        points = state.points,
        gamesPlayed = state.gamesPlayed,
        buchholz = tiebreaks.buchholz,
        sonnebornBerger = tiebreaks.sonnebornBerger
      )
    }
  }

  private def upsertForRegistration(
      tournamentId: ObjectId,
      lichessUserId: String
  ): Future[Either[TournamentError, RegistrationView]] =
    registrationRepository.findByTournamentAndUser(tournamentId, lichessUserId).flatMap {
      case Some(existing) if existing.status == RegistrationStatus.Registered =>
        Future.successful(Left(Conflict(s"User '$lichessUserId' is already registered")))
      case Some(existing) if existing.status == RegistrationStatus.Withdrawn =>
        Future.successful(Left(Conflict(s"User '$lichessUserId' is withdrawn; use reactivate endpoint")))
      case Some(existing) if existing.status == RegistrationStatus.Disqualified =>
        Future.successful(Left(Conflict(s"User '$lichessUserId' is disqualified and cannot be registered")))
      case Some(existing) =>
        Future.successful(Left(Conflict(s"User '$lichessUserId' has unsupported status '${existing.status}'")))
      case None =>
        nextEffectiveRound(tournamentId).flatMap { effectiveRound =>
          val created = RegistrationDocument(
            _id = Some(new ObjectId()),
            tournamentId = tournamentId,
            lichessUserId = lichessUserId,
            status = RegistrationStatus.Registered,
            effectiveRound = effectiveRound,
            createdAt = new Date()
          )
          registrationRepository.insert(created).map { inserted =>
            Right(toRegistrationView(inserted))
          }
        }
    }

  private def updateRegistrationStatus(
      tournamentId: ObjectId,
      lichessUserId: String,
      targetStatus: String
  ): Future[Either[TournamentError, RegistrationView]] =
    for {
      tournament <- tournamentRepository.findByIdOption(tournamentId)
      result <- tournament match {
        case None => Future.successful(Left(NotFound(s"Tournament '${tournamentId.toHexString}' not found")))
        case Some(_) =>
          registrationRepository.findByTournamentAndUser(tournamentId, lichessUserId).flatMap {
            case None => Future.successful(Left(NotFound(s"User '$lichessUserId' is not registered in this tournament")))
            case Some(existing) if existing.status == RegistrationStatus.Disqualified =>
              Future.successful(Left(Conflict(s"User '$lichessUserId' is disqualified and cannot be updated")))
            case Some(existing) if existing.status == targetStatus =>
              Future.successful(Left(Conflict(s"User '$lichessUserId' already has status '$targetStatus'")))
            case Some(existing) if !RegistrationStatus.canTransition(existing.status, targetStatus) =>
              Future.successful(
                Left(
                  Conflict(
                    s"Cannot transition user '$lichessUserId' from '${existing.status}' to '$targetStatus'"
                  )
                )
              )
            case Some(existing) =>
              nextEffectiveRound(tournamentId).flatMap { effectiveRound =>
                val updated = existing.copy(status = targetStatus, effectiveRound = effectiveRound)
                registrationRepository.replaceByTournamentAndUser(updated).map {
                  case false => Left(NotFound(s"User '$lichessUserId' registration was not updated"))
                  case true  => Right(toRegistrationView(updated))
                }
              }
          }
      }
    } yield result

  private def nextEffectiveRound(tournamentId: ObjectId): Future[Int] =
    // Milestone 3 rule: registration state changes only apply from the next round.
    roundRepository.latestRoundNumberForTournament(tournamentId).map(TournamentRules.nextEffectiveRound)

  private def toRegistrationView(document: RegistrationDocument): RegistrationView =
    RegistrationView(
      tournamentId = document.tournamentId.toHexString,
      lichessUserId = document.lichessUserId,
      status = document.status,
      effectiveRound = document.effectiveRound,
      createdAt = document.createdAt.toInstant.toString
    )

  private def toTournamentView(document: TournamentDocument): TournamentView =
    TournamentView(
      id = document._id.map(_.toHexString).getOrElse(""),
      name = document.name,
      teamId = document.teamId,
      timeControlInitialSeconds = document.timeControlInitialSeconds,
      timeControlIncrementSeconds = document.timeControlIncrementSeconds,
      rated = document.rated,
      status = document.status,
      configuredMaxRounds = document.configuredMaxRounds,
      effectiveMaxRounds = document.effectiveMaxRounds,
      createdAt = document.createdAt.toInstant.toString,
      updatedAt = document.updatedAt.toInstant.toString
    )

  private def toMyPairingEntryView(pairing: PairingDocument, lichessUserId: String): MyPairingEntryView = {
    val isWhite = pairing.whiteLichessUserId == lichessUserId
    val opponent = if (isWhite) pairing.blackLichessUserId else pairing.whiteLichessUserId
    val challengeStatus =
      if (pairing.challengeId.nonEmpty) "issued"
      else if (!isWhite) "awaiting_white"
      else "pending"
    val lastUpdateAt = pairing.gameStartedAt
      .orElse(pairing.challengeIssuedAt)
      .getOrElse(pairing.createdAt)
      .toInstant
      .toString

    MyPairingEntryView(
      pairingId = pairing._id.map(_.toHexString).getOrElse(""),
      roundNumber = pairing.roundNumber,
      opponentLichessUserId = opponent,
      color = if (isWhite) "white" else "black",
      challengeStatus = challengeStatus,
      gameId = Option(pairing.gameId).filter(_.nonEmpty),
      result = pairing.result,
      isOfficial = pairing.isOfficial,
      lastUpdateAt = lastUpdateAt
    )
  }

  private def toPublicTournamentCard(document: TournamentDocument): Future[PublicTournamentCardView] = {
    val tournamentId = document._id.getOrElse(new ObjectId())
    roundRepository.latestRoundNumberForTournament(tournamentId).map { latestRound =>
      PublicTournamentCardView(
        id = tournamentId.toHexString,
        name = document.name,
        status = document.status,
        configuredMaxRounds = document.configuredMaxRounds,
        effectiveMaxRounds = document.effectiveMaxRounds,
        currentRoundNumber = latestRound.getOrElse(0),
        createdAt = document.createdAt.toInstant.toString,
        updatedAt = document.updatedAt.toInstant.toString
      )
    }
  }

  private def buildPairings(
      players: Seq[String],
      previousOpponents: Map[String, Set[String]]
  ): Seq[PairingView] = {
    @scala.annotation.tailrec
    def loop(remaining: Vector[String], acc: Vector[PairingView]): Vector[PairingView] =
      if (remaining.isEmpty || remaining.size == 1) acc
      else {
        val player = remaining.head
        val opponents = previousOpponents.getOrElse(player, Set.empty)
        val tail = remaining.tail
        val opponent = tail.find(candidate => !opponents.contains(candidate)).getOrElse(tail.head)
        val white = player
        val black = opponent
        val nextRemaining = remaining.filterNot(id => id == player || id == opponent)
        loop(nextRemaining, acc :+ PairingView(white, black))
      }

    loop(players.sorted.toVector, Vector.empty).toSeq
  }

  private def buildOpponentMap(pairings: Seq[PairingDocument]): Map[String, Set[String]] =
    pairings.foldLeft(Map.empty[String, Set[String]]) { (acc, pairing) =>
      val p1 = pairing.whiteLichessUserId
      val p2 = pairing.blackLichessUserId
      acc
        .updated(p1, acc.getOrElse(p1, Set.empty) + p2)
        .updated(p2, acc.getOrElse(p2, Set.empty) + p1)
    }

  private def inTransactionEither[T](
      op: ClientSession => Future[Either[TournamentError, T]]
  ): Future[Either[TournamentError, T]] =
    mongoClient.startSession().toFuture().flatMap { session =>
      session.startTransaction()
      op(session).transformWith {
        case Success(right @ Right(_)) =>
          org.mongodb.scala.ToSingleObservableUnit(session.commitTransaction()).toFuture().map(_ => right)
        case Success(left @ Left(_)) =>
          abortTransactionQuietly(session).map(_ => left)
        case Failure(error) if isDuplicateKeyError(error) =>
          // Milestone 8 hardening: turn racing unique-index violations into deterministic domain conflicts.
          abortTransactionQuietly(session)
            .map(_ => Left(Conflict("Operation conflicted with a concurrent write; retry the request")))
        case Failure(error) =>
          abortTransactionQuietly(session).flatMap(_ => Future.failed(error))
      }
        .andThen { case _ => session.close() }
    }

  private def abortTransactionQuietly(session: ClientSession): Future[Unit] =
    org.mongodb.scala.ToSingleObservableUnit(session.abortTransaction()).toFuture().recover(_ => ())

  private def isDuplicateKeyError(error: Throwable): Boolean = {
    @scala.annotation.tailrec
    def loop(current: Throwable): Boolean =
      if (current == null) {
        false
      } else {
        current match {
          case write: MongoWriteException =>
            write.getError != null && write.getError.getCode == 11000
          case bulk: MongoBulkWriteException =>
            bulk.getWriteErrors.asScala.exists(_.getCode == 11000)
          case _ =>
            loop(current.getCause)
        }
      }

    loop(error)
  }

}

object TournamentService {
  private final case class PlannedBye(
      lichessUserId: String,
      scoreAwarded: Double,
      reason: String
  )

  private final case class RoundPlan(
      pairings: Seq[PairingView],
      byes: Seq[PlannedBye],
      computedEffectiveMaxRounds: Int
  )

  private final case class StateAccumulator(
      points: Double,
      gamesPlayed: Int,
      opponents: Seq[String],
      colors: Seq[String],
      resultsByRound: Map[String, String]
  )

  private object StateAccumulator {
    val empty: StateAccumulator = StateAccumulator(
      points = 0d,
      gamesPlayed = 0,
      opponents = Seq.empty,
      colors = Seq.empty,
      resultsByRound = Map.empty
    )
  }
}
