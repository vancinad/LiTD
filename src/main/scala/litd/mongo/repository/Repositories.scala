package litd.mongo.repository

import litd.domain._
import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.ReplaceOptions

import scala.concurrent.{ExecutionContext, Future}

final class TournamentRepository(database: MongoDatabase)
    extends MongoRepository[TournamentDocument](database, "tournaments")

final class RegistrationRepository(database: MongoDatabase)
    extends MongoRepository[RegistrationDocument](database, "registrations")

final class RoundRepository(database: MongoDatabase)
    extends MongoRepository[RoundDocument](database, "rounds")

final class PairingRepository(database: MongoDatabase)
    extends MongoRepository[PairingDocument](database, "pairings")

final class ByeRepository(database: MongoDatabase)
    extends MongoRepository[ByeDocument](database, "byes")

final class PlayerTournamentStateRepository(database: MongoDatabase)
    extends MongoRepository[PlayerTournamentStateDocument](database, "playerTournamentState")

final class OverrideRepository(database: MongoDatabase)
    extends MongoRepository[OverrideDocument](database, "overrides")

final class AuditEventRepository(database: MongoDatabase)
    extends MongoRepository[AuditEventDocument](database, "auditEvents")

final class OAuthTokenRepository(database: MongoDatabase)
    extends MongoRepository[OAuthTokenDocument](database, "oauthTokens") {
  def findByLichessUserId(lichessUserId: String)(implicit ec: ExecutionContext): Future[Option[OAuthTokenDocument]] =
    collection.find(equal("lichessUserId", lichessUserId)).first().toFutureOption()

  def findBySessionTokenHash(sessionTokenHash: String)(implicit
      ec: ExecutionContext
  ): Future[Option[OAuthTokenDocument]] =
    collection.find(equal("sessionTokenHash", sessionTokenHash)).first().toFutureOption()

  def upsertByLichessUserId(document: OAuthTokenDocument)(implicit ec: ExecutionContext): Future[Unit] =
    collection
      .replaceOne(
        equal("lichessUserId", document.lichessUserId),
        document,
        ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())
}

final class TeamMembershipCacheRepository(database: MongoDatabase)
    extends MongoRepository[TeamMembershipCacheDocument](database, "teamMembershipCache") {
  def findByTeamAndUser(teamId: String, lichessUserId: String)(implicit
      ec: ExecutionContext
  ): Future[Option[TeamMembershipCacheDocument]] =
    collection
      .find(and(equal("teamId", teamId), equal("lichessUserId", lichessUserId)))
      .first()
      .toFutureOption()

  def upsert(document: TeamMembershipCacheDocument)(implicit ec: ExecutionContext): Future[Unit] =
    collection
      .replaceOne(
        and(equal("teamId", document.teamId), equal("lichessUserId", document.lichessUserId)),
        document,
        ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())
}

final case class Repositories(
    tournaments: TournamentRepository,
    registrations: RegistrationRepository,
    rounds: RoundRepository,
    pairings: PairingRepository,
    byes: ByeRepository,
    playerTournamentState: PlayerTournamentStateRepository,
    overrides: OverrideRepository,
    auditEvents: AuditEventRepository,
    oauthTokens: OAuthTokenRepository,
    teamMembershipCache: TeamMembershipCacheRepository
)

object Repositories {
  def from(database: MongoDatabase): Repositories =
    Repositories(
      tournaments = new TournamentRepository(database),
      registrations = new RegistrationRepository(database),
      rounds = new RoundRepository(database),
      pairings = new PairingRepository(database),
      byes = new ByeRepository(database),
      playerTournamentState = new PlayerTournamentStateRepository(database),
      overrides = new OverrideRepository(database),
      auditEvents = new AuditEventRepository(database),
      oauthTokens = new OAuthTokenRepository(database),
      teamMembershipCache = new TeamMembershipCacheRepository(database)
    )
}
