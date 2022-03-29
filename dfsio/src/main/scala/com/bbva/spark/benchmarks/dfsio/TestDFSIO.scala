/*
 * Copyright 2017 Banco Bilbao Vizcaya Argentaria S.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bbva.spark.benchmarks.dfsio

import java.io.{BufferedWriter, FileWriter, PrintWriter}
import java.util.Date

import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.{LongWritable, SequenceFile, Text}
import org.apache.hadoop.io.SequenceFile.{CompressionType, Writer}
import org.apache.hadoop.io.compress._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

/**
  * Distributed I/O benchmark.
  *
  * This test application writes into or reads from a specified number of files. The number of bytes to write or read
  * is also specified as a parameter to the test. By default, each file is accessed in a separate spark task.
  *
  */
object TestDFSIO extends App with LazyLogging {

  val BaseFileName = "test_io_"
  val ControlDir = "io_control"
  val DataDir = "io_data"

  Logger.getLogger("akka").setLevel(Level.WARN)
  //Logger.getLogger("org").setLevel(Level.WARN)

  TestDFSIOConfParser.parseAndRun(args) { conf =>

    val sparkConf = new SparkConf().setAppName("TestDFSIO").set("spark.logConf", "true")

    implicit val sc = SparkContext.getOrCreate(sparkConf)

    conf.hadoopExtraProps.foreach { case (k, v) =>
      sc.hadoopConfiguration.set(k, v)
    }

    implicit val hadoopConf = new Configuration(sc.hadoopConfiguration)

    // set buffer size
    hadoopConf.setInt("test.io.file.buffer.size", conf.bufferSize)
    hadoopConf.set("fs.s3a.access.key", "UTXJE0A2QE6RRZLYWAJ0")
    hadoopConf.set("fs.s3a.secret.key", "1vM5gdhQ3PZmn1rzMkvPpxwcpCpeS8Caq7eQhgQy")
    hadoopConf.set("fs.s3a.endpoint", "http://main.selab-avnet202.sli.vastdata.com")
    hadoopConf.set("fs.s3a.connection.ssl.enabled", "false")
    hadoopConf.set("fs.s3a.path.style.access", "true")
~

    val analyze: (=> Stats) => Unit = measure(conf.mode, conf.resFileName)

    conf.mode match {
      case Clean =>
        cleanUp(conf.benchmarkDir)
      case Write =>
        createControlFiles(conf.benchmarkDir, conf.fileSize, conf.numFiles)
        analyze(runWriteTest(conf.benchmarkDir))
      case Read =>
        analyze(runReadTest(conf.benchmarkDir))
      case _ => // ignore
    }

  }

  private def cleanUp(benchmarkDir: String)(implicit hadoopConf: Configuration): Unit = {
    logger.info("Cleaning up test files")
    val fs = FileSystem.get(hadoopConf)
    val path = new Path(benchmarkDir)
    if (fs.exists(path)) fs.delete(path, true)
  }

  private def createControlFiles(benchmarkDir: String, fileSize: Long, numFiles: Int)
                                (implicit hadoopConf: Configuration, sc: SparkContext): Unit = {

    val controlDirPath: Path = new Path(benchmarkDir, ControlDir)

    logger.info("Deleting any previous control directory...")
    val fs = FileSystem.get(hadoopConf)
    if (fs.exists(controlDirPath)) fs.delete(controlDirPath, true)

    logger.info("Creating control files: {} bytes, {} files", fileSize.toString, numFiles.toString)
    ControlFilesCreator.createFiles(controlDirPath.toString, numFiles, fileSize)

    //(0 until numFiles).map(getFileName).foreach(createControlFile(hadoopConf, controlDirPath, fileSize))
    logger.info("Control files created for: {}  files", numFiles.toString)

  }

