package litd.mongo.migration

import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.Filters.exists
import org.mongodb.scala.model.Updates.{combine, set}

import java.util.Date
import scala.concurrent.{ExecutionContext, Future}

object TournamentTimeControlMigration extends Migration {
  override val version: Int = 6
  override val description: String =
    "Backfill tournaments with time control fields (timeControlInitialSeconds/timeControlIncrementSeconds)"

  override def up(database: MongoDatabase)(implicit ec: ExecutionContext): Future[Unit] = {
    val tournaments = database.getCollection("tournaments")
    tournaments
      .find(
        org.mongodb.scala.model.Filters.or(
          exists("timeControlInitialSeconds", exists = false),
          exists("timeControlIncrementSeconds", exists = false)
        )
      )
      .toFuture()
      .flatMap { documents =>
        Future
          .traverse(documents) { document =>
            val id = document.getObjectId("_id")
            if (id == null) {
              Future.failed(new IllegalStateException("Cannot backfill tournament time control for document without _id"))
            } else {
              tournaments
                .updateOne(
                  org.mongodb.scala.model.Filters.equal("_id", id),
                  combine(
                    set("timeControlInitialSeconds", 180),
                    set("timeControlIncrementSeconds", 2),
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
