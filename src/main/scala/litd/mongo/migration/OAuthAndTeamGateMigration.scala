package litd.mongo.migration

import litd.domain.{OAuthTokenDocument, TeamMembershipCacheDocument}
import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.model.{IndexOptions, Indexes}

import scala.concurrent.{ExecutionContext, Future}

object OAuthAndTeamGateMigration extends Migration {
  override val version: Int = 2
  override val description: String = "Create oauthTokens and teamMembershipCache indexes"

  override def up(database: MongoDatabase)(implicit ec: ExecutionContext): Future[Unit] = {
    val oauthTokens = database.getCollection[OAuthTokenDocument]("oauthTokens")
    val membershipCache = database.getCollection[TeamMembershipCacheDocument]("teamMembershipCache")

    val ops = Seq(
      oauthTokens
        .createIndex(Indexes.ascending("lichessUserId"), IndexOptions().unique(true))
        .toFuture(),
      oauthTokens
        .createIndex(Indexes.ascending("sessionTokenHash"), IndexOptions().unique(true))
        .toFuture(),
      oauthTokens
        .createIndex(Indexes.ascending("updatedAt"))
        .toFuture(),
      membershipCache
        .createIndex(
          Indexes.ascending("teamId", "lichessUserId"),
          IndexOptions().unique(true)
        )
        .toFuture(),
      membershipCache
        .createIndex(
          Indexes.ascending("expiresAt"),
          IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS)
        )
        .toFuture()
    )

    Future.sequence(ops).map(_ => ())
  }
}

