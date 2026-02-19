package litd.mongo.migration

import litd.mongo.AppliedMigrationDocument
import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.model.{Filters, IndexOptions, Indexes}

import java.util.Date
import scala.concurrent.{ExecutionContext, Future}

final class MigrationRunner(
    database: MongoDatabase,
    migrations: Seq[Migration]
) {
  private val appliedMigrationsCollection = database.getCollection[AppliedMigrationDocument]("_migrations")

  def run()(implicit ec: ExecutionContext): Future[Unit] =
    for {
      _ <- appliedMigrationsCollection
        .createIndex(Indexes.ascending("version"), IndexOptions().unique(true))
        .toFuture()
      applied <- appliedMigrationsCollection.find().toFuture()
      appliedVersions = applied.map(_.version).toSet
      pending = migrations.sortBy(_.version).filterNot(m => appliedVersions.contains(m.version))
      _ <- pending.foldLeft(Future.unit) { (acc, migration) =>
        acc.flatMap { _ =>
          migration
            .up(database)
            .flatMap { _ =>
              appliedMigrationsCollection
                .insertOne(
                  AppliedMigrationDocument(
                    version = migration.version,
                    description = migration.description,
                    appliedAt = new Date()
                  )
                )
                .toFuture()
                .map(_ => ())
            }
        }
      }
    } yield ()

  def hasVersion(version: Int)(implicit ec: ExecutionContext): Future[Boolean] =
    appliedMigrationsCollection
      .find(Filters.equal("version", version))
      .first()
      .toFutureOption()
      .map(_.nonEmpty)
}

object MigrationRunner {
  def default(database: MongoDatabase): MigrationRunner =
    new MigrationRunner(
      database,
      Seq(
        InitialCollectionsAndIndexesMigration,
        OAuthAndTeamGateMigration,
        ChallengeIssuanceMigration
      )
    )
}
