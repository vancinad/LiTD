package litd.tournament

import litd.domain.{
  AuditEventDocument,
  ByeDocument,
  PairingDocument,
  PlayerTournamentStateDocument,
  RoundDocument,
  TiebreaksDocument,
  RegistrationDocument,
  TournamentDocument
}
import litd.mongo.repository.{
  AuditEventRepository,
  ByeRepository,
  PairingRepository,
  PlayerTournamentStateRepository,
  RegistrationRepository,
  RoundRepository,
  TournamentRepository
}
import litd.tournament.TournamentError.{BadRequest, Conflict, NotFound}
import org.bson.Document
import org.bson.types.ObjectId
import org.mongodb.scala._

import java.util.Date
import scala.concurrent.{ExecutionContext, Future}

import litd.tournament.TournamentService.{PlannedBye, RoundPlan}

final class TournamentService(
    tournamentRepository: TournamentRepository,
    registrationRepository: RegistrationRepository,
    roundRepository: RoundRepository,
    pairingRepository: PairingRepository,
    byeRepository: ByeRepository,
    playerTournamentStateRepository: PlayerTournamentStateRepository,
    auditEventRepository: AuditEventRepository,
    mongoClient: MongoClient
)(implicit ec: ExecutionContext) {

  /** Creates a tournament with spec-capped configured rounds and draft status. */
  def createTournament(request: CreateTournamentRequest): Future[Either[TournamentError, TournamentView]] = {
    val trimmedName = request.name.trim

    if (trimmedName.isEmpty) {
      Future.successful(Left(BadRequest("Tournament name must not be empty")))
    } else if (!TournamentRules.isValidConfiguredMaxRounds(request.configuredMaxRounds)) {
      Future.successful(
        Left(BadRequest(s"configuredMaxRounds must be between 1 and ${TournamentRules.MaxConfiguredRounds}"))
      )
    } else {
      val now = new Date()
      val document = TournamentDocument(
        _id = Some(new ObjectId()),
        name = trimmedName,
        status = "draft",
        configuredMaxRounds = request.configuredMaxRounds,
        effectiveMaxRounds = request.configuredMaxRounds,
        createdAt = now,
        updatedAt = now
      )

      tournamentRepository.insert(document).map { inserted =>
        val id = inserted._id.map(_.toHexString).getOrElse("")
        Right(
          TournamentView(
            id = id,
            name = inserted.name,
            status = inserted.status,
            configuredMaxRounds = inserted.configuredMaxRounds,
            effectiveMaxRounds = inserted.effectiveMaxRounds,
            createdAt = inserted.createdAt.toInstant.toString,
            updatedAt = inserted.updatedAt.toInstant.toString
          )
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
      op(session)
        .flatMap {
          case right @ Right(_) =>
            org.mongodb.scala.ToSingleObservableUnit(session.commitTransaction()).toFuture().map(_ => right)
          case left @ Left(_) =>
            org.mongodb.scala.ToSingleObservableUnit(session.abortTransaction()).toFuture().map(_ => left)
        }
        .recoverWith { case error =>
          org.mongodb.scala
            .ToSingleObservableUnit(session.abortTransaction())
            .toFuture()
            .recover(_ => ())
            .flatMap(_ => Future.failed(error))
        }
        .andThen { case _ => session.close() }
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
}
