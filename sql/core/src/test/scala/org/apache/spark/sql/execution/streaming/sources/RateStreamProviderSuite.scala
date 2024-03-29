/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.streaming.sources

import java.util.Optional
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.execution.streaming._
import org.apache.spark.sql.execution.streaming.continuous._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.sources.v2.{ContinuousReadSupport, DataSourceOptions, MicroBatchReadSupport}
import org.apache.spark.sql.sources.v2.reader.streaming.Offset
import org.apache.spark.sql.streaming.StreamTest
import org.apache.spark.util.ManualClock

class RateSourceSuite extends StreamTest {

  import testImplicits._

  case class AdvanceRateManualClock(seconds: Long) extends AddData {
    override def addData(query: Option[StreamExecution]): (BaseStreamingSource, Offset) = {
      assert(query.nonEmpty)
      val rateSource = query.get.logicalPlan.collect {
        case StreamingExecutionRelation(source: RateStreamMicroBatchReader, _) => source
      }.head

      rateSource.clock.asInstanceOf[ManualClock].advance(TimeUnit.SECONDS.toMillis(seconds))
      val offset = LongOffset(TimeUnit.MILLISECONDS.toSeconds(
        rateSource.clock.getTimeMillis() - rateSource.creationTimeMs))
      (rateSource, offset)
    }
  }

  test("microbatch in registry") {
    withTempDir { temp =>
      DataSource.lookupDataSource("rate", spark.sqlContext.conf).
        getConstructor().newInstance() match {
        case ds: MicroBatchReadSupport =>
          val reader = ds.createMicroBatchReader(
            Optional.empty(), temp.getCanonicalPath, DataSourceOptions.empty())
          assert(reader.isInstanceOf[RateStreamMicroBatchReader])
        case _ =>
          throw new IllegalStateException("Could not find read support for rate")
      }
    }
  }

  test("compatible with old path in registry") {
    DataSource.lookupDataSource("org.apache.spark.sql.execution.streaming.RateSourceProvider",
      spark.sqlContext.conf).getConstructor().newInstance() match {
      case ds: MicroBatchReadSupport =>
        assert(ds.isInstanceOf[RateStreamProvider])
      case _ =>
        throw new IllegalStateException("Could not find read support for rate")
    }
  }

  test("microbatch - basic") {
    val input = spark.readStream
      .format("rate")
      .option("rowsPerSecond", "10")
      .option("useManualClock", "true")
      .load()
    testStream(input)(
      AdvanceRateManualClock(seconds = 1),
      CheckLastBatch((0 until 10).map(v => new java.sql.Timestamp(v * 100L) -> v): _*)
    )
  }

