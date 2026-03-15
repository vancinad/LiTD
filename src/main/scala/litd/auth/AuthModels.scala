package litd.auth

import akka.http.scaladsl.model.StatusCodes

import java.time.Instant

final case class LichessAuthConfig(
    baseUrl: String,
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    scope: String,
    requestTimeoutMillis: Int,
    retryCount: Int
)

final case class SessionConfig(
    cookieName: String,
    secureCookie: Boolean,
    maxAgeSeconds: Int
)

final case class AuthConfig(
    encryptionKeyBase64: String,
    stateTtlSeconds: Int,
    membershipCacheTtlSeconds: Int,
    lichess: LichessAuthConfig,
    session: SessionConfig
)

final case class OAuthTokenResponse(
    accessToken: String,
    tokenType: String,
    scope: String,
    expiresInSeconds: Option[Int]
)

final case class LichessChallengeResponse(
    challengeId: String,
    status: String,
    challengerColor: String
)

final case class AuthenticatedUser(
    lichessUserId: String,
    accessToken: String
)

final case class LichessTeamView(
    id: String,
    name: String
)

sealed trait AuthError extends Product with Serializable {
  def status: akka.http.scaladsl.model.StatusCode
  def message: String
}

object AuthError {
  final case class BadRequest(message: String) extends AuthError {
    override val status: akka.http.scaladsl.model.StatusCode = StatusCodes.BadRequest
  }
  final case class Unauthorized(message: String) extends AuthError {
    override val status: akka.http.scaladsl.model.StatusCode = StatusCodes.Unauthorized
  }
  final case class Forbidden(message: String) extends AuthError {
    override val status: akka.http.scaladsl.model.StatusCode = StatusCodes.Forbidden
  }
  final case class External(message: String) extends AuthError {
    override val status: akka.http.scaladsl.model.StatusCode = StatusCodes.BadGateway
  }
}

final case class OAuthCallbackResult(
    sessionToken: String,
    lichessUserId: String,
    expiresAt: Option[Instant]
)
