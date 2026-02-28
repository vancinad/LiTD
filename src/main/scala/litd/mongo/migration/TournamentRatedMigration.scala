package litd.mongo.migration

import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.model.Filters.exists
import org.mongodb.scala.model.Updates.{combine, set}

import java.util.Date
import scala.concurrent.{ExecutionContext, Future}

object TournamentRatedMigration extends Migration {
  override val version: Int = 7
  override val description: String = "Backfill tournaments.rated flag with unrated default"

  override def up(database: MongoDatabase)(implicit ec: ExecutionContext): Future[Unit] = {
    val tournaments = database.getCollection("tournaments")
    tournaments
      .find(exists("rated", exists = false))
      .toFuture()
      .flatMap { documents =>
        Future
          .traverse(documents) { document =>
            val id = document.getObjectId("_id")
            if (id == null) {
              Future.failed(new IllegalStateException("Cannot backfill tournament rated flag for document without _id"))
            } else {
              tournaments
                .updateOne(
                  org.mongodb.scala.model.Filters.equal("_id", id),
                  combine(
                    set("rated", false),
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
