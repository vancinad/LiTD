package litd.mongo.repository

import litd.domain._
import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.Updates.set
import org.mongodb.scala.model.ReplaceOptions
import org.mongodb.scala.model.Sorts
import org.mongodb.scala.ClientSession

import scala.concurrent.{ExecutionContext, Future}

final class TournamentRepository(database: MongoDatabase)
    extends MongoRepository[TournamentDocument](database, "tournaments") {
  def findByIdOption(id: org.bson.types.ObjectId)(implicit ec: ExecutionContext): Future[Option[TournamentDocument]] =
    collection.find(equal("_id", id)).first().toFutureOption()

  def findByIdOption(session: ClientSession, id: org.bson.types.ObjectId)(implicit
      ec: ExecutionContext
  ): Future[Option[TournamentDocument]] =
    collection.find(session, equal("_id", id)).first().toFutureOption()

  def replaceById(session: ClientSession, id: org.bson.types.ObjectId, document: TournamentDocument)(implicit
      ec: ExecutionContext
  ): Future[Boolean] =
    collection
      .replaceOne(session, equal("_id", id), document, ReplaceOptions().upsert(false))
      .toFuture()
      .map(_.getMatchedCount > 0)
}

final class RegistrationRepository(database: MongoDatabase)
    extends MongoRepository[RegistrationDocument](database, "registrations") {
  def findByTournamentAndUser(tournamentId: org.bson.types.ObjectId, lichessUserId: String)(implicit
      ec: ExecutionContext
  ): Future[Option[RegistrationDocument]] =
    collection
      .find(and(equal("tournamentId", tournamentId), equal("lichessUserId", lichessUserId)))
      .first()
      .toFutureOption()

  def findByTournamentAndUser(session: ClientSession, tournamentId: org.bson.types.ObjectId, lichessUserId: String)(implicit
      ec: ExecutionContext
  ): Future[Option[RegistrationDocument]] =
    collection
      .find(session, and(equal("tournamentId", tournamentId), equal("lichessUserId", lichessUserId)))
      .first()
      .toFutureOption()

  def replaceByTournamentAndUser(document: RegistrationDocument)(implicit ec: ExecutionContext): Future[Boolean] =
    collection
      .replaceOne(
        and(equal("tournamentId", document.tournamentId), equal("lichessUserId", document.lichessUserId)),
        document,
        ReplaceOptions().upsert(false)
      )
      .toFuture()
      .map(_.getMatchedCount > 0)

  def listEligibleForRound(tournamentId: org.bson.types.ObjectId, roundNumber: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[RegistrationDocument]] =
    collection
      .find(
        and(
          equal("tournamentId", tournamentId),
          equal("status", litd.tournament.RegistrationStatus.Registered),
          org.mongodb.scala.model.Filters.lte("effectiveRound", roundNumber)
        )
      )
      .toFuture()

  def listEligibleForRound(session: ClientSession, tournamentId: org.bson.types.ObjectId, roundNumber: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[RegistrationDocument]] =
    collection
      .find(
        session,
        and(
          equal("tournamentId", tournamentId),
          equal("status", litd.tournament.RegistrationStatus.Registered),
          org.mongodb.scala.model.Filters.lte("effectiveRound", roundNumber)
        )
      )
      .toFuture()
}

final class RoundRepository(database: MongoDatabase)
    extends MongoRepository[RoundDocument](database, "rounds") {
  def latestRoundNumberForTournament(tournamentId: org.bson.types.ObjectId)(implicit
      ec: ExecutionContext
  ): Future[Option[Int]] =
    collection
      .find(equal("tournamentId", tournamentId))
      .sort(Sorts.descending("roundNumber"))
      .first()
      .toFutureOption()
      .map(_.map(_.roundNumber))

  def latestRoundForTournament(tournamentId: org.bson.types.ObjectId)(implicit
      ec: ExecutionContext
  ): Future[Option[RoundDocument]] =
    collection
      .find(equal("tournamentId", tournamentId))
      .sort(Sorts.descending("roundNumber"))
      .first()
      .toFutureOption()

  def latestRoundForTournament(session: ClientSession, tournamentId: org.bson.types.ObjectId)(implicit
      ec: ExecutionContext
  ): Future[Option[RoundDocument]] =
    collection
      .find(session, equal("tournamentId", tournamentId))
      .sort(Sorts.descending("roundNumber"))
      .first()
      .toFutureOption()

  def findByTournamentAndRoundNumber(session: ClientSession, tournamentId: org.bson.types.ObjectId, roundNumber: Int)(implicit
      ec: ExecutionContext
  ): Future[Option[RoundDocument]] =
    collection
      .find(session, and(equal("tournamentId", tournamentId), equal("roundNumber", roundNumber)))
      .first()
      .toFutureOption()

  def insert(session: ClientSession, document: RoundDocument)(implicit ec: ExecutionContext): Future[RoundDocument] =
    collection.insertOne(session, document).toFuture().map(_ => document)
}

