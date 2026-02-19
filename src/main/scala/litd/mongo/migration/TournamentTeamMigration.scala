package litd.mongo.migration

import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.Filters.exists
import org.mongodb.scala.model.{IndexOptions, Indexes}
import org.mongodb.scala.model.Updates.{combine, set}

import java.util.Date
import scala.concurrent.{ExecutionContext, Future}

object TournamentTeamMigration extends Migration {
  override val version: Int = 5
  override val description: String = "Add tournaments.teamId with backfill and create teamId index"

  override def up(database: MongoDatabase)(implicit ec: ExecutionContext): Future[Unit] = {
    val tournaments = database.getCollection("tournaments")

    for {
      _ <- backfillTournamentTeamId(tournaments)
      _ <- tournaments
        .createIndex(Indexes.ascending("teamId"), IndexOptions().background(true))
        .toFuture()
        .map(_ => ())
    } yield ()
  }

  private def backfillTournamentTeamId(
      tournaments: org.mongodb.scala.MongoCollection[Document]
  )(implicit ec: ExecutionContext): Future[Unit] =
    tournaments
      .find(exists("teamId", exists = false))
      .toFuture()
      .flatMap { documents =>
        Future
          .traverse(documents) { document =>
            val id = document.getObjectId("_id")
            if (id == null) {
              Future.failed(new IllegalStateException("Cannot backfill tournament.teamId for document without _id"))
            } else {
              tournaments
                .updateOne(
                  org.mongodb.scala.model.Filters.equal("_id", id),
                  combine(
                    set("teamId", ""),
                    set("updatedAt", new Date())
                  )
                )
                .toFuture()
                .map(_ => ())
            }
          }
          .map(_ => ())
      }
}
