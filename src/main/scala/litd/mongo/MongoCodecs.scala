package litd.mongo

import litd.domain._
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala.MongoClient
import org.mongodb.scala.bson.codecs.Macros.createCodecProviderIgnoreNone

object MongoCodecs {
  val registry: CodecRegistry =
    fromRegistries(
      MongoClient.DEFAULT_CODEC_REGISTRY,
      fromProviders(
        createCodecProviderIgnoreNone[TournamentDocument](),
        createCodecProviderIgnoreNone[RegistrationDocument](),
        createCodecProviderIgnoreNone[RoundDocument](),
        createCodecProviderIgnoreNone[PairingDocument](),
        createCodecProviderIgnoreNone[ByeDocument](),
        createCodecProviderIgnoreNone[TiebreaksDocument](),
        createCodecProviderIgnoreNone[PlayerTournamentStateDocument](),
        createCodecProviderIgnoreNone[OverrideDocument](),
        createCodecProviderIgnoreNone[AuditEventDocument](),
        createCodecProviderIgnoreNone[OAuthTokenDocument](),
        createCodecProviderIgnoreNone[TeamMembershipCacheDocument](),
        createCodecProviderIgnoreNone[AppliedMigrationDocument]()
      )
    )
}

final case class AppliedMigrationDocument(
    _id: Option[org.bson.types.ObjectId] = None,
    version: Int,
    description: String,
    appliedAt: java.util.Date
)

object MongoDatabaseFactory {
  def withCodecRegistry(client: MongoClient, databaseName: String): org.mongodb.scala.MongoDatabase =
    client.getDatabase(databaseName).withCodecRegistry(MongoCodecs.registry)
}
