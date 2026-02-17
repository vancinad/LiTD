package litd.mongo.migration

import org.mongodb.scala.MongoDatabase

import scala.concurrent.{ExecutionContext, Future}

trait Migration {
  def version: Int
  def description: String
  def up(database: MongoDatabase)(implicit ec: ExecutionContext): Future[Unit]
}

