package litd.mongo.migration

import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.Filters.{exists, lte, or}
import org.mongodb.scala.model.Updates.{combine, set}

import java.util.Date
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

object SchemaEvolutionBackfillMigration extends Migration {
  override val version: Int = 4
  override val description: String =
    "Backfill legacy schema fields (pairings.playerIds and tournaments.effectiveMaxRounds)"

  override def up(database: MongoDatabase)(implicit ec: ExecutionContext): Future[Unit] = {
    val pairings = database.getCollection("pairings")
    val tournaments = database.getCollection("tournaments")

    for {
      _ <- backfillPairingPlayerIds(pairings)
      _ <- backfillTournamentEffectiveMaxRounds(tournaments)
    } yield ()
  }

  private def backfillPairingPlayerIds(
      pairings: org.mongodb.scala.MongoCollection[Document]
  )(implicit ec: ExecutionContext): Future[Unit] =
    pairings
      .find(exists("playerIds", exists = false))
      .toFuture()
      .flatMap { documents =>
        Future
          .traverse(documents) { document =>
            val id = document.getObjectId("_id")
            val white = Option(document.getString("whiteLichessUserId")).map(_.trim).getOrElse("")
            val black = Option(document.getString("blackLichessUserId")).map(_.trim).getOrElse("")
            val playerIds = Seq(white, black).filter(_.nonEmpty).distinct.sorted

            if (id == null || playerIds.size != 2) {
              Future.failed(
                new IllegalStateException(s"Cannot backfill pairings.playerIds for document '${Option(id).map(_.toHexString).getOrElse("<missing-id>")}'")
              )
            } else {
              pairings
                .updateOne(
                  org.mongodb.scala.model.Filters.equal("_id", id),
                  set("playerIds", playerIds.asJava)
                )
                .toFuture()
                .map(_ => ())
            }
          }
          .map(_ => ())
      }

  private def backfillTournamentEffectiveMaxRounds(
      tournaments: org.mongodb.scala.MongoCollection[Document]
  )(implicit ec: ExecutionContext): Future[Unit] =
    tournaments
      .find(or(exists("effectiveMaxRounds", exists = false), lte("effectiveMaxRounds", 0)))
      .toFuture()
      .flatMap { documents =>
        Future
          .traverse(documents) { document =>
            val id = document.getObjectId("_id")
            val configured = Option(document.getInteger("configuredMaxRounds")).map(_.intValue()).getOrElse(1)
            val effectiveMaxRounds = math.max(1, configured)

            if (id == null) {
              Future.failed(new IllegalStateException("Cannot backfill tournament.effectiveMaxRounds for document without _id"))
            } else {
              tournaments
                .updateOne(
                  org.mongodb.scala.model.Filters.equal("_id", id),
                  combine(
                    set("effectiveMaxRounds", effectiveMaxRounds),
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
