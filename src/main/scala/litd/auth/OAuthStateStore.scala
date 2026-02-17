package litd.auth

import java.time.{Duration, Instant}
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom
import scala.jdk.CollectionConverters._

final class OAuthStateStore(stateTtlSeconds: Int) {
  private val random = new SecureRandom()
  private val ttl = Duration.ofSeconds(stateTtlSeconds.toLong)
  private val states = new ConcurrentHashMap[String, Instant]()

  def issueState(): String = {
    cleanupExpired()
    val state = newStateValue()
    states.put(state, Instant.now().plus(ttl))
    state
  }

  def consumeState(state: String): Boolean = {
    cleanupExpired()
    Option(states.remove(state)).exists(_.isAfter(Instant.now()))
  }

  private def cleanupExpired(): Unit = {
    val now = Instant.now()
    states.entrySet().asScala.foreach { entry =>
      if (entry.getValue.isBefore(now)) {
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

