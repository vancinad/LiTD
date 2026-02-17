package litd.tournament

import litd.domain.RoundDocument
import litd.mongo.MongoDatabaseFactory
import litd.mongo.migration.MigrationRunner
import litd.mongo.repository.Repositories
import org.bson.types.ObjectId
import org.mongodb.scala.MongoClient
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.Assertions.fail
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName

import java.util.Date
import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

final class TournamentServiceIntegrationSpec
    extends AnyFunSuite
    with Matchers
    with BeforeAndAfterAll {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private val integrationEnabled: Boolean =
    sys.env.get("LITD_RUN_INTEGRATION_TESTS").exists(_.equalsIgnoreCase("true"))

  private var mongoContainer: Option[MongoDBContainer] = None
  private var mongoClient: Option[MongoClient] = None
  private var dockerAvailable: Boolean = false

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    if (integrationEnabled) {
      dockerAvailable = DockerClientFactory.instance().isDockerAvailable
    }
    if (integrationEnabled && dockerAvailable) {
      val container = new MongoDBContainer(DockerImageName.parse("mongo:7.0"))
      container.start()
      mongoContainer = Some(container)
      mongoClient = Some(MongoClient(container.getReplicaSetUrl))
    }
  }

  override protected def afterAll(): Unit = {
    mongoClient.foreach(_.close())
    mongoContainer.foreach(_.stop())
    super.afterAll()
  }

  test("register player uses effectiveRound 1 when no rounds exist") {
    requireDocker()
    val (service, _) = freshServiceContext()

    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("No rounds", 5)))
    val tournamentId = new ObjectId(createdTournament.id)

    val registration = awaitDomain(service.registerPlayer(tournamentId, "player-a"))
    registration.effectiveRound shouldBe 1
    registration.status shouldBe RegistrationStatus.Registered
  }

  test("register player after rounds exist uses latestRound + 1") {
    requireDocker()
    val (service, repositories) = freshServiceContext()

    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Late registration", 7)))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitFuture(
      repositories.rounds.insert(
        RoundDocument(
          tournamentId = tournamentId,
          roundNumber = 1,
          status = "completed",
          createdAt = new Date(),
          completedAt = Some(new Date())
        )
      )
    )
    awaitFuture(
      repositories.rounds.insert(
        RoundDocument(
          tournamentId = tournamentId,
          roundNumber = 2,
          status = "active",
          createdAt = new Date(),
          completedAt = None
        )
      )
    )

    val registration = awaitDomain(service.registerPlayer(tournamentId, "player-b"))
    registration.effectiveRound shouldBe 3
  }

  test("withdraw and reactivate update status and effectiveRound from next round") {
    requireDocker()
    val (service, repositories) = freshServiceContext()

    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Status transitions", 6)))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitFuture(
      repositories.rounds.insert(
        RoundDocument(
          tournamentId = tournamentId,
          roundNumber = 3,
          status = "active",
          createdAt = new Date(),
          completedAt = None
        )
      )
    )

    val initial = awaitDomain(service.registerPlayer(tournamentId, "player-c"))
    initial.effectiveRound shouldBe 4
    initial.status shouldBe RegistrationStatus.Registered

    val withdrawn = awaitDomain(service.withdrawPlayer(tournamentId, "player-c"))
    withdrawn.status shouldBe RegistrationStatus.Withdrawn
    withdrawn.effectiveRound shouldBe 4

    awaitFuture(
      repositories.rounds.insert(
        RoundDocument(
          tournamentId = tournamentId,
          roundNumber = 4,
          status = "pending",
          createdAt = new Date(),
          completedAt = None
        )
      )
    )

    val reactivated = awaitDomain(service.reactivatePlayer(tournamentId, "player-c"))
    reactivated.status shouldBe RegistrationStatus.Registered
    reactivated.effectiveRound shouldBe 5
  }

  private def requireDocker(): Unit =
    if (!integrationEnabled) {
      cancel("Integration tests disabled; set LITD_RUN_INTEGRATION_TESTS=true to enable")
    } else if (!dockerAvailable) {
      cancel("Docker is not available; skipping integration test")
    }

  private def freshServiceContext(): (TournamentService, Repositories) = {
    val client = mongoClient.getOrElse(fail("Mongo client is not initialized"))
    val dbName = s"litd_integration_${UUID.randomUUID().toString.replace('-', '_')}"
    val database = MongoDatabaseFactory.withCodecRegistry(client, dbName)
    awaitFuture(MigrationRunner.default(database).run())
    val repositories = Repositories.from(database)
    val service = new TournamentService(repositories.tournaments, repositories.registrations, repositories.rounds)
    (service, repositories)
  }

  private def awaitDomain[T](future: Future[Either[TournamentError, T]]): T = {
    val value = awaitFuture(future)
    value match {
      case Right(result) => result
      case Left(error)   => fail(s"Unexpected error: ${error.message}")
    }
  }

  private def awaitFuture[T](future: Future[T]): T = Await.result(future, 15.seconds)
}
