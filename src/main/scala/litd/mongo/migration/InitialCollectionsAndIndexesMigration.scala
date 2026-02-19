package litd.mongo.migration

import litd.domain._
import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.model.{IndexOptions, Indexes}

import scala.concurrent.{ExecutionContext, Future}

object InitialCollectionsAndIndexesMigration extends Migration {
  override val version: Int = 1
  override val description: String = "Create base collections and indexes from SPEC.md"

  override def up(database: MongoDatabase)(implicit ec: ExecutionContext): Future[Unit] = {
    val tournaments = database.getCollection[TournamentDocument]("tournaments")
    val registrations = database.getCollection[RegistrationDocument]("registrations")
    val rounds = database.getCollection[RoundDocument]("rounds")
    val pairings = database.getCollection[PairingDocument]("pairings")
    val byes = database.getCollection[ByeDocument]("byes")
    val playerTournamentState = database.getCollection[PlayerTournamentStateDocument]("playerTournamentState")
    val overrides = database.getCollection[OverrideDocument]("overrides")
    val auditEvents = database.getCollection[AuditEventDocument]("auditEvents")

    val ops = Seq(
      tournaments.createIndex(Indexes.ascending("status")).toFuture(),
      tournaments.createIndex(Indexes.ascending("teamId")).toFuture(),
      registrations
        .createIndex(
          Indexes.ascending("tournamentId", "lichessUserId"),
          IndexOptions().unique(true)
        )
        .toFuture(),
      registrations
        .createIndex(Indexes.ascending("tournamentId", "status", "effectiveRound"))
        .toFuture(),
      rounds
        .createIndex(
          Indexes.ascending("tournamentId", "roundNumber"),
          IndexOptions().unique(true)
        )
        .toFuture(),
      pairings
        .createIndex(
          Indexes.ascending("roundId", "playerIds"),
          IndexOptions().unique(true)
        )
        .toFuture(),
      pairings
        .createIndex(Indexes.ascending("tournamentId", "roundNumber"))
        .toFuture(),
      pairings
        .createIndex(Indexes.ascending("tournamentId", "playerIds", "roundNumber"))
        .toFuture(),
      pairings.createIndex(Indexes.ascending("gameId")).toFuture(),
      byes
        .createIndex(
          Indexes.ascending("roundId", "lichessUserId"),
          IndexOptions().unique(true)
        )
        .toFuture(),
      byes
        .createIndex(Indexes.ascending("tournamentId", "lichessUserId", "roundNumber"))
        .toFuture(),
      playerTournamentState
        .createIndex(
          Indexes.ascending("tournamentId", "lichessUserId"),
          IndexOptions().unique(true)
        )
        .toFuture(),
      playerTournamentState
        .createIndex(Indexes.descending("tournamentId", "points"))
        .toFuture(),
      overrides
        .createIndex(Indexes.ascending("pairingId", "createdAt"))
        .toFuture(),
      auditEvents
        .createIndex(Indexes.ascending("tournamentId", "createdAt"))
        .toFuture()
    )

    Future.sequence(ops).map(_ => ())
  }
}
