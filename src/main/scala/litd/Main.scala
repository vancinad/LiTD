package litd

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import com.typesafe.config.{Config, ConfigFactory}
import org.mongodb.scala.MongoClient

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import io.circe.syntax.EncoderOps

final case class HttpConfig(host: String, port: Int)
final case class MongoConfig(uri: String, database: String)
final case class AppConfig(http: HttpConfig, mongodb: MongoConfig)

object AppConfigLoader {
  def load(config: Config = ConfigFactory.load()): AppConfig = {
    val httpConfig = config.getConfig("litd.http")
    val mongoConfig = config.getConfig("litd.mongodb")
    AppConfig(
      http = HttpConfig(
        host = httpConfig.getString("host"),
        port = httpConfig.getInt("port")
      ),
      mongodb = MongoConfig(
        uri = mongoConfig.getString("uri"),
        database = mongoConfig.getString("database")
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
    system.log.info("mongoClient status: "+mongoClient.getClusterDescription)
    
    // GET /health -> "ok"
    val routes = path("health") {
      get {
        complete(StatusCodes.OK -> "ok")
      }
    }

    Http()
      .newServerAt(appConfig.http.host, appConfig.http.port)
      .bind(routes)
      .onComplete {
        case Success(binding) =>
          system.log.info("Server running at {}", binding.localAddress)
        case Failure(ex) =>
          system.log.error("Failed to bind HTTP endpoint", ex)
          mongoClient.close()
          system.terminate()
      }

  }
}