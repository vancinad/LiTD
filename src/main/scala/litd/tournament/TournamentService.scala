package litd.tournament

import litd.domain.{RegistrationDocument, TournamentDocument}
import litd.mongo.repository.{RegistrationRepository, RoundRepository, TournamentRepository}
import litd.tournament.TournamentError.{BadRequest, Conflict, NotFound}

import java.util.Date
import org.bson.types.ObjectId

import scala.concurrent.{ExecutionContext, Future}

final class TournamentService(
    tournamentRepository: TournamentRepository,
    registrationRepository: RegistrationRepository,
    roundRepository: RoundRepository
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
}
