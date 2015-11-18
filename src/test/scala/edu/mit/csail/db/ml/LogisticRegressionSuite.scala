package org.apache.spark.ml

import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.sql.{SQLContext, DataFrame}
import org.scalatest.{FunSuite, BeforeAndAfter}

/**
 * Check whether models are cached in the model database.
 */
class LogisticRegressionSuite extends FunSuite with BeforeAndAfter {
  before {
    TestBase.db.clear()
  }

  test("are models cached in the model database?") {
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
}