final class PairingRepository(database: MongoDatabase)
    extends MongoRepository[PairingDocument](database, "pairings") {
  def listByTournament(tournamentId: org.bson.types.ObjectId)(implicit ec: ExecutionContext): Future[Seq[PairingDocument]] =
    collection
      .find(equal("tournamentId", tournamentId))
      .toFuture()

  def listByTournament(session: ClientSession, tournamentId: org.bson.types.ObjectId)(implicit
      ec: ExecutionContext
  ): Future[Seq[PairingDocument]] =
    collection
      .find(session, equal("tournamentId", tournamentId))
      .toFuture()

  def insertMany(session: ClientSession, documents: Seq[PairingDocument])(implicit ec: ExecutionContext): Future[Unit] =
    if (documents.isEmpty) Future.unit
    else collection.insertMany(session, documents).toFuture().map(_ => ())

  def findByTournamentAndId(tournamentId: org.bson.types.ObjectId, pairingId: org.bson.types.ObjectId)(implicit
      ec: ExecutionContext
  ): Future[Option[PairingDocument]] =
    collection
      .find(and(equal("_id", pairingId), equal("tournamentId", tournamentId)))
      .first()
      .toFutureOption()

  def findByRoundAndUser(
      session: ClientSession,
      roundId: org.bson.types.ObjectId,
      lichessUserId: String
  )(implicit ec: ExecutionContext): Future[Option[PairingDocument]] =
    collection
      .find(
        session,
        and(
          equal("roundId", roundId),
          org.mongodb.scala.model.Filters.in("playerIds", lichessUserId)
        )
      )
      .first()
      .toFutureOption()

  def setChallengeIssued(
      session: ClientSession,
      pairingId: org.bson.types.ObjectId,
      challengeId: String,
      challengeIssuedAt: java.util.Date
  )(implicit ec: ExecutionContext): Future[Boolean] =
    collection
      .updateOne(
        session,
        and(
          equal("_id", pairingId),
          equal("gameId", ""),
          org.mongodb.scala.model.Filters.exists("challengeId", exists = false)
        ),
        org.mongodb.scala.model.Updates.combine(
          set("challengeId", challengeId),
          set("challengeIssuedAt", challengeIssuedAt)
        )
      )
      .toFuture()
      .map(_.getModifiedCount > 0)

  def listPendingChallengeGames(limit: Int)(implicit ec: ExecutionContext): Future[Seq[PairingDocument]] =
    collection
      .find(
        and(
          equal("gameId", ""),
          org.mongodb.scala.model.Filters.exists("challengeId", exists = true)
        )
      )
      .limit(limit)
      .toFuture()

  def setGameStarted(
      pairingId: org.bson.types.ObjectId,
      gameId: String,
      gameStartedAt: java.util.Date
  )(implicit ec: ExecutionContext): Future[Boolean] =
    collection
      .updateOne(
        and(
          equal("_id", pairingId),
          equal("gameId", "")
        ),
        org.mongodb.scala.model.Updates.combine(
          set("gameId", gameId),
          set("gameStartedAt", gameStartedAt)
        )
      )
      .toFuture()
      .map(_.getModifiedCount > 0)
}

final class ByeRepository(database: MongoDatabase)
    extends MongoRepository[ByeDocument](database, "byes") {
  def listByTournament(tournamentId: org.bson.types.ObjectId)(implicit ec: ExecutionContext): Future[Seq[ByeDocument]] =
    collection
      .find(equal("tournamentId", tournamentId))
      .toFuture()

  def listByTournament(session: ClientSession, tournamentId: org.bson.types.ObjectId)(implicit
      ec: ExecutionContext
  ): Future[Seq[ByeDocument]] =
    collection
      .find(session, equal("tournamentId", tournamentId))
      .toFuture()

  def findByRoundAndUser(session: ClientSession, roundId: org.bson.types.ObjectId, lichessUserId: String)(implicit
      ec: ExecutionContext
  ): Future[Option[ByeDocument]] =
    collection
      .find(session, and(equal("roundId", roundId), equal("lichessUserId", lichessUserId)))
      .first()
      .toFutureOption()

  def insertMany(session: ClientSession, documents: Seq[ByeDocument])(implicit ec: ExecutionContext): Future[Unit] =
    if (documents.isEmpty) Future.unit
    else collection.insertMany(session, documents).toFuture().map(_ => ())

  def insert(session: ClientSession, document: ByeDocument)(implicit ec: ExecutionContext): Future[ByeDocument] =
    collection.insertOne(session, document).toFuture().map(_ => document)
}

final class PlayerTournamentStateRepository(database: MongoDatabase)
    extends MongoRepository[PlayerTournamentStateDocument](database, "playerTournamentState") {
  def insertMany(session: ClientSession, documents: Seq[PlayerTournamentStateDocument])(implicit
      ec: ExecutionContext
  ): Future[Unit] =
    if (documents.isEmpty) Future.unit
    else collection.insertMany(session, documents).toFuture().map(_ => ())
}

final class OverrideRepository(database: MongoDatabase)
    extends MongoRepository[OverrideDocument](database, "overrides")

final class AuditEventRepository(database: MongoDatabase)
    extends MongoRepository[AuditEventDocument](database, "auditEvents") {
  def insert(session: ClientSession, document: AuditEventDocument)(implicit ec: ExecutionContext): Future[AuditEventDocument] =
    collection.insertOne(session, document).toFuture().map(_ => document)
}

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
