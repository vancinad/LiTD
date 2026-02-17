package litd

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import com.typesafe.config.{Config, ConfigFactory}
import litd.auth.{
  AuthConfig,
  AuthRoutes,
  AuthService,
  CryptoService,
  LichessApiClient,
  LichessAuthConfig,
  OAuthStateStore,
  SessionConfig
}
import litd.mongo.MongoDatabaseFactory
import litd.mongo.migration.MigrationRunner
import litd.mongo.repository.Repositories
import litd.tournament.{TournamentRoutes, TournamentService}
import org.mongodb.scala.MongoClient

import scala.io.StdIn
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.control.NonFatal

final case class HttpConfig(host: String, port: Int)
final case class MongoConfig(uri: String, database: String)
final case class AppConfig(http: HttpConfig, mongodb: MongoConfig, auth: AuthConfig)

object AppConfigLoader {
  def load(config: Config = ConfigFactory.load()): AppConfig = {
    val httpConfig = config.getConfig("litd.http")
    val mongoConfig = config.getConfig("litd.mongodb")
    val authConfig = config.getConfig("litd.auth")
    val lichessConfig = authConfig.getConfig("lichess")
    val sessionConfig = authConfig.getConfig("session")
    AppConfig(
      http = HttpConfig(
        host = httpConfig.getString("host"),
        port = httpConfig.getInt("port")
      ),
      mongodb = MongoConfig(
        uri = mongoConfig.getString("uri"),
        database = mongoConfig.getString("database")
      ),
      auth = AuthConfig(
        encryptionKeyBase64 = authConfig.getString("encryptionKeyBase64"),
        stateTtlSeconds = authConfig.getInt("stateTtlSeconds"),
        membershipCacheTtlSeconds = authConfig.getInt("membershipCacheTtlSeconds"),
        lichess = LichessAuthConfig(
          baseUrl = lichessConfig.getString("baseUrl"),
          clientId = lichessConfig.getString("clientId"),
          clientSecret = lichessConfig.getString("clientSecret"),
          redirectUri = lichessConfig.getString("redirectUri"),
          scope = lichessConfig.getString("scope"),
          teamId = lichessConfig.getString("teamId"),
          requestTimeoutMillis = lichessConfig.getInt("requestTimeoutMillis"),
          retryCount = lichessConfig.getInt("retryCount")
        ),
        session = SessionConfig(
          cookieName = sessionConfig.getString("cookieName"),
          secureCookie = sessionConfig.getBoolean("secureCookie"),
          maxAgeSeconds = sessionConfig.getInt("maxAgeSeconds")
        )
      )
    )
  }
}

object MongoClientFactory {
  def create(mongoConfig: MongoConfig): MongoClient = MongoClient(mongoConfig.uri)
}

object MainObject {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "litd")
    implicit val ec: ExecutionContext = system.executionContext

    val appConfig = AppConfigLoader.load()
    val mongoClient = MongoClientFactory.create(appConfig.mongodb)
    val mongoDatabase = MongoDatabaseFactory.withCodecRegistry(mongoClient, appConfig.mongodb.database)
    val migrationRunner = MigrationRunner.default(mongoDatabase)

    Await.result(migrationRunner.run(), 30.seconds)
    val repositories = Repositories.from(mongoDatabase)
    system.log.info("Mongo initialized")

    val cryptoService = new CryptoService(appConfig.auth.encryptionKeyBase64)
    val oauthStateStore = new OAuthStateStore(appConfig.auth.stateTtlSeconds)
    val lichessApiClient = new LichessApiClient(appConfig.auth.lichess)
    val authService = new AuthService(
      config = appConfig.auth,
      cryptoService = cryptoService,
      oauthStateStore = oauthStateStore,
      lichessApiClient = lichessApiClient,
      oauthTokenRepository = repositories.oauthTokens,
      teamMembershipCacheRepository = repositories.teamMembershipCache
    )
    val authRoutes = new AuthRoutes(appConfig.auth, authService)
    val tournamentService = new TournamentService(
      tournamentRepository = repositories.tournaments,
      registrationRepository = repositories.registrations,
      roundRepository = repositories.rounds
    )
    val tournamentRoutes = new TournamentRoutes(appConfig.auth, authService, tournamentService)

    /** API endpoint: GET /health returns plain "ok" for basic liveness checks. */
    val healthRoute = path("health") {
      get {
        complete(StatusCodes.OK -> "ok")
      }
    }
    val routes = healthRoute ~ authRoutes.routes ~ tournamentRoutes.routes

    val binding = Await.result(
      Http()
        .newServerAt(appConfig.http.host, appConfig.http.port)
        .bind(routes),
      30.seconds
    )
    system.log.info("Server running at {}", binding.localAddress)

    try {
      StdIn.readLine()
    } catch {
      case NonFatal(ex) =>
        system.log.error("Read from stdin failed", ex)
    } finally {
      Await.result(binding.unbind(), 15.seconds)
      mongoClient.close()
      system.terminate()
      Await.result(system.whenTerminated, 15.seconds)
    }
  }
}
