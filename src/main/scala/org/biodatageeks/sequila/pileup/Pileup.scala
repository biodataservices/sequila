package org.biodatageeks.sequila.pileup

import htsjdk.samtools.SAMRecord
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.biodatageeks.sequila.datasources.BAM.BDGAlignFileReaderWriter
import org.biodatageeks.sequila.datasources.InputDataType
import org.biodatageeks.sequila.inputformats.BDGAlignInputFormat
import org.biodatageeks.sequila.pileup.conf.Conf
import org.biodatageeks.sequila.pileup.partitioning.{PartitionUtils, RangePartitionCoalescer}
import org.biodatageeks.sequila.utils.{InternalParams, TableFuncs}
import org.seqdoop.hadoop_bam.CRAMBDGInputFormat
import org.slf4j.LoggerFactory

import scala.reflect.ClassTag
import collection.JavaConverters._

class Pileup[T<:BDGAlignInputFormat](spark:SparkSession)(implicit c: ClassTag[T]) extends BDGAlignFileReaderWriter[T] {
  val logger = LoggerFactory.getLogger(this.getClass.getCanonicalName)

  def handlePileup(tableName: String, sampleId: String, refPath:String, output: Seq[Attribute], conf: Conf): RDD[InternalRow] = {
    logger.info(s"Calculating pileup on table: $tableName")
    logger.info(s"Current configuration\n$conf")
    spark.sqlContext.getConf(InternalParams.IOReadAlignmentMethod,"disq").toLowerCase

    lazy val allAlignments = readTableFile(name=tableName, sampleId)
    val bdConf = spark
      .sparkContext
      .broadcast(conf)
    val alignments = filterAlignments(allAlignments, bdConf )
    val repartitionedAlignments = repartitionAlignments(alignments)
    PileupMethods.calculatePileup(repartitionedAlignments, spark, refPath, bdConf)

  }

  private def repartitionAlignments (alignments:RDD[SAMRecord]): RDD[SAMRecord] ={
    val numPartitions = alignments.getNumPartitions
    val maxEndIndex = (List.range(1, numPartitions) :+ (numPartitions-1)).map(r=> new Integer(r )).asJava

    val alignments2 = alignments.coalesce(alignments.getNumPartitions,false, Some(new RangePartitionCoalescer(maxEndIndex )) )

    val lowerBounds = PartitionUtils.getPartitionLowerBound(alignments) // get the start of first read in partition
    //lowerBounds.foreach(l => println(s"Partition lower lowerBounds ${l.idx} => ${l.record.getContig},${l.record.getAlignmentStart}"))
    val adjBounds = PartitionUtils.getAdjustedPartitionBounds(lowerBounds)
    //adjBounds.foreach(l => println(s"Partition ${l.idx} => ${l.contigStart},${l.postStart} -- ${l.contigEnd},${l.posEnd}"))
    val broadcastBounds = spark.sparkContext.broadcast(adjBounds)
    val adjustedAlignments = alignments2.mapPartitionsWithIndex {
      (i, p) => {
        val bounds = broadcastBounds.value(i)
        p.takeWhile(r =>
          if (r.getReadUnmappedFlag) true
          else if (r.getContig == bounds.contigStart && r.getAlignmentStart >= bounds.postStart && r.getAlignmentEnd <= bounds.posEnd) true
          else
            false)
      }
    }
    adjustedAlignments
  }

  private def filterAlignments(alignments:RDD[SAMRecord], conf : Broadcast[Conf]): RDD[SAMRecord] = {
    // any other filtering conditions should go here
    val filterFlag = spark.conf.get(InternalParams.filterReadsByFlag, conf.value.filterFlag).toInt
    val cleaned = alignments.filter(read => read.getContig != null && (read.getFlags & filterFlag) == 0)
    if(logger.isDebugEnabled()) logger.debug("Processing {} cleaned reads in total", cleaned.count() )
    cleaned
  }

  def readTableFile(name: String, sampleId: String): RDD[SAMRecord] = {
    val metadata = TableFuncs.getTableMetadata(spark, name)
    val path = metadata.location.toString

    val samplePathTemplate = (
      path
      .split('/')
      .dropRight(1) ++ Array(s"$sampleId*.{{fileExtension}}"))
      .mkString("/")

    metadata.provider match {
      case Some(f) =>
        if (f == InputDataType.BAMInputDataType)
           readBAMFile(spark.sqlContext, samplePathTemplate.replace("{{fileExtension}}", "bam"), refPath = None)
        else if (f == InputDataType.CRAMInputDataType) {
          val refPath = spark.sqlContext
            .sparkContext
            .hadoopConfiguration
            .get(CRAMBDGInputFormat.REFERENCE_SOURCE_PATH_PROPERTY)
           readBAMFile(spark.sqlContext, samplePathTemplate.replace("{{fileExtension}}", "cram"), Some(refPath))
        }
        else throw new Exception("Only BAM and CRAM file formats are supported in coverage.")
      case None => throw new Exception("Wrong file extension - only BAM and CRAM file formats are supported in coverage.")
    }


  }
}
