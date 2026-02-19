package litd.tournament

import akka.actor.Cancellable
import akka.actor.typed.ActorSystem
import litd.auth.CryptoService
import litd.domain.AuditEventDocument
import litd.mongo.repository.{AuditEventRepository, OAuthTokenRepository, PairingRepository}
import org.bson.Document
import org.bson.types.ObjectId

import java.util.Date
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

final case class ChallengeWorkerConfig(
    enabled: Boolean,
    pollIntervalSeconds: Int,
    batchSize: Int
)

final class ChallengeSyncWorker(
    config: ChallengeWorkerConfig,
    pairingRepository: PairingRepository,
    oauthTokenRepository: OAuthTokenRepository,
    auditEventRepository: AuditEventRepository,
    cryptoService: CryptoService,
    challengeGateway: ChallengeGateway
)(implicit system: ActorSystem[Nothing], ec: ExecutionContext) {
  private val logger = system.log

  def start(): Option[Cancellable] =
    if (!config.enabled) {
      logger.info("Challenge sync worker disabled")
      None
    } else {
      val pollInterval = config.pollIntervalSeconds.max(5).seconds
      logger.info("Challenge sync worker enabled, polling every {} seconds", pollInterval.toSeconds)
      Some(
        system.scheduler.scheduleWithFixedDelay(5.seconds, pollInterval) { () =>
          syncOnce().failed.foreach(ex => logger.warn("Challenge sync tick failed: {}", ex.getMessage))
        }(system.executionContext)
      )
    }

  def syncOnce(): Future[Unit] =
    pairingRepository
      .listPendingChallengeGames(config.batchSize.max(1))
      .flatMap { pairings =>
        Future.traverse(pairings)(syncPairing).map(_ => ())
      }

  private def syncPairing(pairing: litd.domain.PairingDocument): Future[Unit] =
    pairing.challengeId match {
      case None => Future.unit
      case Some(challengeId) =>
        oauthTokenRepository.findByLichessUserId(pairing.whiteLichessUserId).flatMap {
          case None => Future.unit
          case Some(tokenDoc) =>
            val maybeAccessToken =
              scala.util.Try(cryptoService.decrypt(tokenDoc.encryptedAccessToken)).toOption

            maybeAccessToken match {
              case None => Future.unit
              case Some(accessToken) =>
                challengeGateway.lookupGameId(challengeId, accessToken).flatMap {
                  case Left(error) =>
                    logger.debug("Challenge lookup failed for pairing {}: {}", pairing._id.map(_.toHexString).getOrElse(""), error)
                    Future.unit
                  case Right(None) => Future.unit
                  case Right(Some(gameId)) =>
                    pairing._id match {
                      case None => Future.unit
                      case Some(pairingId) =>
                        val now = new Date()
                        pairingRepository
                          .setGameStarted(pairingId, gameId, now)
                          .flatMap {
                            case false => Future.unit
                            case true =>
                              val payload = new Document()
                                .append("pairingId", pairingId.toHexString)
                                .append("roundNumber", pairing.roundNumber)
                                .append("challengeId", challengeId)
                                .append("gameId", gameId)
                              auditEventRepository
                                .insert(
                                  AuditEventDocument(
                                    _id = Some(new ObjectId()),
                                    tournamentId = pairing.tournamentId,
                                    `type` = "challenge_game_started",
                                    payload = payload,
                                    createdAt = now
                                  )
                                )
                                .map(_ => ())
                          }
                    }
                }
            }
        }
    }
}
