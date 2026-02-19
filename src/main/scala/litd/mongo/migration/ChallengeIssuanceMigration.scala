package litd.mongo.migration

import litd.domain.PairingDocument
import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.model.{IndexOptions, Indexes}

import scala.concurrent.{ExecutionContext, Future}

object ChallengeIssuanceMigration extends Migration {
  override val version: Int = 3
  override val description: String = "Add pairing challenge tracking indexes"

  override def up(database: MongoDatabase)(implicit ec: ExecutionContext): Future[Unit] = {
    val pairings = database.getCollection[PairingDocument]("pairings")

    val ops = Seq(
      pairings
        .createIndex(
          Indexes.ascending("challengeId"),
          IndexOptions().sparse(true)
        )
        .toFuture(),
      pairings
        .createIndex(Indexes.ascending("challengeId", "gameId"))
        .toFuture()
    )

    Future.sequence(ops).map(_ => ())
  }
}
