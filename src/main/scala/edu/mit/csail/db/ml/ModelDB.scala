package org.apache.spark.ml

import org.apache.spark.sql.DataFrame
import com.mongodb.casbah.Imports._
import java.security.MessageDigest

/**
 * This is the model database.
 * Currently, it stores all the models in a single MongoDB collection.
 * It connects to the default localhost:27017.
 */
class ModelDb(private val databaseName: String, private val modelCollectionName: String) {
  /**
   * Set up database connection
   */
  val mongoClient = MongoClient()
  val modelCollection = mongoClient(databaseName)(modelCollectionName)

  /**
   * Store this model in the database.
   * @param spec - The specification. Since this is a key, make sure it implements equals() and
   *             hashCode().
   * @param model - The model to store with the given key.
   * @tparam M - The type of the model being stored. For example, LogisticRegressionModel.
   */
  def cache[M <: Model[M]](spec: ModelSpec[M], model: M, dataset: DataFrame): Unit = {
    val modelObj: MongoDBObject = spec.toDBObject(model)
    // TODO dataframe may not be stored in same table, to allow for folds.
    modelObj += "dataframe" -> hashDataFrame(dataset)
    modelCollection += modelObj
  }

  /**
   * Deterministic hashing function for a DataFrame.
   * @param dataset - The DataFrame for which we are generating the hash.
   * @return A unique, deterministic MD5 hash of the data in the DataFrame
   */
  private def hashDataFrame(dataset: DataFrame) = {
    val info = dataset.toString() + dataset.count.toString()
    MessageDigest.getInstance("MD5").digest(info.getBytes).map("%02x".format(_)).mkString
  }

  /**
   * Check whether this model specification has a model that has been cached.
   * @param spec - The specification, make sure it implements equals() and hashCode().
   * @tparam M - The type of the model. For example, LogisticRegressionModel.
   * @return Whether the cache contains a model with the given specification.
   */
  def get[M <: Model[M]](spec: ModelSpec[M], dataset: DataFrame): M = {
    val modelQuery = spec.toDBQuery()
    modelQuery += "dataframe" -> hashDataFrame(dataset)
    modelCollection.findOne(modelQuery) match {
      case Some(modelInfo) => {
        return spec.generateModel(new DBObjectHelper(modelInfo))
      }
      case None => return null.asInstanceOf[M]
    }
  }

  /**
   * Fetch the model with the given specification, or execute a function to generate a model if the
   * result is not found.
   * @param spec - The specification to look up, make sure it implements equals() and hashCode().
   * @param orElse - The function to execute if no model is found with the given specification. It
   *               should return a new model.
   * @tparam M - The type of the model. For example, LogisticRegressionModel.
   * @return The model fetched from the database, or the result of orElse.
   */
  def getOrElse[M <: Model[M]](spec: ModelSpec[M], dataset: DataFrame)(orElse: ()=> M): M = {
    val model = get[M](spec, dataset)
    if (model != None && model.isInstanceOf[Model[M]])
      model.asInstanceOf[M] else orElse()
  }

  /**
   * ONLY FOR TESTING PURPOSES
   * TODO: create a more selective clear function
   * Drops the entire database so we start anew when testing.
   */
  def clear() = modelCollection.remove(MongoDBObject.empty)
}

/**
 * Augments a class with a model database. Call the setDb method and ensure each object points to the
 * same model database.
 */
trait HasModelDb {
  private var modelDb: Option[ModelDb] = None
  def setDb(db: ModelDb) = modelDb = Some(db)
  def getDb: Option[ModelDb] = modelDb
}

/**
 * Augment a class to give it the ability to check for a model in the model database before it tries
 * training a new one.
 * @tparam M - The type of the model. For example, LogisticRegressionModel.
 */
trait CanCache[M <: Model[M]] extends Estimator[M] with HasModelDb {
  /**
   * Generate a ModelSpec given the dataset.
   * @param dataset - The dataset.
   * @return The model specification.
   */
  def modelSpec(dataset: DataFrame): ModelSpec[M]

  /**
   * Smarter fit method which checks the cache before it trains. If the model is not in the cache,
   * train it. Otherwise, returned the cached model.
   * @param dataset - The dataset to train on.
   * @return The trained model.
   */
  abstract override def fit(dataset: DataFrame): M =
    super.getDb match {
      case Some(db) =>
        db.getOrElse[M](modelSpec(dataset), dataset)(() => {
          val model = super.fit(dataset)
          db.cache[M](modelSpec(dataset), model, dataset)
          model
        })
      case None =>
        super.fit(dataset)
    }
}
