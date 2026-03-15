package litd.tournament

import litd.auth.{AuthenticatedUser, CryptoService}
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
  private val testEncryptionKeyBase64: String = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

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
    val (service, _, _, _) = freshServiceContext()

    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("No rounds", 5, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)
    createdTournament.rated shouldBe true
    createdTournament.tournamentDirectorLichessUserId shouldBe "td-user"

    val registration = awaitDomain(service.registerPlayer(tournamentId, "player-a"))
    registration.effectiveRound shouldBe 1
    registration.status shouldBe RegistrationStatus.Registered
  }

  test("register player after rounds exist uses latestRound + 1") {
    requireDocker()
    val (service, repositories, _, _) = freshServiceContext()

    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Late registration", 7, "team-one", 180, 2, rated = true), "td-user"))
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
    val (service, repositories, _, _) = freshServiceContext()

    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Status transitions", 6, "team-one", 180, 2, rated = true), "td-user"))
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

  test("generate first round computes effectiveMaxRounds and creates pairings plus odd bye") {
    requireDocker()
    val (service, _, _, _) = freshServiceContext()
    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Round gen", 6, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitDomain(service.registerPlayer(tournamentId, "alpha"))
    awaitDomain(service.registerPlayer(tournamentId, "beta"))
    awaitDomain(service.registerPlayer(tournamentId, "gamma"))

    val generated = awaitDomain(service.generateNextRound(tournamentId, GenerateRoundRequest()))
    generated.roundNumber shouldBe 1
    generated.effectiveMaxRounds shouldBe 2
    generated.pairings.size shouldBe 1
    generated.byes.count(_.reason == TournamentRules.ByeReasonOdd) shouldBe 1
  }

  test("td-granted byes are stored during generation and explicit endpoint validates conflicts") {
    requireDocker()
    val (service, _, _, _) = freshServiceContext()
    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("TD byes", 6, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitDomain(service.registerPlayer(tournamentId, "p1"))
    awaitDomain(service.registerPlayer(tournamentId, "p2"))
    awaitDomain(service.registerPlayer(tournamentId, "p3"))
    awaitDomain(service.registerPlayer(tournamentId, "p4"))

    val generated = awaitDomain(
      service.generateNextRound(
        tournamentId,
        GenerateRoundRequest(tdByes = Seq(GrantTdByeRequest("p1", 0.5d)))
      )
    )
    generated.byes.exists(bye => bye.lichessUserId == "p1" && bye.reason == TournamentRules.ByeReasonTdGrant) shouldBe true

    val tdByeAttempt = awaitFuture(
      service.grantTdBye(
        tournamentId = tournamentId,
        roundNumber = 1,
        request = GrantTdByeRequest("p2", 0.5d)
      )
    )
    tdByeAttempt.isLeft shouldBe true
  }

  test("issue challenge persists challengeId on pairing") {
    requireDocker()
    val gateway = new FakeChallengeGateway(
      issueResponse = Right(IssuedChallenge("challenge-123", "created"))
    )
    val (service, repositories, _, _) = freshServiceContext(challengeGateway = gateway)
    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Challenge issuance", 4, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitDomain(service.registerPlayer(tournamentId, "white-player"))
    awaitDomain(service.registerPlayer(tournamentId, "black-player"))
    awaitDomain(service.generateNextRound(tournamentId, GenerateRoundRequest()))

    val pairing = awaitFuture(repositories.pairings.listByTournament(tournamentId)).head
    val issued = awaitDomain(
      service.issueChallenge(
        tournamentId = tournamentId,
        pairingId = pairing._id.get,
        user = AuthenticatedUser("white-player", "token-a")
      )
    )

    issued.challengeId shouldBe "challenge-123"
    gateway.requestedOpponent shouldBe Some("black-player")
    gateway.requestedChallengerColor shouldBe Some("white")
    val persisted = awaitFuture(repositories.pairings.findById(pairing._id.get))
    persisted.flatMap(_.challengeId) shouldBe Some("challenge-123")
  }

  test("issue challenge is idempotent when pairing already has challengeId") {
    requireDocker()
    val gateway = new FakeChallengeGateway(
      issueResponse = Right(IssuedChallenge("challenge-777", "created"))
    )
    val (service, repositories, _, _) = freshServiceContext(challengeGateway = gateway)
    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Challenge idempotency", 4, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitDomain(service.registerPlayer(tournamentId, "white-player"))
    awaitDomain(service.registerPlayer(tournamentId, "black-player"))
    awaitDomain(service.generateNextRound(tournamentId, GenerateRoundRequest()))

    val pairing = awaitFuture(repositories.pairings.listByTournament(tournamentId)).head
    val firstIssue = awaitDomain(
      service.issueChallenge(
        tournamentId = tournamentId,
        pairingId = pairing._id.get,
        user = AuthenticatedUser("white-player", "token-a")
      )
    )
    firstIssue.status shouldBe "created"

    val secondIssue = awaitDomain(
      service.issueChallenge(
        tournamentId = tournamentId,
        pairingId = pairing._id.get,
        user = AuthenticatedUser("white-player", "token-a")
      )
    )
    secondIssue.challengeId shouldBe firstIssue.challengeId
    secondIssue.status shouldBe "already_issued"
  }

  test("tournament hub refresh updates unresolved pairing results from bulk game export") {
    requireDocker()
    val gateway = new FakeChallengeGateway(
      issueResponse = Right(IssuedChallenge("challenge-321", "created")),
      gameResultsResponse = Right(Map("game-321" -> TournamentRules.ResultDraw))
    )
    val (service, repositories, _, _) = freshServiceContext(challengeGateway = gateway)
    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Refresh results", 4, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitDomain(service.registerPlayer(tournamentId, "white-player"))
    awaitDomain(service.registerPlayer(tournamentId, "black-player"))
    awaitDomain(service.generateNextRound(tournamentId, GenerateRoundRequest()))

    val pairing = awaitFuture(repositories.pairings.listByTournament(tournamentId)).head
    awaitFuture(repositories.pairings.setGameStarted(pairing._id.get, "game-321", new Date()))

    awaitDomain(service.getTournamentHub(tournamentId, refreshResults = true))
    val updated = awaitFuture(repositories.pairings.findById(pairing._id.get)).getOrElse(
      fail("Expected pairing to exist after tournament hub refresh")
    )
    updated.result shouldBe Some(TournamentRules.ResultDraw)
    updated.isOfficial shouldBe false
  }

  test("end round applies double-forfeit and completes round") {
    requireDocker()
    val (service, repositories, _, _) = freshServiceContext()
    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("End round", 4, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitDomain(service.registerPlayer(tournamentId, "white-player"))
    awaitDomain(service.registerPlayer(tournamentId, "black-player"))
    awaitDomain(service.generateNextRound(tournamentId, GenerateRoundRequest()))

    val ended = awaitDomain(service.endRound(tournamentId, roundNumber = 1))
    ended.doubleForfeitCount shouldBe 1
    ended.roundStatus shouldBe "completed"

    val pairing = awaitFuture(repositories.pairings.listByTournament(tournamentId)).head
    pairing.result shouldBe Some(TournamentRules.ResultForfeit)
    pairing.isOfficial shouldBe true

    val round = awaitFuture(repositories.rounds.findByTournamentAndRoundNumber(tournamentId, 1)).getOrElse(
      fail("Expected round 1 to exist after end round")
    )
    round.status shouldBe "completed"
    round.completedAt.nonEmpty shouldBe true
  }

  test("override result stores override history and recomputes player state") {
    requireDocker()
    val (service, repositories, _, _) = freshServiceContext()
    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Override result", 4, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitDomain(service.registerPlayer(tournamentId, "white-player"))
    awaitDomain(service.registerPlayer(tournamentId, "black-player"))
    awaitDomain(service.generateNextRound(tournamentId, GenerateRoundRequest()))

    val pairing = awaitFuture(repositories.pairings.listByTournament(tournamentId)).head
    val overridden = awaitDomain(
      service.overridePairingResult(
        tournamentId = tournamentId,
        pairingId = pairing._id.get,
        request = OverridePairingResultRequest(result = "draw", reason = "agreed draw"),
        user = AuthenticatedUser("td-user", "token-a")
      )
    )

    overridden.result shouldBe TournamentRules.ResultDraw
    val persistedPairing = awaitFuture(repositories.pairings.findById(pairing._id.get)).getOrElse(
      fail("Expected pairing after override")
    )
    persistedPairing.result shouldBe Some(TournamentRules.ResultDraw)
    persistedPairing.isOfficial shouldBe true

    val overrides = awaitFuture(repositories.overrides.list())
    overrides.exists(ov => ov.pairingId == pairing._id.get && ov.reason == "agreed draw" && ov.appliedBy == "td-user") shouldBe true

    val states = awaitFuture(repositories.playerTournamentState.list())
      .filter(_.tournamentId == tournamentId)
      .sortBy(_.lichessUserId)
    states.map(_.points) shouldBe Seq(0.5d, 0.5d)
  }

  test("standings read model returns ranked entries with computed tiebreaks") {
    requireDocker()
    val (service, repositories, _, _) = freshServiceContext()
    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Standings read model", 5, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitDomain(service.registerPlayer(tournamentId, "a"))
    awaitDomain(service.registerPlayer(tournamentId, "b"))
    awaitDomain(service.registerPlayer(tournamentId, "c"))
    awaitDomain(service.registerPlayer(tournamentId, "d"))

    awaitDomain(service.generateNextRound(tournamentId, GenerateRoundRequest()))
    val roundOnePairings = awaitFuture(repositories.pairings.listByTournament(tournamentId)).sortBy(_.whiteLichessUserId)
    val roundOneAb = roundOnePairings.find(p => p.whiteLichessUserId == "a" && p.blackLichessUserId == "b").getOrElse(
      fail("Expected round 1 pairing a vs b")
    )
    val roundOneCd = roundOnePairings.find(p => p.whiteLichessUserId == "c" && p.blackLichessUserId == "d").getOrElse(
      fail("Expected round 1 pairing c vs d")
    )
    awaitDomain(
      service.overridePairingResult(
        tournamentId,
        roundOneAb._id.get,
        OverridePairingResultRequest("white", "round 1 result"),
        AuthenticatedUser("td-user", "token-a")
      )
    )
    awaitDomain(
      service.overridePairingResult(
        tournamentId,
        roundOneCd._id.get,
        OverridePairingResultRequest("draw", "round 1 result"),
        AuthenticatedUser("td-user", "token-a")
      )
    )
    awaitDomain(service.endRound(tournamentId, 1))

    awaitDomain(service.generateNextRound(tournamentId, GenerateRoundRequest()))
    val allPairings = awaitFuture(repositories.pairings.listByTournament(tournamentId))
    val roundTwoPairings = allPairings.filter(_.roundNumber == 2)
    val roundTwoAc = roundTwoPairings.find(p => p.whiteLichessUserId == "a" && p.blackLichessUserId == "c").getOrElse(
      fail("Expected round 2 pairing a vs c")
    )
    val roundTwoBd = roundTwoPairings.find(p => p.whiteLichessUserId == "b" && p.blackLichessUserId == "d").getOrElse(
      fail("Expected round 2 pairing b vs d")
    )
    awaitDomain(
      service.overridePairingResult(
        tournamentId,
        roundTwoAc._id.get,
        OverridePairingResultRequest("draw", "round 2 result"),
        AuthenticatedUser("td-user", "token-a")
      )
    )
    awaitDomain(
      service.overridePairingResult(
        tournamentId,
        roundTwoBd._id.get,
        OverridePairingResultRequest("black", "round 2 result"),
        AuthenticatedUser("td-user", "token-a")
      )
    )
    awaitDomain(service.endRound(tournamentId, 2))

    val standings = awaitDomain(service.getStandings(tournamentId))
    standings.roundCount shouldBe 2
    standings.entries.map(_.lichessUserId) shouldBe Seq("a", "d", "c", "b")
    standings.entries.map(_.rank) shouldBe Seq(1, 1, 3, 4)
    standings.entries.head.points shouldBe 1.5d
    standings.entries.head.buchholz shouldBe 1.0d
    standings.entries.head.sonnebornBerger shouldBe 0.5d
  }

  test("standings read model includes registered players before results are posted") {
    requireDocker()
    val (service, _, _, _) = freshServiceContext()
    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Standings pre-results", 5, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitDomain(service.registerPlayer(tournamentId, "alpha"))
    awaitDomain(service.registerPlayer(tournamentId, "beta"))
    awaitDomain(service.registerPlayer(tournamentId, "gamma"))

    val standings = awaitDomain(service.getStandings(tournamentId))
    standings.roundCount shouldBe 0
    standings.entries.map(_.lichessUserId) shouldBe Seq("alpha", "beta", "gamma")
    all(standings.entries.map(_.points)) shouldBe 0d
    all(standings.entries.map(_.gamesPlayed)) shouldBe 0
    all(standings.entries.map(_.buchholz)) shouldBe 0d
    all(standings.entries.map(_.sonnebornBerger)) shouldBe 0d
    standings.entries.map(_.rank) shouldBe Seq(1, 1, 1)
  }

  test("crosstable read model returns per-player games and byes") {
    requireDocker()
    val (service, repositories, _, _) = freshServiceContext()
    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Crosstable read model", 4, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitDomain(service.registerPlayer(tournamentId, "alpha"))
    awaitDomain(service.registerPlayer(tournamentId, "beta"))
    awaitDomain(service.registerPlayer(tournamentId, "gamma"))

    awaitDomain(service.generateNextRound(tournamentId, GenerateRoundRequest()))
    val pairing = awaitFuture(repositories.pairings.listByTournament(tournamentId)).headOption.getOrElse(
      fail("Expected one pairing for three-player round")
    )
    awaitDomain(
      service.overridePairingResult(
        tournamentId,
        pairing._id.get,
        OverridePairingResultRequest("draw", "record result"),
        AuthenticatedUser("td-user", "token-a")
      )
    )
    awaitDomain(service.endRound(tournamentId, 1))

    val crosstable = awaitDomain(service.getCrosstable(tournamentId))
    crosstable.roundCount shouldBe 1
    crosstable.rows.map(_.lichessUserId) shouldBe Seq("alpha", "beta", "gamma")

    val alphaRow = crosstable.rows.find(_.lichessUserId == "alpha").getOrElse(fail("alpha row missing"))
    alphaRow.points shouldBe 1.0d
    alphaRow.games shouldBe empty
    alphaRow.byes.map(_.reason) shouldBe Seq(TournamentRules.ByeReasonOdd)

    val betaRow = crosstable.rows.find(_.lichessUserId == "beta").getOrElse(fail("beta row missing"))
    betaRow.games.size shouldBe 1
    betaRow.games.head.opponentLichessUserId shouldBe "gamma"
    betaRow.games.head.result shouldBe TournamentRules.ResultDraw
    betaRow.games.head.score shouldBe 0.5d
  }

  test("crosstable read model includes registered players before results are posted") {
    requireDocker()
    val (service, _, _, _) = freshServiceContext()
    val createdTournament = awaitDomain(service.createTournament(CreateTournamentRequest("Crosstable pre-results", 4, "team-one", 180, 2, rated = true), "td-user"))
    val tournamentId = new ObjectId(createdTournament.id)

    awaitDomain(service.registerPlayer(tournamentId, "alpha"))
    awaitDomain(service.registerPlayer(tournamentId, "beta"))
    awaitDomain(service.registerPlayer(tournamentId, "gamma"))

    val crosstable = awaitDomain(service.getCrosstable(tournamentId))
    crosstable.roundCount shouldBe 0
    crosstable.rows.map(_.lichessUserId) shouldBe Seq("alpha", "beta", "gamma")
    all(crosstable.rows.map(_.points)) shouldBe 0d
    all(crosstable.rows.map(_.gamesPlayed)) shouldBe 0
    all(crosstable.rows.map(_.games)) shouldBe empty
    all(crosstable.rows.map(_.byes)) shouldBe empty
  }

  private def requireDocker(): Unit =
    if (!integrationEnabled) {
      cancel("Integration tests disabled; set LITD_RUN_INTEGRATION_TESTS=true to enable")
    } else if (!dockerAvailable) {
      cancel("Docker is not available; skipping integration test")
    }

  private def freshServiceContext(
      challengeGateway: ChallengeGateway = ChallengeGateway.Disabled
  ): (TournamentService, Repositories, CryptoService, MongoClient) = {
    val client = mongoClient.getOrElse(fail("Mongo client is not initialized"))
    val dbName = s"litd_integration_${UUID.randomUUID().toString.replace('-', '_')}"
    val database = MongoDatabaseFactory.withCodecRegistry(client, dbName)
    awaitFuture(MigrationRunner.default(database).run())
    val repositories = Repositories.from(database)
    val cryptoService = new CryptoService(testEncryptionKeyBase64)
    val service = new TournamentService(
      repositories.tournaments,
      repositories.registrations,
      repositories.rounds,
      repositories.pairings,
      repositories.byes,
      repositories.playerTournamentState,
      repositories.overrides,
      repositories.auditEvents,
      client,
      challengeGateway
    )
    (service, repositories, cryptoService, client)
  }

  private def awaitDomain[T](future: Future[Either[TournamentError, T]]): T = {
    val value = awaitFuture(future)
    value match {
      case Right(result) => result
      case Left(error)   => fail(s"Unexpected error: ${error.message}")
    }
  }

  private def awaitFuture[T](future: Future[T]): T = Await.result(future, 15.seconds)

  private final class FakeChallengeGateway(
      issueResponse: Either[String, IssuedChallenge],
      gameResultsResponse: Either[String, Map[String, String]] = Right(Map.empty)
  ) extends ChallengeGateway {
    var requestedOpponent: Option[String] = None
    var requestedChallengerColor: Option[String] = None

    override def issueChallenge(
        opponentLichessUserId: String,
        accessToken: String,
        initialSeconds: Int,
        incrementSeconds: Int,
        challengerColor: String
    ): Future[Either[String, IssuedChallenge]] = {
      requestedOpponent = Some(opponentLichessUserId)
      requestedChallengerColor = Some(challengerColor)
      Future.successful(issueResponse)
    }

    override def lookupGameResults(gameIds: Seq[String]): Future[Either[String, Map[String, String]]] =
      Future.successful(gameResultsResponse.map(_.filter { case (gameId, _) => gameIds.contains(gameId) }))
  }
}
