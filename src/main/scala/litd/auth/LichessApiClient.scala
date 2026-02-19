package litd.auth

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.pattern.after
import io.circe.Json
import io.circe.parser.parse

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

final class LichessApiClient(
    config: LichessAuthConfig
)(implicit system: ActorSystem[Nothing], ec: ExecutionContext) {
  private val classicSystem = system.toClassic
  private val requestTimeout: FiniteDuration = config.requestTimeoutMillis.millis

  def authorizeUrl(state: String): String = {
    val params = Seq(
      "response_type" -> "code",
      "client_id" -> config.clientId,
      "redirect_uri" -> config.redirectUri,
      "scope" -> config.scope,
      "state" -> state
    )
    s"${config.baseUrl}/oauth?${encodeParams(params)}"
  }

  def exchangeCodeForToken(code: String): Future[OAuthTokenResponse] = {
    val formData = FormData(
      "grant_type" -> "authorization_code",
      "code" -> code,
      "client_id" -> config.clientId,
      "client_secret" -> config.clientSecret,
      "redirect_uri" -> config.redirectUri
    )
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = s"${config.baseUrl}/api/token",
      entity = formData.toEntity
    )

    withRetries(request).flatMap { json =>
      Future.fromTry {
        for {
          token <- json.hcursor.get[String]("access_token").toTry
          tokenType = json.hcursor.get[String]("token_type").getOrElse("Bearer")
          scope = json.hcursor.get[String]("scope").getOrElse(config.scope)
          expires = json.hcursor.get[Int]("expires_in").toOption
        } yield OAuthTokenResponse(token, tokenType, scope, expires)
      }.recoverWith { case ex =>
        Future.failed(new RuntimeException(s"Failed to parse Lichess token response: ${ex.getMessage}", ex))
      }
    }
  }

  def currentUserId(accessToken: String): Future[String] = {
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = s"${config.baseUrl}/api/account",
      headers = List(headers.RawHeader("Authorization", s"Bearer $accessToken"))
    )
    withRetries(request).flatMap { json =>
      Future
        .fromTry(json.hcursor.get[String]("id").toTry)
        .recoverWith { case _ =>
          Future.fromTry(json.hcursor.get[String]("username").toTry)
        }
    }
  }

  def isTeamMember(userId: String, accessToken: String): Future[Boolean] = {
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = s"${config.baseUrl}/api/team/of/${urlEncode(userId)}",
      headers = List(headers.RawHeader("Authorization", s"Bearer $accessToken"))
    )
    withRetries(request).map { json =>
      json.asArray.exists(_.exists { team =>
        team.hcursor.get[String]("id").contains(config.teamId)
      })
    }
  }

  def issueChallenge(opponentUserId: String, accessToken: String): Future[LichessChallengeResponse] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = s"${config.baseUrl}/api/challenge/${urlEncode(opponentUserId)}",
      headers = List(headers.RawHeader("Authorization", s"Bearer $accessToken")),
      entity = FormData(Map.empty[String, String]).toEntity
    )
    withRetries(request).flatMap { json =>
      val cursor = json.hcursor
      val challengeCursor = cursor.downField("challenge")
      val idResult =
        challengeCursor.get[String]("id").orElse(cursor.get[String]("id"))
      val statusResult =
        challengeCursor
          .get[String]("status")
          .orElse(challengeCursor.downField("status").get[String]("name"))
          .orElse(cursor.get[String]("status"))

      Future.fromTry {
        for {
          challengeId <- idResult.toTry
          status = statusResult.getOrElse("created")
        } yield LichessChallengeResponse(challengeId, status)
      }.recoverWith { case ex =>
        Future.failed(new RuntimeException(s"Failed to parse Lichess challenge response: ${ex.getMessage}", ex))
      }
    }
  }

  def lookupChallengeGameId(challengeId: String, accessToken: String): Future[Option[String]] = {
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = s"${config.baseUrl}/api/challenge/${urlEncode(challengeId)}",
      headers = List(headers.RawHeader("Authorization", s"Bearer $accessToken"))
    )
    withRetries(request).map { json =>
      val cursor = json.hcursor
      val topLevel = cursor.get[String]("gameId").toOption
      val challenge = cursor.downField("challenge").get[String]("gameId").toOption
      val game = cursor.downField("game").get[String]("id").toOption
      topLevel.orElse(challenge).orElse(game).filter(_.trim.nonEmpty)
    }
  }

  private def withRetries(request: HttpRequest): Future[Json] =
    retry(config.retryCount.max(1)) {
      withTimeout(Http()(classicSystem).singleRequest(request), requestTimeout).flatMap(decodeResponse)
    }

  private def decodeResponse(response: HttpResponse): Future[Json] =
    withTimeout(response.entity.toStrict(requestTimeout), requestTimeout).flatMap { strict =>
      val body = strict.data.utf8String
      if (!response.status.isSuccess()) {
        Future.failed(new RuntimeException(s"Lichess API ${response.status.intValue()}: $body"))
      } else {
        Future.fromTry(parse(body).toTry)
      }
    }

  private def retry[T](attempts: Int)(thunk: => Future[T]): Future[T] =
    thunk.recoverWith { case ex if attempts > 1 =>
      after(150.millis, classicSystem.scheduler)(retry(attempts - 1)(thunk))
    }

  private def withTimeout[T](future: Future[T], timeout: FiniteDuration): Future[T] =
    Future.firstCompletedOf(
      Seq(
        future,
        after(timeout, classicSystem.scheduler)(
          Future.failed(new RuntimeException(s"Request timed out after ${timeout.toMillis}ms"))
        )
      )
    )

  private def encodeParams(params: Seq[(String, String)]): String =
    params.map { case (k, v) => s"${urlEncode(k)}=${urlEncode(v)}" }.mkString("&")

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
