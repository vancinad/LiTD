package litd.auth

import java.time.{Duration, Instant}
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom
import scala.jdk.CollectionConverters._

final class OAuthStateStore(stateTtlSeconds: Int) {
  private final class StoredState(val expiresAt: Instant, val codeVerifier: String)

  private val random = new SecureRandom()
  private val ttl = Duration.ofSeconds(stateTtlSeconds.toLong)
  private val states = new ConcurrentHashMap[String, StoredState]()

  def issueState(codeVerifier: String): String = {
    cleanupExpired()
    val state = newStateValue()
    states.put(state, new StoredState(Instant.now().plus(ttl), codeVerifier))
    state
  }

  def consumeState(state: String): Option[String] = {
    cleanupExpired()
    Option(states.remove(state))
      .filter(_.expiresAt.isAfter(Instant.now()))
      .map(_.codeVerifier)
  }

  private def cleanupExpired(): Unit = {
    val now = Instant.now()
    states.entrySet().asScala.foreach { entry =>
      if (entry.getValue.expiresAt.isBefore(now)) {
        states.remove(entry.getKey)
      }
    }
  }

  private def newStateValue(): String = {
    val bytes = new Array[Byte](24)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }
}
