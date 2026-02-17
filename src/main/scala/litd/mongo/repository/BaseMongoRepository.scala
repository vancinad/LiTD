package litd.mongo.repository

import org.bson.types.ObjectId
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.ReplaceOptions
import org.mongodb.scala.{MongoCollection, MongoDatabase}

import scala.concurrent.{ExecutionContext, Future}

trait BaseMongoRepository[T] {
  def insert(document: T)(implicit ec: ExecutionContext): Future[T]
  def findById(id: ObjectId)(implicit ec: ExecutionContext): Future[Option[T]]
  def list(limit: Int = 100)(implicit ec: ExecutionContext): Future[Seq[T]]
  def replaceById(id: ObjectId, document: T)(implicit ec: ExecutionContext): Future[Boolean]
  def deleteById(id: ObjectId)(implicit ec: ExecutionContext): Future[Boolean]
}

abstract class MongoRepository[T: Manifest](database: MongoDatabase, collectionName: String)
    extends BaseMongoRepository[T] {
  protected val collection: MongoCollection[T] = database.getCollection[T](collectionName)

  override def insert(document: T)(implicit ec: ExecutionContext): Future[T] =
    collection.insertOne(document).toFuture().map(_ => document)

  override def findById(id: ObjectId)(implicit ec: ExecutionContext): Future[Option[T]] =
    collection.find(equal("_id", id)).first().toFutureOption()

  override def list(limit: Int = 100)(implicit ec: ExecutionContext): Future[Seq[T]] =
    collection.find().limit(limit).toFuture()

  override def replaceById(id: ObjectId, document: T)(implicit ec: ExecutionContext): Future[Boolean] =
    collection
      .replaceOne(equal("_id", id), document, ReplaceOptions().upsert(false))
      .toFuture()
      .map(_.getModifiedCount > 0)

  override def deleteById(id: ObjectId)(implicit ec: ExecutionContext): Future[Boolean] =
    collection
      .deleteOne(equal("_id", id))
      .toFuture()
      .map(_.getDeletedCount > 0)
}