  test("microbatch - restart") {
    val input = spark.readStream
      .format("rate")
      .option("rowsPerSecond", "10")
      .load()
      .select('value)

    var streamDuration = 0

    // Microbatch rate stream offsets contain the number of seconds since the beginning of
    // the stream.
    def updateStreamDurationFromOffset(s: StreamExecution, expectedMin: Int): Unit = {
      streamDuration = s.lastProgress.sources(0).endOffset.toInt
      assert(streamDuration >= expectedMin)
    }

    // We have to use the lambda version of CheckAnswer because we don't know the right range
    // until we see the last offset.
    def expectedResultsFromDuration(rows: Seq[Row]): Unit = {
      assert(rows.map(_.getLong(0)).sorted == (0 until (streamDuration * 10)))
    }

    testStream(input)(
      StartStream(),
      Execute(_.awaitOffset(0, LongOffset(2), streamingTimeout.toMillis)),
      StopStream,
      Execute(updateStreamDurationFromOffset(_, 2)),
      CheckAnswer(expectedResultsFromDuration _),
      StartStream(),
      Execute(_.awaitOffset(0, LongOffset(4), streamingTimeout.toMillis)),
      StopStream,
      Execute(updateStreamDurationFromOffset(_, 4)),
      CheckAnswer(expectedResultsFromDuration _)
    )
  }

  test("microbatch - uniform distribution of event timestamps") {
    val input = spark.readStream
      .format("rate")
      .option("rowsPerSecond", "1500")
      .option("useManualClock", "true")
      .load()
      .as[(java.sql.Timestamp, Long)]
      .map(v => (v._1.getTime, v._2))
    val expectedAnswer = (0 until 1500).map { v =>
      (math.round(v * (1000.0 / 1500)), v)
    }
    testStream(input)(
      AdvanceRateManualClock(seconds = 1),
      CheckLastBatch(expectedAnswer: _*)
    )
  }

  test("microbatch - set offset") {
    withTempDir { temp =>
      val reader = new RateStreamMicroBatchReader(DataSourceOptions.empty(), temp.getCanonicalPath)
      val startOffset = LongOffset(0L)
      val endOffset = LongOffset(1L)
      reader.setOffsetRange(Optional.of(startOffset), Optional.of(endOffset))
      assert(reader.getStartOffset() == startOffset)
      assert(reader.getEndOffset() == endOffset)
    }
  }

  test("microbatch - infer offsets") {
    withTempDir { temp =>
      val reader = new RateStreamMicroBatchReader(
        new DataSourceOptions(
          Map("numPartitions" -> "1", "rowsPerSecond" -> "100", "useManualClock" -> "true").asJava),
        temp.getCanonicalPath)
      reader.clock.asInstanceOf[ManualClock].advance(100000)
      reader.setOffsetRange(Optional.empty(), Optional.empty())
      reader.getStartOffset() match {
        case r: LongOffset => assert(r.offset === 0L)
        case _ => throw new IllegalStateException("unexpected offset type")
      }
      reader.getEndOffset() match {
        case r: LongOffset => assert(r.offset >= 100)
        case _ => throw new IllegalStateException("unexpected offset type")
      }
    }
  }

  test("microbatch - predetermined batch size") {
    withTempDir { temp =>
      val reader = new RateStreamMicroBatchReader(
        new DataSourceOptions(Map("numPartitions" -> "1", "rowsPerSecond" -> "20").asJava),
        temp.getCanonicalPath)
      val startOffset = LongOffset(0L)
      val endOffset = LongOffset(1L)
      reader.setOffsetRange(Optional.of(startOffset), Optional.of(endOffset))
      val tasks = reader.planInputPartitions()
      assert(tasks.size == 1)
      val dataReader = tasks.get(0).createPartitionReader()
      val data = ArrayBuffer[InternalRow]()
      while (dataReader.next()) {
        data.append(dataReader.get())
      }
      assert(data.size === 20)
    }
  }

  test("microbatch - data read") {
    withTempDir { temp =>
      val reader = new RateStreamMicroBatchReader(
        new DataSourceOptions(Map("numPartitions" -> "11", "rowsPerSecond" -> "33").asJava),
        temp.getCanonicalPath)
      val startOffset = LongOffset(0L)
      val endOffset = LongOffset(1L)
      reader.setOffsetRange(Optional.of(startOffset), Optional.of(endOffset))
      val tasks = reader.planInputPartitions()
      assert(tasks.size == 11)

      val readData = tasks.asScala
        .map(_.createPartitionReader())
        .flatMap { reader =>
          val buf = scala.collection.mutable.ListBuffer[InternalRow]()
          while (reader.next()) buf.append(reader.get())
          buf
        }

      assert(readData.map(_.getLong(1)).sorted == Range(0, 33))
    }
  }

  test("valueAtSecond") {
    import RateStreamProvider._

    assert(valueAtSecond(seconds = 0, rowsPerSecond = 5, rampUpTimeSeconds = 0) === 0)
    assert(valueAtSecond(seconds = 1, rowsPerSecond = 5, rampUpTimeSeconds = 0) === 5)

    assert(valueAtSecond(seconds = 0, rowsPerSecond = 5, rampUpTimeSeconds = 2) === 0)
    assert(valueAtSecond(seconds = 1, rowsPerSecond = 5, rampUpTimeSeconds = 2) === 1)
    assert(valueAtSecond(seconds = 2, rowsPerSecond = 5, rampUpTimeSeconds = 2) === 3)
    assert(valueAtSecond(seconds = 3, rowsPerSecond = 5, rampUpTimeSeconds = 2) === 8)

    assert(valueAtSecond(seconds = 0, rowsPerSecond = 10, rampUpTimeSeconds = 4) === 0)
    assert(valueAtSecond(seconds = 1, rowsPerSecond = 10, rampUpTimeSeconds = 4) === 2)
    assert(valueAtSecond(seconds = 2, rowsPerSecond = 10, rampUpTimeSeconds = 4) === 6)
    assert(valueAtSecond(seconds = 3, rowsPerSecond = 10, rampUpTimeSeconds = 4) === 12)
    assert(valueAtSecond(seconds = 4, rowsPerSecond = 10, rampUpTimeSeconds = 4) === 20)
    assert(valueAtSecond(seconds = 5, rowsPerSecond = 10, rampUpTimeSeconds = 4) === 30)
  }

  test("rampUpTime") {
    val input = spark.readStream
      .format("rate")
      .option("rowsPerSecond", "10")
      .option("rampUpTime", "4s")
      .option("useManualClock", "true")
      .load()
      .as[(java.sql.Timestamp, Long)]
      .map(v => (v._1.getTime, v._2))
    testStream(input)(
      AdvanceRateManualClock(seconds = 1),
      CheckLastBatch((0 until 2).map(v => v * 500 -> v): _*), // speed = 2
      AdvanceRateManualClock(seconds = 1),
      CheckLastBatch((2 until 6).map(v => 1000 + (v - 2) * 250 -> v): _*), // speed = 4
      AdvanceRateManualClock(seconds = 1),
      CheckLastBatch({
        Seq(2000 -> 6, 2167 -> 7, 2333 -> 8, 2500 -> 9, 2667 -> 10, 2833 -> 11)
      }: _*), // speed = 6
      AdvanceRateManualClock(seconds = 1),
      CheckLastBatch((12 until 20).map(v => 3000 + (v - 12) * 125 -> v): _*), // speed = 8
      AdvanceRateManualClock(seconds = 1),
      // Now we should reach full speed
      CheckLastBatch((20 until 30).map(v => 4000 + (v - 20) * 100 -> v): _*), // speed = 10
      AdvanceRateManualClock(seconds = 1),
      CheckLastBatch((30 until 40).map(v => 5000 + (v - 30) * 100 -> v): _*), // speed = 10
      AdvanceRateManualClock(seconds = 1),
      CheckLastBatch((40 until 50).map(v => 6000 + (v - 40) * 100 -> v): _*) // speed = 10
    )
  }

  test("numPartitions") {
    val input = spark.readStream
      .format("rate")
      .option("rowsPerSecond", "10")
      .option("numPartitions", "6")
      .option("useManualClock", "true")
      .load()
      .select(spark_partition_id())
      .distinct()
    testStream(input)(
      AdvanceRateManualClock(1),
      CheckLastBatch((0 until 6): _*)
    )
  }

  testQuietly("overflow") {
    val input = spark.readStream
      .format("rate")
      .option("rowsPerSecond", Long.MaxValue.toString)
      .option("useManualClock", "true")
      .load()
      .select(spark_partition_id())
      .distinct()
    testStream(input)(
      AdvanceRateManualClock(2),
      ExpectFailure[ArithmeticException](t => {
        Seq("overflow", "rowsPerSecond").foreach { msg =>
          assert(t.getMessage.contains(msg))
        }
      })
    )
  }

  testQuietly("illegal option values") {
    def testIllegalOptionValue(
        option: String,
        value: String,
        expectedMessages: Seq[String]): Unit = {
      val e = intercept[IllegalArgumentException] {
        spark.readStream
          .format("rate")
          .option(option, value)
          .load()
          .writeStream
          .format("console")
          .start()
          .awaitTermination()
      }
      for (msg <- expectedMessages) {
        assert(e.getMessage.contains(msg))
      }
    }

    testIllegalOptionValue("rowsPerSecond", "-1", Seq("-1", "rowsPerSecond", "positive"))
    testIllegalOptionValue("numPartitions", "-1", Seq("-1", "numPartitions", "positive"))
  }

  test("user-specified schema given") {
    val exception = intercept[AnalysisException] {
      spark.readStream
        .format("rate")
        .schema(spark.range(1).schema)
        .load()
    }
    assert(exception.getMessage.contains(
      "rate source does not support a user-specified schema"))
  }

  test("continuous in registry") {
    DataSource.lookupDataSource("rate", spark.sqlContext.conf).
      getConstructor().newInstance() match {
      case ds: ContinuousReadSupport =>
        val reader = ds.createContinuousReader(Optional.empty(), "", DataSourceOptions.empty())
        assert(reader.isInstanceOf[RateStreamContinuousReader])
      case _ =>
        throw new IllegalStateException("Could not find read support for continuous rate")
    }
  }

  test("continuous data") {
    val reader = new RateStreamContinuousReader(
      new DataSourceOptions(Map("numPartitions" -> "2", "rowsPerSecond" -> "20").asJava))
    reader.setStartOffset(Optional.empty())
    val tasks = reader.planInputPartitions()
    assert(tasks.size == 2)

    val data = scala.collection.mutable.ListBuffer[InternalRow]()
    tasks.asScala.foreach {
      case t: RateStreamContinuousInputPartition =>
        val startTimeMs = reader.getStartOffset()
          .asInstanceOf[RateStreamOffset]
          .partitionToValueAndRunTimeMs(t.partitionIndex)
          .runTimeMs
        val r = t.createPartitionReader().asInstanceOf[RateStreamContinuousInputPartitionReader]
        for (rowIndex <- 0 to 9) {
          r.next()
          data.append(r.get())
          assert(r.getOffset() ==
            RateStreamPartitionOffset(
              t.partitionIndex,
              t.partitionIndex + rowIndex * 2,
              startTimeMs + (rowIndex + 1) * 100))
        }
        assert(System.currentTimeMillis() >= startTimeMs + 1000)

      case _ => throw new IllegalStateException("Unexpected task type")
    }

    assert(data.map(_.getLong(1)).toSeq.sorted == Range(0, 20))
  }
}
