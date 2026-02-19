package litd.auth

import litd.auth.AuthError.{BadRequest, External, Forbidden, Unauthorized}
import litd.domain.{OAuthTokenDocument, TeamMembershipCacheDocument}
import litd.mongo.repository.{OAuthTokenRepository, TeamMembershipCacheRepository}

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.Base64
import java.util.Date
import scala.concurrent.{ExecutionContext, Future}

final class AuthService(
    config: AuthConfig,
    cryptoService: CryptoService,
    oauthStateStore: OAuthStateStore,
    lichessApiClient: LichessApiClient,
    oauthTokenRepository: OAuthTokenRepository,
    teamMembershipCacheRepository: TeamMembershipCacheRepository
)(implicit ec: ExecutionContext) {
  private val secureRandom = new SecureRandom()

  def startOAuth(): String = {
    val codeVerifier = generateCodeVerifier()
    val state = oauthStateStore.issueState(codeVerifier)
    val codeChallenge = codeChallengeFromVerifier(codeVerifier)
    lichessApiClient.authorizeUrl(state, codeChallenge)
  }

  def finishOAuth(code: String, state: String): Future[Either[AuthError, OAuthCallbackResult]] = {
    oauthStateStore.consumeState(state) match {
      case None =>
        Future.successful(Left(BadRequest("OAuth state is invalid or expired")))
      case Some(codeVerifier) =>
        for {
          token <- lichessApiClient.exchangeCodeForToken(code, codeVerifier)
          userId <- lichessApiClient.currentUserId(token.accessToken)
          membership <- checkMembership(userId, token.accessToken)
          result <- if (!membership) {
            Future.successful(Left(Forbidden(s"User '$userId' is not in required team '${config.lichess.teamId}'")))
          } else {
            persistToken(userId, token).map(Right(_))
          }
        } yield result
    }
  }

  def authenticate(sessionToken: String): Future[Either[AuthError, AuthenticatedUser]] = {
    val sessionTokenHash = cryptoService.sha256Hex(sessionToken)
    oauthTokenRepository.findBySessionTokenHash(sessionTokenHash).flatMap {
      case None => Future.successful(Left(Unauthorized("Session is invalid")))
      case Some(tokenDoc) =>
        decodeToken(tokenDoc).flatMap {
          case Left(err) => Future.successful(Left(err))
          case Right(accessToken) =>
            checkMembership(tokenDoc.lichessUserId, accessToken).map {
              case true  => Right(AuthenticatedUser(tokenDoc.lichessUserId, accessToken))
              case false => Left(Forbidden(s"User '${tokenDoc.lichessUserId}' is not in required team"))
            }
        }
    }
  }

  private def decodeToken(tokenDoc: OAuthTokenDocument): Future[Either[AuthError, String]] =
    Future(cryptoService.decrypt(tokenDoc.encryptedAccessToken))
      .map(Right(_))
      .recover { case ex =>
        Left(External(s"Stored OAuth token cannot be decrypted: ${ex.getMessage}"))
      }

  private def persistToken(
      userId: String,
      tokenResponse: OAuthTokenResponse
  ): Future[OAuthCallbackResult] = {
    val now = Instant.now()
    val expiresAt = tokenResponse.expiresInSeconds.map(seconds => now.plusSeconds(seconds.toLong))
    val sessionToken = cryptoService.generateSessionToken()

    val document = OAuthTokenDocument(
      lichessUserId = userId,
      encryptedAccessToken = cryptoService.encrypt(tokenResponse.accessToken),
      tokenType = tokenResponse.tokenType,
      scope = tokenResponse.scope,
      expiresAt = expiresAt.map(Date.from),
      sessionTokenHash = cryptoService.sha256Hex(sessionToken),
      createdAt = Date.from(now),
      updatedAt = Date.from(now)
    )

    oauthTokenRepository
      .upsertByLichessUserId(document)
      .map(_ => OAuthCallbackResult(sessionToken, userId, expiresAt))
  }

  private def checkMembership(userId: String, accessToken: String): Future[Boolean] = {
    val now = Instant.now()
    teamMembershipCacheRepository.findByTeamAndUser(config.lichess.teamId, userId).flatMap {
      case Some(cached) if cached.expiresAt.toInstant.isAfter(now) => Future.successful(cached.isMember)
      case _ =>
        lichessApiClient
          .isTeamMember(userId, accessToken)
          .flatMap { isMember =>
            val expiresAt = now.plusSeconds(config.membershipCacheTtlSeconds.toLong)
            val cacheDoc = TeamMembershipCacheDocument(
              teamId = config.lichess.teamId,
              lichessUserId = userId,
              isMember = isMember,
              expiresAt = Date.from(expiresAt),
              updatedAt = Date.from(now)
            )
            teamMembershipCacheRepository.upsert(cacheDoc).map(_ => isMember)
          }
    }
  }

  private def generateCodeVerifier(): String = {
    val bytes = new Array[Byte](32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private def codeChallengeFromVerifier(codeVerifier: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII))
    Base64.getUrlEncoder.withoutPadding().encodeToString(hash)
  }
}
