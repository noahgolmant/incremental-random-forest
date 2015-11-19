package org.apache.spark.ml

import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.sql.{SQLContext, DataFrame}
import org.scalatest.{FunSuite, BeforeAndAfter}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.Row
import org.apache.spark.ml.classification.{LogisticRegressionModel}

/**
 * Check whether models are cached in the model database.
 */
class LogisticRegressionSuite extends FunSuite with BeforeAndAfter {
  before {
    TestBase.db.clear()
  }

  test("models cached in model database") {
    val training = TestBase.sqlContext.createDataFrame(Seq(
      (1.0, Vectors.dense(0.0, 1.1, 0.1)),
      (0.0, Vectors.dense(2.0, 1.0, -1.0)),
      (0.0, Vectors.dense(2.0, 1.3, 1.0)),
      (1.0, Vectors.dense(0.0, 1.2, -0.5))
    )).toDF("label", "features")
    

    // Train a Wahoo logistic regression model.
    val lr = new WahooLogisticRegression()
    lr.setMaxIter(10).setRegParam(1.0).setDb(TestBase.db)
    lr.fit(training)

    // The first training should train from scratch.
    assert(!TestBase.db.fromCache)

    // The second training should just read from the cache.
    lr.fit(training)
    assert(TestBase.db.fromCache)
  }

  /**
   * Dataset source: UCI Machine Learning Repository
   */
  test("model caching works with a large dataset") {
    val data = TestBase.sqlContext.read
      .format("com.databricks.spark.csv")
      .option("header","true")
      .option("inferSchema","true")
      .load("src/test/scala/edu/mit/csail/db/ml/data/test.csv")

    val toVec = udf[Vector, Int, Double, Double, Double, Double, Int] {
      (a,b,c,d,e,f) => Vectors.dense(a,b,c,d,e,f)
    }
    val toBinary = udf[Double, Double]( a => {
      if (a > 30.2) 1 // 2011 CAFE standard
      else 0
    })
    val allData = data.withColumn("features", toVec(
      data("cylinders"),
      data("displacement"),
      data("horsepower"),
      data("weight"),
      data("acceleration"),
      data("year")
    )).withColumn("label", toBinary(data("mpg")))
      .select("features","label")
      .randomSplit(Array(0.8,0.2))

    val training = allData(0)
    val testing = allData(1)

    // Train a Wahoo logistic regression model.
    val lr = new WahooLogisticRegression()
    lr.setMaxIter(30).setRegParam(0.05).setDb(TestBase.db)
    val model1 = lr.fit(training)

    // The first training should train from scratch.
    assert(!TestBase.db.fromCache)
    val accuracy1 = evalModel(model1, testing)

    // The second training should just read from the cache.
    val model2 = lr.fit(training)
    assert(TestBase.db.fromCache)
    val accuracy2 = evalModel(model2, testing)

    assert(accuracy2 == accuracy1)
    println("accuracy: " + accuracy2)
  }


  /**
   * This method evaluates a model on a test data set and outputs both
   * the results and the overall accuracy. This method exists within
   * the LogisticRegressionModel class, but is private, so this is
   * a modified version for testing purposes.
   * @param model - the trained model
   * @param testing - the testing data
   * @return the accuracy of the model on the testing data
   */
  def evalModel(model: LogisticRegressionModel, testing: DataFrame): Double = {
    var count = 0.0
    var pos = 0.0
    model.transform(testing)
      .select("features", "label", "prediction")
      .collect()
      .foreach { case Row(features: Vector, label: Double, prediction: Double) =>
        count += 1.0
        if (label == prediction) pos += 1.0
        println(s"($features, $label) -> prediction=$prediction")
      }
    pos/count
  }
}
