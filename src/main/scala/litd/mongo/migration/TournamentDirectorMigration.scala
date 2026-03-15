package litd.mongo.migration

import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.model.Filters.exists
import org.mongodb.scala.model.Updates.{combine, set}

import java.util.Date
import scala.concurrent.{ExecutionContext, Future}

object TournamentDirectorMigration extends Migration {
  override val version: Int = 8
  override val description: String = "Backfill tournaments.tournamentDirectorLichessUserId for legacy tournaments"

  private val LegacyDirectorPlaceholder: String = "unknown"

  override def up(database: MongoDatabase)(implicit ec: ExecutionContext): Future[Unit] = {
    val tournaments = database.getCollection("tournaments")
    tournaments
      .find(exists("tournamentDirectorLichessUserId", exists = false))
      .toFuture()
      .flatMap { documents =>
        Future
          .traverse(documents) { document =>
            val id = document.getObjectId("_id")
            if (id == null) {
              Future.failed(new IllegalStateException("Cannot backfill tournament director for document without _id"))
            } else {
              tournaments
                .updateOne(
                  org.mongodb.scala.model.Filters.equal("_id", id),
                  combine(
                    set("tournamentDirectorLichessUserId", LegacyDirectorPlaceholder),
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
}