  private def createControlFile(hadoopConf: Configuration, controlDir: Path, fileSize: Long)(fileName: String): Unit = {

    val controlFilePath = new Path(controlDir, s"in_file_$fileName")
    logger.info("Creating control file in path {}, with size {} bytes", controlFilePath.toString, fileSize.toString)

    val writer: Writer = SequenceFile.createWriter(hadoopConf,
      Writer.file(controlFilePath),
      Writer.keyClass(classOf[Text]),
      Writer.valueClass(classOf[LongWritable]),
      Writer.compression(CompressionType.NONE)
    )

    try {
      writer.append(new Text(fileName), new LongWritable(fileSize))
    } finally {
      writer.close()
    }

    logger.info("Control file created in path {}, with size {} bytes", controlFilePath.toString, fileSize.toString)
  }

  private def runWriteTest(benchmarkDir: String)
                          (implicit hadoopConf: Configuration, sc: SparkContext): Stats = {

    val controlDirPath: Path = new Path(benchmarkDir, ControlDir)
    val dataDirPath: Path = new Path(benchmarkDir, DataDir)

    logger.info("Deleting any previous data directories...")
    val fs = FileSystem.get(hadoopConf)
    if (fs.exists(dataDirPath)) fs.delete(dataDirPath, true)

    logger.info("Writing files...")
    val files: RDD[(Text, LongWritable)] = sc.sequenceFile(controlDirPath.toString, classOf[Text], classOf[LongWritable])
    val stats: RDD[Stats] = new IOWriter(hadoopConf, dataDirPath.toString).runIOTest(files)
    StatsAccumulator.accumulate(stats)

  }

  private def runReadTest(benchmarkDir: String)(implicit hadoopConf: Configuration, sc: SparkContext): Stats = {

    val controlDirPath: Path = new Path(benchmarkDir, ControlDir)
    val dataDirPath: Path = new Path(benchmarkDir, DataDir)

    logger.info("Reading files...")
    val files: RDD[(Text, LongWritable)] = sc.sequenceFile(controlDirPath.toString, classOf[Text], classOf[LongWritable])
    val stats: RDD[Stats] = new IOReader(hadoopConf, dataDirPath.toString).runIOTest(files)
    StatsAccumulator.accumulate(stats)

  }

  private def measure(testMode: TestMode, resFileName: String)(job: => Stats): Unit = {
    val startTime: Long = System.currentTimeMillis()
    val stats: Stats = job
    val execTime: Long = System.currentTimeMillis() - startTime
    analyzeResult(testMode, execTime, stats, resFileName)
  }

  private def analyzeResult(testMode: TestMode, execTime: Long, stats: Stats, resFileName: String): Unit = {
    val med: Float = stats.rate / 1000 / stats.tasks
    val stdDev = math.sqrt(math.abs(stats.sqRate / 1000 / stats.tasks - med * med))
    val resultLines: String =
      s"""
        |----- TestDFSIO ----- : ${testMode.command}
        |           Date & time: ${new Date(System.currentTimeMillis())}
        |       Number of files: ${stats.tasks}
        |Total MBytes processed: ${stats.size / 0x100000}
        |     Throughput mb/sec: ${stats.size * 1000.0 / (stats.time * 0x100000)}
        |Average IO rate mb/sec: $med
        | IO rate std deviation: $stdDev
        |    Test exec time sec: ${execTime.toFloat / 1000}
        |
      """.stripMargin
    logger.info(resultLines)
    appendToResultFile(resFileName, resultLines)
  }

  private def getFileName(fileIndex: Int): String = BaseFileName + fileIndex

  private def getCompressionCodecClass(codec: String): String = getCompressionCodec(codec).getName

  private def getCompressionCodec(codec: String): Class[_ <: CompressionCodec] =
    codec match {
      case "gzip" => classOf[GzipCodec]
      case "snappy" => classOf[SnappyCodec]
      case "lz4" => classOf[Lz4Codec]
      case "bzip2" => classOf[BZip2Codec]
    }

  private def withPrintWriter[A](name: String, append: Boolean = true)(func: PrintWriter => A): A = {
    val writer = new PrintWriter(new BufferedWriter(new FileWriter(name, append)))
    try {
      func(writer)
    } finally {
      writer.close()
    }
  }

  private def appendToResultFile(resFileName: String, resultLines: String): Unit =
    withPrintWriter(resFileName) { writer =>
      writer.println(resultLines)
    }

}
