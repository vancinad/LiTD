package litd.auth

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.pattern.after
import io.circe.HCursor
import io.circe.Json
import io.circe.parser.parse
import litd.tournament.TournamentRules

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

final class LichessApiClient(
    config: LichessAuthConfig
)(implicit system: ActorSystem[Nothing], ec: ExecutionContext) {
  private val classicSystem = system.toClassic
  private val requestTimeout: FiniteDuration = config.requestTimeoutMillis.millis

  def authorizeUrl(state: String, codeChallenge: String): String = {
    val params = Seq(
      "response_type" -> "code",
      "client_id" -> config.clientId,
      "redirect_uri" -> config.redirectUri,
      "scope" -> config.scope,
      "state" -> state,
      "code_challenge_method" -> "S256",
      "code_challenge" -> codeChallenge
    )
    s"${config.baseUrl}/oauth?${encodeParams(params)}"
  }

  def exchangeCodeForToken(code: String, codeVerifier: String): Future[OAuthTokenResponse] = {
    val formData = FormData(
      "grant_type" -> "authorization_code",
      "code" -> code,
      "client_id" -> config.clientId,
      "client_secret" -> config.clientSecret,
      "redirect_uri" -> config.redirectUri,
      "code_verifier" -> codeVerifier
    )
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = s"${config.baseUrl}/api/token",
      headers = List(headers.Accept(MediaTypes.`application/json`)),
      entity = formData.toEntity
    )

    withRetriesBody(request).flatMap { body =>
      Future.fromTry(parseOAuthTokenResponse(body)).recoverWith { case ex =>
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

  def isTeamMember(userId: String, teamId: String, accessToken: String): Future[Boolean] = {
    listTeams(userId, accessToken).map { teams =>
      teams.exists(_.id == teamId)
    }
  }

  def listTeams(userId: String, accessToken: String): Future[Seq[LichessTeamView]] = {
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = s"${config.baseUrl}/api/team/of/${urlEncode(userId)}",
      headers = List(headers.RawHeader("Authorization", s"Bearer $accessToken"))
    )
    withRetries(request).map { json =>
      json.asArray
        .getOrElse(Vector.empty)
        .flatMap { team =>
          val cursor = team.hcursor
          cursor.get[String]("id").toOption.map { id =>
            val name = cursor.get[String]("name").getOrElse(id)
            LichessTeamView(id = id, name = name)
          }
        }
    }
  }

  def issueChallenge(
      opponentUserId: String,
      accessToken: String,
      initialSeconds: Int,
      incrementSeconds: Int,
      challengerColor: String
  ): Future[LichessChallengeResponse] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = s"${config.baseUrl}/api/challenge/${urlEncode(opponentUserId)}",
      headers = List(headers.RawHeader("Authorization", s"Bearer $accessToken")),
      entity = FormData(
        "clock.limit" -> initialSeconds.toString,
        "clock.increment" -> incrementSeconds.toString,
        "color" -> challengerColor
      ).toEntity
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
        } yield LichessChallengeResponse(challengeId, status, challengerColor)
      }.recoverWith { case ex =>
        Future.failed(new RuntimeException(s"Failed to parse Lichess challenge response: ${ex.getMessage}", ex))
      }
    }
  }

  def lookupGameResults(gameIds: Seq[String]): Future[Map[String, String]] = {
    val normalizedIds = gameIds.map(_.trim).filter(_.nonEmpty).distinct
    if (normalizedIds.isEmpty) {
      Future.successful(Map.empty)
    } else {
      val requestBody = normalizedIds.mkString(",")
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = Uri(s"${config.baseUrl}/api/games/export/_ids")
          .withQuery(Query("pgnInJson" -> "true", "moves" -> "false")),
        headers = List(headers.RawHeader("Accept", "application/x-ndjson")),
        entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, requestBody)
      )

      // Set a breakpoint here to inspect outgoing game IDs and request details.
      val responseBodyFuture: Future[String] = withRetriesBody(request)

      responseBodyFuture.map { responseBody =>
        // Set a breakpoint here to inspect raw NDJSON returned by Lichess.
        parseGameResultsBody(responseBody)
      }
    }
  }

  private def parseGameResultsBody(responseBody: String): Map[String, String] =
    responseBody.linesIterator
      .flatMap { line =>
        parse(line).toOption.flatMap { json =>
          val cursor = json.hcursor
          val gameId = cursor.get[String]("id").toOption.map(_.trim).filter(_.nonEmpty)
          val result = parseResult(cursor)
          gameId.flatMap(id => result.map(id -> _))
        }
      }
      .toMap

  private def withRetries(request: HttpRequest): Future[Json] =
    retry(config.retryCount.max(1)) {
      withTimeout(Http()(classicSystem).singleRequest(request), requestTimeout).flatMap(decodeResponse)
    }

  private def withRetriesBody(request: HttpRequest): Future[String] =
    retry(config.retryCount.max(1)) {
      withTimeout(Http()(classicSystem).singleRequest(request), requestTimeout).flatMap(decodeBody)
    }

  private def decodeResponse(response: HttpResponse): Future[Json] =
    decodeBody(response).flatMap { body =>
      Future.fromTry(parse(body).toTry)
    }

  private def decodeBody(response: HttpResponse): Future[String] =
    withTimeout(response.entity.toStrict(requestTimeout), requestTimeout).flatMap { strict =>
      val body = strict.data.utf8String
      if (!response.status.isSuccess()) {
        Future.failed(new RuntimeException(s"Lichess API ${response.status.intValue()}: $body"))
      } else {
        Future.successful(body)
      }
    }

  private def parseOAuthTokenResponse(body: String): Try[OAuthTokenResponse] =
    parse(body).toTry
      .flatMap { json =>
        for {
          token <- json.hcursor.get[String]("access_token").toTry
          tokenType = json.hcursor.get[String]("token_type").getOrElse("Bearer")
          scope = json.hcursor.get[String]("scope").getOrElse(config.scope)
          expires = json.hcursor.get[Int]("expires_in").toOption
        } yield OAuthTokenResponse(token, tokenType, scope, expires)
      }
      .recoverWith { case _ =>
        parseFormEncodedTokenResponse(body)
      }

  private def parseFormEncodedTokenResponse(body: String): Try[OAuthTokenResponse] = {
    val query = Query(body)
    query.get("access_token") match {
      case Some(token) =>
        val tokenType = query.get("token_type").getOrElse("Bearer")
        val scope = query.get("scope").getOrElse(config.scope)
        val expires = query.get("expires_in").flatMap(_.toIntOption)
        Try(OAuthTokenResponse(token, tokenType, scope, expires))
      case None =>
        Failure(new RuntimeException(s"Token payload missing access_token: $body"))
    }
  }

  private def parseResult(cursor: HCursor): Option[String] =
    cursor
      .get[String]("winner")
      .toOption
      .collect {
        case TournamentRules.ResultWhite => TournamentRules.ResultWhite
        case TournamentRules.ResultBlack => TournamentRules.ResultBlack
      }
      .orElse {
        val status = cursor.get[String]("status").toOption.map(_.toLowerCase)
        status.collect {
          case "draw" | "stalemate" | "repetition" | "50move" | "insufficient" | "timevsinsufficient" =>
            TournamentRules.ResultDraw
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
