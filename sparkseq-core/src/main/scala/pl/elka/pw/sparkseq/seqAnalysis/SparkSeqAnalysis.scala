/**
 * Copyright (c) 2014. Marek Wiewiorka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.elka.pw.sparkseq.seqAnalysis

import org.apache.spark.SparkContext
import SparkContext._
import org.apache.spark.rdd._
import org.apache.spark._
import org.seqdoop.hadoop_bam.BAMInputFormat
import org.seqdoop.hadoop_bam.SAMRecordWritable
import org.apache.hadoop.io.LongWritable
import scala.util.control._
import scala.collection.mutable.ArrayBuffer
import pl.elka.pw.sparkseq.conversions.SparkSeqConversions
import java.io.{File, PrintWriter}
import pl.elka.pw.sparkseq.util.SparkSeqRegType._
import htsjdk.samtools.SAMRecord
import htsjdk.samtools.SAMUtils._
import scala.Function._
import com.sun.org.apache.xpath.internal.operations.Bool

/**
 * Main class for analysis of sequencing data. A SparkSeqAnalysis holds Apache Spark context as well as references
 * to BAM files containing NGS data.
 *
 * @param iSC Apache Spark context.
 * @param iBAMFile  Path to the first BAM file.
 * @param iSampleId  Id of the firs sample (must be numeric).
 * @param iNormFactor  Normalization factor for doing count normalization between samples.
 * @param iReduceWorkers  Number of Reduce workers for doing transformations such as sort or join (see
 *                        http://spark.incubator.apache.org/docs/latest/scala-programming-guide.html for details).
 */
class SparkSeqAnalysis(iSC: SparkContext, iBAMFile: String, iSampleId: Int, iNormFactor: Double, iReduceWorkers: Int = 8) extends Serializable {

  /* Spark context parameters defaults */
  /*  val workerMem = iWorkerMem getOrElse "6g"
    val serializer = iSerializer getOrElse "spark.KryoSerializer"
    val masterConnString = iMasterConnString getOrElse "local"
    val sparkHome = iSparkHome getOrElse "/opt/spark"*/

  //val sc = iSC
  /**
   * References to all samples in the analysis.
   */
  var bamFile = iSC.newAPIHadoopFile[LongWritable, SAMRecordWritable, BAMInputFormat](iBAMFile).map(r => (iSampleId, r._2.get))


  private var regionCovRDD: RDD[(Long, Int)] = _
  private var baseCovRDD: RDD[(Long, Int)] = _

  private var samplesID = new ArrayBuffer[Int]()
  samplesID += iSampleId

  private var samplePaths = new ArrayBuffer[String]()
  private var samplePathsRDD = iSC.makeRDD(samplePaths)
  samplePaths += iBAMFile

  private var bamFileFilter = bamFile

  private var bamFileUndo = bamFileFilter

  private var regionMap: org.apache.spark.broadcast.Broadcast[scala.collection.mutable.
  HashMap[String, Array[scala.collection.mutable.ArrayBuffer[(String, String, Int, Int)]]]] = _
  /**
   * Number of samples (defaults to 1)
   */
  var sampleNum = 1

  private var normFactor = scala.collection.mutable.HashMap[Int, Double]()
  normFactor(iSampleId) = iNormFactor

  //private def reads
  /**
   * Method for generating bases coordinates that a given read is alligned to using its Cigar string.
   *
   * @param iAlignStart Start of a read alignment
   * @param iCigar Cigar string of a read aligments
   * @return Array of ranges computed from Cigar string.
   */
  private def genBasesFromCigar(iAlignStart: Int, iCigar:htsjdk.samtools.Cigar): Array[Range] = {

    var nuclReadArray = ArrayBuffer[Range]()
    val numCigElem = iCigar.numCigarElements()


    var nuclShift = 0
    for (i <- 0 to (numCigElem - 1)) {
      var cElem = iCigar.getCigarElement(i)
      //first mapped read fragment
      if (cElem.getOperator().toString() == "M" && i == 0 || (i == 1 && iCigar.getCigarElement(0).getOperator().toString() == "S"))
      //nuclReadArray=Array.range(iAlignStart,iAlignStart+cElem.getLength()+1)
        nuclReadArray += Range(iAlignStart, iAlignStart + cElem.getLength() + 1)
      //find maps in between
      else if (cElem.getOperator().toString() != "M")
        nuclShift += cElem.getLength()
      else if (cElem.getOperator().toString() == "M" && i > 1 && i < (numCigElem - 1) && nuclReadArray.length > 0) {
        var mapStr = nuclReadArray.last.last + nuclShift
        //nuclReadArray=Array.concat(nuclReadArray,Array.range(mapStr,mapStr+cElem.getLength()+1))
        nuclReadArray += Range(mapStr, mapStr + cElem.getLength() + 1)
        nuclShift = 0
      }
      //last mapped read fragment
      else if (cElem.getOperator().toString() == "M" && i == (numCigElem - 1) && nuclReadArray.length > 0)
      //nuclReadArray=Array.concat(nuclReadArray,Array.range(nuclReadArray.last+nuclShift,nuclReadArray.last+nuclShift+cElem.getLength()+1))
        nuclReadArray += Range(nuclReadArray.last.last + nuclShift, nuclReadArray.last.last + nuclShift + cElem.getLength() + 1)
    }
    return nuclReadArray.toArray

  }


  /**
   * Method for adding another BAM files to intance of SparkSeqAnalysis class.
   *
   * @param iSC Apache Spark context.
   * @param iBAMFile  Path to the first BAM file.
   * @param iSampleId  Id of the firs sample (must be numeric).
   * @param iNormFactor  Normalization factor for doing count normalization between samples.
   *
   */
  def addBAM(iSC: SparkContext, iBAMFile: String, iSampleId: Int, iNormFactor: Double) {
    bamFile = bamFile ++ iSC.newAPIHadoopFile[LongWritable, SAMRecordWritable, BAMInputFormat](iBAMFile).map(r => (iSampleId, r._2.get()))
    bamFileUndo
    normFactor(iSampleId) = iNormFactor
    bamFileFilter = bamFile
    bamFileUndo = bamFileFilter
    sampleNum += 1
    samplesID += iSampleId
    samplePaths += iBAMFile
    samplePathsRDD.+(iBAMFile)
  }

  /**
   * Method for computing coverage for a given list of genetic regions.
   *
   * @param iGenExons A Spark broadcast variable created from BED file that is transformed using SparkSeqConversions.BEDFileToHashMap
   * @param unionMode If set to true reads overlapping more than one region are discarded (false by default). More info on union mode:
   *                  http://www-huber.embl.de/users/anders/HTSeq/doc/count.html#count
   * @return RDD of tuples (regionId, coverage)
   */
  def getCoverageRegion(iGenExons: org.apache.spark.broadcast.Broadcast[scala.collection.mutable.
  HashMap[String, Array[scala.collection.mutable.ArrayBuffer[(String, String, Int, Int)]]]], unionMode: Boolean = false): RDD[(Long, Int)] = {

    val coverage = (bamFileFilter.mapPartitions {
      partitionIterator =>
      //var exonsCountArray = new Array[(Long,Int)](3000000)
        var exonsCountMap = scala.collection.mutable.HashMap[Long, Int]()
        var sampleId: Long = 0
        var sampleIdRaw: Int = 0
        var exId = 0
        var refName: String = ""
        val pattern = "^[A-Za-z]*0*".r
        for (read <- partitionIterator) {
          sampleId = read._1 * 1000000000000L
          sampleIdRaw = read._1
          refName = read._2.getReferenceName match {
            case "Y" => "chrY"
            case "X" => "chrX"
            case _ => read._2.getReferenceName
          }
          if (iGenExons.value.contains(refName)) {
            var exons = iGenExons.value(refName)
            var basesFromRead = genBasesFromCigar(read._2.getAlignmentStart, read._2.getCigar)
            for (basesArray <- basesFromRead) {
              var subReadStart = basesArray.start
              var subReadEnd = basesArray.end
              var idReadStart = subReadStart / 10000
              var idReadEnd = subReadEnd / 10000
              var readStartArray = exons(idReadStart)
              if (idReadStart > 0 && readStartArray != null && exons(idReadStart - 1) != null)
                readStartArray = readStartArray ++ (exons(idReadStart - 1))
              else if (idReadStart > 0 && readStartArray == null && exons(idReadStart - 1) != null)
                readStartArray = exons(idReadStart - 1)
              val loop = new Breaks;
              val outloop = new Breaks;
              // if(idReadStart == idReadEnd ){
              if (readStartArray != null) {
                val exonsOverlap = new ArrayBuffer[Long]()
                var counter = 0
                outloop.breakable {
                  if (unionMode == true && counter > 1)
                    outloop.break
                  for (es <- readStartArray) {
                    loop.breakable {
                      for (r <- subReadStart to subReadEnd by 2) {
                        if (es._3 <= r && es._4 >= r) {
                          var id = sampleId + pattern.replaceAllIn(es._2, "").toInt * 100000L
                          exonsOverlap += id
                          counter += 1
                          loop.break
                        }

                      }
                    }
                  }
                }
                if (unionMode == true && counter == 1) {
                  val id = exonsOverlap(0)
                  if (!exonsCountMap.contains(id))
                    exonsCountMap((id)) = 1
                  else
                    exonsCountMap((id)) += 1
                }
                else if (unionMode == false && counter >= 1) {
                  for (e <- exonsOverlap) {
                    val id = e
                    if (!exonsCountMap.contains(id))
                      exonsCountMap((id)) = 1
                    else
                      exonsCountMap((id)) += 1
                  }
                }


              }
            }

          }
        }

        Iterator(exonsCountMap.mapValues(r => (math.round(r * normFactor(sampleIdRaw)).toInt)))
    }
      ).flatMap(r => r).reduceByKey(_ + _, iReduceWorkers)
    regionCovRDD = coverage
    return (coverage)
  }

  /**
   * Method for computing coverage of all bases.
   * @return RDD of tuples (genID, coverage)
   */
  def getCoverageBase(): RDD[(Long, Int)] = {
    val coverage = (bamFileFilter.mapPartitions {
      partitionIterator =>
        var sampleId = 0
        var id: Int = 0
        var i = 0
        var count = 0
        var minIndex = Int.MaxValue
        var maxIndex = 0
        var chrMap = scala.collection.mutable.HashMap[Long, Array[Array[Int]]]()
        var chrMin = scala.collection.mutable.HashMap[Long, Int]()
        var chrMax = scala.collection.mutable.HashMap[Long, Int]()
        val bufferSize = 150000
        var refName: String = ""
        var chNumCode: Long = 0
        //val nuclArray = new Array[Array[Int]](2000000)
        var countArray = new Array[(Long, Int)](15000000)
        var countArrayToReduce = new Array[(Long, Int)](300000)
        var outputArray = new Array[Array[(Long, Int)]](2)

        for (read <- partitionIterator) {
          sampleId = read._1
          refName = read._2.getReferenceName
          chNumCode = SparkSeqConversions.chrToLong(refName) + sampleId * 1000000000000L
          if (!chrMin.contains(chNumCode))
            chrMin(chNumCode) = Int.MaxValue
          if (chrMin(chNumCode) > read._2.getAlignmentStart)
            chrMin(chNumCode) = read._2.getAlignmentStart

          if (!chrMax.contains(chNumCode))
            chrMax(chNumCode) = 0
          if (chrMax(chNumCode) < read._2.getAlignmentEnd)
            chrMax(chNumCode) = read._2.getAlignmentEnd
          var basesFromRead = genBasesFromCigar(read._2.getAlignmentStart, read._2.getCigar)
          //new chr in reads
          if (!chrMap.contains(chNumCode))
            chrMap(chNumCode) = new Array[Array[Int]](2500000)
          for (basesArray <- basesFromRead) {
            for (rb <- basesArray) {
              //id = chNumCode+baseRange(j)
              id = (rb % 100)
              var idIn = rb / 100
              if (chrMap(chNumCode)(idIn) == null)
                chrMap(chNumCode)(idIn) = Array.fill(100)(0)
              chrMap(chNumCode)(idIn)(id) += 1
            }
          }

        }
        i = 0
        var k = 0
        for (chr <- chrMap) {
          for (j <- 0 to chr._2.length - 1) {
            if (chr._2(j) != null) {
              for (r <- 0 to 99) {
                if (chr._2(j)(r) > 0) {
                  var idx = j * 100 + r
                  if (idx <= (chrMin(chr._1) + bufferSize) || idx >= (chrMax(chr._1) - bufferSize)) {
                    countArrayToReduce(k) = ((chr._1 + idx, math.round(chr._2(j)(r) * normFactor(sampleId)).toInt))
                    k += 1
                  }
                  else {
                    countArray(i) = ((chr._1 + idx, math.round(chr._2(j)(r) * normFactor(sampleId)).toInt))
                    i += 1
                  }

                }
              }
            }

          }
        }
        outputArray(0) = countArray.filter(r => r != null)
        outputArray(1) = countArrayToReduce.filter(r => r != null)
        Iterator(outputArray)
    })
    val coverageToReduce = coverage.flatMap(r => (r.array(1))).reduceByKey(_ + _, iReduceWorkers)
    val coverageNotReduce = coverage.flatMap(r => (r.array(0)))
    bamFileFilter = bamFile
    baseCovRDD = coverageNotReduce.union(coverageToReduce)
    return (baseCovRDD)
  }

  /**
   * Method for computing coverage of all bases from a give chromosome region.
   *
   * @param chr Chromosome (eg. chr1)
   * @param regStart Starting position in a chromosome.
   * @param regEnd End position in a chromosome.
   * @return RDD of tuples (genID, coverage)
   */
  def getCoverageBaseRegion(chr: String, regStart: Int, regEnd: Int): RDD[(Long, Int)] = {
    //val chrCode = chrToLong(chr)
    if (chr == "*")
      bamFileFilter = bamFile.filter(r => r._2.getAlignmentStart() >= regStart && r._2.getAlignmentEnd() <= regEnd)
    else {
      val chrT = SparkSeqConversions.trimLetterChr(chr)
      bamFileFilter = bamFile.filter(r => r._2.getReferenceName() == chrT && r._2.getAlignmentStart() >= regStart && r._2.getAlignmentEnd() <= regEnd)
    }

    return (getCoverageBase())

  }

  /**
   * Get all reads from all samples  in format (sampleId,ReadObject)
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def getReads(): RDD[(Int, htsjdk.samtools.SAMRecord)] = {

    return bamFileFilter
  }

  /**
   * Get all reads for a specific sample in format (sampleId,ReadObject)
   * @param sampleID ID of a given sample
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def getSampleReads(sampleID: Int): RDD[(Int, htsjdk.samtools.SAMRecord)] = {

    return getReads().filter(r => r._1 == sampleID)
  }

  /**
   * Set reads of SeqAnalysis object, e.g. after external filtering
   * @param reads RDD of (sampleID,ReadObject)
   */
  def setReads(reads: RDD[(Int, htsjdk.samtools.SAMRecord)]) = {

    bamFileFilter = reads
  }

  /**
   * Generic method for filtering out all reads using the condition provided: _.1 refers to sampleID, _.2 to ReadObject .
   * @param filterCond ((Int, htsjdk.samtools.SAMRecord)) => Boolean
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterReads(filterCond: ((Int, htsjdk.samtools.SAMRecord)) => Boolean): RDD[(Int, htsjdk.samtools.SAMRecord)] = {

    bamFileFilter = bamFileFilter.filter(filterCond)
    return bamFileFilter
  }

  def filterReads(filterCond: ((Int, htsjdk.samtools.SAMRecord), (Int, htsjdk.samtools.SAMRecord)) => Boolean): RDD[(Int, htsjdk.samtools.SAMRecord)] = {

    bamFileFilter = bamFileFilter.filter(r => filterCond(r, r))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the sample id
   * @param sampleCond  Condition on the sample id
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterSample(sampleCond: (Int => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {

    bamFileFilter = bamFileFilter.filter(r => sampleCond(r._1))
    return bamFileFilter
  }

  def filterSample(sampleCond: ((Int, Int) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {

    bamFileFilter = bamFileFilter.filter(r => sampleCond(r._1, r._1))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the quality of mapping
   * @param qaulityCond - Conditions on the quality of read mapping
   * @return RDD[(Int, htsjdk.samtools.SAMRecord]
   */
  def filterMappingQuality(qaulityCond: (Int => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {

    bamFileFilter = bamFileFilter.filter(r => qaulityCond(r._2.getMappingQuality))
    return bamFileFilter
  }

  def filterMappingQuality(qaulityCond: ((Int, Int) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {

    bamFileFilter = bamFileFilter.filter(r => qaulityCond(r._2.getMappingQuality, r._2.getMappingQuality))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the base qualities of a given read
   * @param baseQualCond  Conditions on the quality of read bases
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterBaseQualities(baseQualCond: (Array[Int] => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => baseQualCond(r._2.getBaseQualityString.toCharArray.map(r => fastqToPhred(r))))
    return bamFileFilter
  }

  def filterBaseQualities(baseQualCond: ((Array[Int], Array[Int]) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => baseQualCond(r._2.getBaseQualityString.toCharArray.map(r => fastqToPhred(r)),
      r._2.getBaseQualityString.toCharArray.map(r => fastqToPhred(r))))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the reference name
   * @param refNameCond Condition on reference name
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterReferenceName(refNameCond: (String => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => refNameCond(SparkSeqConversions.standardizeChr(r._2.getReferenceName)))
    return bamFileFilter
  }

  def filterReferenceName(refNameCond: ((String, String) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => refNameCond(SparkSeqConversions.standardizeChr(r._2.getReferenceName), SparkSeqConversions.standardizeChr(r._2.getReferenceName)))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the start of alignment
   * @param alignStartCond  Condition on the start of the alignment
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterAlignmentStart(alignStartCond: (Int => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => alignStartCond(r._2.getAlignmentStart))
    return bamFileFilter
  }

  def filterAlignmentStart(alignStartCond: ((Int, Int) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => alignStartCond(r._2.getAlignmentStart, r._2.getAlignmentStart))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the end of alignment
   * @param alignEndCond  Condition on the end of the alignment
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterAlignmentEnd(alignEndCond: (Int => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => alignEndCond(r._2.getAlignmentEnd))
    return bamFileFilter
  }

  def filterAlignmentEnd(alignEndCond: ((Int, Int) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => alignEndCond(r._2.getAlignmentEnd, r._2.getAlignmentEnd))
    return bamFileFilter
  }

  /**
   * Generic method for filtering reads using conditions on the merged flags.
   * More info http://picard.sourceforge.net/explain-flags.html
   * @param flagCond Condition on the merged flags
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterFlags(flagCond: (Int => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => flagCond(r._2.getFlags))
    return bamFileFilter
  }

  def filterFlags(flagCond: ((Int, Int) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => flagCond(r._2.getFlags, r._2.getFlags))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the mapped flag
   * @param unmapFlagCond Condition on the end of the mapped flag
   * @return RDD[(Int, nhtsjdk.samtools.SAMRecord)]
   */
  def filterUnmappedFlag(unmapFlagCond: (Boolean => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => unmapFlagCond(r._2.getReadUnmappedFlag))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the duplicate flag
   * @param dupFlagCond Condition on the end of the duplicate flag
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterDuplicateReadFlag(dupFlagCond: (Boolean => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => dupFlagCond(r._2.getDuplicateReadFlag))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the primary flag
   * @param notPrimFlagCond Condition on the end of the primary flag
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterNotPrimaryAlignFlag(notPrimFlagCond: (Boolean => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => notPrimFlagCond(r._2.getNotPrimaryAlignmentFlag))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the read name
   * @param readNameCond Condition on the end of the read name
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterReadName(readNameCond: (String => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => readNameCond(r._2.getReadName))
    return bamFileFilter
  }

  def filterReadName(readNameCond: ((String, String) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => readNameCond(r._2.getReadName, r._2.getReadName))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the read length
   * @param readLengthCond Condition on the end of the read length
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterReadLength(readLengthCond: (Int => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => readLengthCond(r._2.getReadLength))
    return bamFileFilter
  }

  def filterReadLength(readLengthCond: ((Int, Int) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => readLengthCond(r._2.getReadLength, r._2.getReadLength))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the CIGAR string
   * @param cigarStringCond Condition on the end of the CIGAR string
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterCigarString(cigarStringCond: (String => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => cigarStringCond(r._2.getCigarString))
    return bamFileFilter
  }

  def filterCigarString(cigarStringCond: ((String, String) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => cigarStringCond(r._2.getCigarString, r._2.getCigarString))
    return bamFileFilter
  }


  /**
   * Method for filtering reads using conditions on the CIGAR object
   * @param cigarCond Condition on the end of the CIGAR object
   * @return RDD[(Int,htsjdk.samtools.SAMRecord)]
   */
  def filterCigar(cigarCond: (htsjdk.samtools.Cigar => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => cigarCond(r._2.getCigar))
    return bamFileFilter
  }

  def filterCigar(cigarCond: ((htsjdk.samtools.Cigar, htsjdk.samtools.Cigar) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => cigarCond(r._2.getCigar, r._2.getCigar))
    return bamFileFilter
  }

  /**
   * Method for filtering reads using conditions on the alignment length covered by read incl. gaps
   * @param alignLenCond Condition on the end of the alignmrnent length covered by read incl. gaps
   * @return RDD[(Int, htsjdk.samtools.SAMRecord)]
   */
  def filterAlignmentLength(alignLenCond: (Int => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => alignLenCond(r._2.getCigar.getPaddedReferenceLength))
    return bamFileFilter
  }

  def filterAlignmentLength(alignLenCond: ((Int, Int) => Boolean)): RDD[(Int, htsjdk.samtools.SAMRecord)] = {
    bamFileFilter = bamFileFilter.filter(r => alignLenCond(r._2.getCigar.getPaddedReferenceLength, r._2.getCigar.getPaddedReferenceLength))
    return bamFileFilter
  }

  private def coverageRDDToFile(iRDD: RDD[(Long, Int)], iRegType: SparkSeqRegType = Exon, iFile: String) = {
    val regionCollect = iRDD
      .map(r => (SparkSeqConversions.splitSampleID(r._1), r._2))
      .map(r => (r._1._2, (r._1._1, r._2)))
      .groupByKey()
      .sortByKey()
      .mapValues(r => r.toSeq.sortBy(r => r._1))
      .collect()
    var samplesHeader: String = ""
    val samplesIDSort = samplesID.sortBy(r => r)
    for (i <- samplesIDSort)
      samplesHeader += ("Sample_" + i.toString).padTo(10, ' ')
    val fileHeader = "Feature".padTo(25, ' ') + samplesHeader + "\n"
    var writer = new PrintWriter(new File(iFile))
    writer.write(fileHeader)
    for (r <- regionCollect) {
      var feature: String = ""
      if (iRegType == Exon)
        feature = SparkSeqConversions.ensemblRegionIdToExonId(r._1, Exon)
      else if (iRegType == Gene)
        feature = SparkSeqConversions.ensemblRegionIdToExonId(r._1, Gene)
      else if (iRegType == Base) {
        val posTup = SparkSeqConversions.idToCoordinates(r._1)
        feature = posTup._1 + "," + posTup._2.toString
      }
      var sampleData: String = feature.padTo(25, ' ')
      val rData = r._2
      val loop = new Breaks
      for (i <- samplesIDSort) {
        loop.breakable {
          for (s <- rData) {
            if (s._1 == i) {
              sampleData += s._2.toString.padTo(10, ' ')
              loop.break()
            }
            else if (s == rData.last)
              sampleData += 0.toString.padTo(10, ' ')
          }

        }

      }
      writer.write(sampleData + "\n")
    }

    writer.close()
  }

  protected def coverageRDDToTable(iRDD: RDD[(Long, Int)], iRegType: SparkSeqRegType = Exon) = {
    val regionCollect = iRDD
      .map(r => (SparkSeqConversions.splitSampleID(r._1), r._2))
      .map(r => (r._1._2, (r._1._1, r._2)))
      .groupByKey()
      .sortByKey()
      .mapValues(r => r.toSeq.sortBy(r => r._1))
      .collect()
    var samplesHeader: String = ""
    val samplesIDSort = samplesID.sortBy(r => r)
    for (i <- samplesIDSort)
      samplesHeader += ("Sample_" + i.toString).padTo(10, ' ')
    val tabHeader = "\n" + "Feature".padTo(25, ' ') + samplesHeader + "\n"
    print(tabHeader)
    println("".padTo(tabHeader.length, '='))
    for (r <- regionCollect) {
      var feature: String = ""
      if (iRegType == Exon)
        feature = SparkSeqConversions.ensemblRegionIdToExonId(r._1, Exon)
      else if (iRegType == Gene)
        feature = SparkSeqConversions.ensemblRegionIdToExonId(r._1, Gene)
      else if (iRegType == Base) {
        val posTup = SparkSeqConversions.idToCoordinates(r._1)
        feature = posTup._1 + "," + posTup._2.toString
      }
      var sampleData: String = feature.padTo(25, ' ')
      val rData = r._2
      val loop = new Breaks
      for (i <- samplesIDSort) {
        loop.breakable {
          for (s <- rData) {
            if (s._1 == i) {
              sampleData += s._2.toString.padTo(10, ' ')
              loop.break()
            }
            else if (s == rData.last)
              sampleData += 0.toString.padTo(10, ' ')
          }

        }

      }
      print(sampleData + "\n")
    }
  }


  /**
   * Method for saving feature counts to a file with samples in columns and feature in rows.
   * @param iFile Path to a file.
   */
  def saveFeatureCoverageToFile(iFile: String, iRegType: SparkSeqRegType = Exon) = {
    if (regionCovRDD != None) {
      coverageRDDToFile(regionCovRDD, iRegType, iFile)
    }
    else
      println("Run getCoverageRegion method first!")
  }


  protected def getCoverageTable(iRDD: RDD[(Long, Int)], iRegType: SparkSeqRegType): Array[(String, Array[Int])] = {
    val regionCoverageTable = new Array[(String, Array[Int])](iRDD.count().toInt)
    val regionCollect = iRDD
      .map(r => (SparkSeqConversions.splitSampleID(r._1), r._2))
      .map(r => (r._1._2, (r._1._1, r._2)))
      .groupByKey()
      .sortByKey()
      .mapValues(r => r.toSeq.sortBy(r => r._1))
      .collect()
    val samplesIDSort = samplesID.sortBy(r => r)
    var i = 0
    for (r <- regionCollect) {
      var feature: String = ""
      if (iRegType == Exon)
        feature = SparkSeqConversions.ensemblRegionIdToExonId(r._1, Exon)
      else if (iRegType == Gene)
        feature = SparkSeqConversions.ensemblRegionIdToExonId(r._1, Gene)
      else
        feature = SparkSeqConversions.idToCoordinates(r._1).toString()
      var sampleData = new ArrayBuffer[Int]()
      val rData = r._2
      val loop = new Breaks
      for (i <- samplesIDSort) {
        loop.breakable {
          for (s <- rData) {
            if (s._1 == i) {
              sampleData += s._2
              loop.break()
            }
            else if (s == rData.last)
              sampleData += 0
          }

        }

      }
      regionCoverageTable(i) = (feature, sampleData.toArray)
      i += 1
    }
    return regionCoverageTable
  }

  /**
   * Get all genes counts for all samples.  Foe each gene it returns tuple with geneID and sorted by sampleID feature counts
   * @param iGenExons A Spark broadcast variable created from BED file that is transformed using SparkSeqConversions.BEDFileToHashMap
   * @param unionMode If set to true reads overlapping more than one region are discarded (false by default). More info on union mode:
   *                  http://www-huber.embl.de/users/anders/HTSeq/doc/count.html#count
   * @return (geneId,Array(count))
   */
  def getGeneCoverage(iGenExons: org.apache.spark.broadcast.Broadcast[scala.collection.mutable.
  HashMap[String, Array[scala.collection.mutable.ArrayBuffer[(String, String, Int, Int)]]]], unionMode: Boolean = false): Array[(String, Array[Int])] = {

    return getCoverageTable(getCoverageRegion(iGenExons, unionMode), Gene)
  }


  /**
   * Get all exons counts for all samples.  Foe each gene it returns tuple with exonID and sorted by sampleID feature counts
   * @param iGenExons A Spark broadcast variable created from BED file that is transformed using SparkSeqConversions.BEDFileToHashMap
   * @param unionMode If set to true reads overlapping more than one region are discarded (false by default). More info on union mode:
   *                  http://www-huber.embl.de/users/anders/HTSeq/doc/count.html#count
   * @return
   */
  def getExonCoverage(iGenExons: org.apache.spark.broadcast.Broadcast[scala.collection.mutable.
  HashMap[String, Array[scala.collection.mutable.ArrayBuffer[(String, String, Int, Int)]]]], unionMode: Boolean = false): Array[(String, Array[Int])] = {

    return getCoverageTable(getCoverageRegion(iGenExons, unionMode), Exon)
  }


  /**
   * Method that displays all samples coverage(counts) for the specified exons
   * @param exArray Array of exons
   */
  def viewExonCoverage(exArray: Array[String]) = {
    if (regionCovRDD != None) {
      regionCovRDD.cache()
      val regionCovFilterRDD = regionCovRDD
        .filter(r => exArray.contains(SparkSeqConversions.ensemblRegionIdToExonId(r._1, Exon)))
      coverageRDDToTable(regionCovFilterRDD, Exon)
    }
    else
      println("Run getCoverageRegion method first!")
  }

  /**
   * Method that displays all samples coverage(counts) for the specified regions
   */
  def viewRegionCoverage(exArray: Array[String]) = {
    viewExonCoverage(exArray)
  }

  /**
   * Method that displays all samples coverage(counts) for the specified genes
   * @param genArray Array of gene names
   */
  def viewGeneCoverage(genArray: Array[String]) = {
    if (regionCovRDD != None) {
      regionCovRDD.cache()
      val regionCovFilterRDD = regionCovRDD
        .filter(r => genArray.contains(SparkSeqConversions.ensemblRegionIdToExonId(r._1, Gene)))
      coverageRDDToTable(regionCovFilterRDD, Gene)
    }
    else
      println("Run getCoverageRegion method first!")
  }


  /**
   * Method for saving base counts to a file with samples in columns and base positions in rows.
   * @param iFile
   */
  def saveBaseCoverageToFile(iFile: String) = {
    if (baseCovRDD != None) {
      coverageRDDToFile(baseCovRDD, Base, iFile)
    }
    else
      println("Run getCoverageBase method first!")

  }

  /**
   * Method that displays all samples coverage for the specified region at base resolution
   * @param iChr chromosome
   * @param iStartPos start position
   * @param iEndPos end position
   */
  def viewBaseCoverage(iChr: String, iStartPos: Int, iEndPos: Int) = {
    if (baseCovRDD != None) {
      baseCovRDD.cache()
      val baseCovFilterRDD = baseCovRDD
        .filter {
        r => val coord = SparkSeqConversions.idToCoordinates(r._1); coord._1 == iChr && coord._2 >= iStartPos && coord._2 <= iEndPos
      }
      coverageRDDToTable(baseCovFilterRDD, Base)
    }
    else
      println("Run getCoverageBase method first!")

  }

  /**
   *
   * Method that returns all the samples paths that are attached to the analysis
   * @return Array of paths.
   */
  def getSamples(): RDD[String] = {

    return samplePathsRDD
  }

  /**
   *
   * Method that lists all the samples paths that are attached to the analysis
   */
  def listSamples() = {
    for (s <- samplePaths)
      println(s)
  }

  /**
   * Method that displays sample reads(or first @iNum reads ) after optional applying all the filters specified
   * @param sampleID ID of a given sample
   * @param iNum Optionally the number of reads to display
   */
  def viewSampleReads(sampleID: Int, iNum: Int) = {
    val n = (Option(iNum) getOrElse 0)
    if (n > 0)
      getSampleReads(sampleID).take(n).foreach(r => println(r._2.getSAMString))
    else
      getSampleReads(sampleID).collect.foreach(r => println(r._2.getSAMString))

  }

  /**
   * Method that displays all reads(or first @iNum reads ) after optional applying all the filters specified
   * @param iNum Optionally the number of reads to display
   */
  def viewReads(iNum: Int = Int.MaxValue) = {
    if (iNum == Int.MaxValue)
      getReads.collect.foreach(r => println(r._2.getSAMString))
    else
      getReads.take(iNum).foreach(r => println(r._2.getSAMString))

  }

  /**
   * Method that undoes all the filtering done to all samples
   */
  def undoFilter() = {
    bamFileFilter = bamFileUndo
  }

  /**
   * Method that return mean read length of all samples
   * @return mean read length
   */
  def getAvgReadLength(): Double = {
    val stat = bamFileFilter
      .map(r => (1, (1, r._2.getReadLength)))
      .reduceByKey((a, b) => (a._1 + b._1, a._2 + b._2))
      .map(r => (r._2))
    return stat.first._2.toDouble / stat.first._1.toDouble
  }

  /**
   * Sort all reads from all the samples by alignment start
   * @param iAsc If results should be sorted ascending (by default)
   * @return RDD of((sampleID,(chrName,alignStart)),read object)
   */
  def sortReadsByAlign(iAsc: Boolean = true): RDD[((Int, (String, Int)), htsjdk.samtools.SAMRecord)] = {

    val sortReads = getReads
      .map(r => ((r._1, SparkSeqConversions.chrToLong(r._2.getReferenceName), r._2.getAlignmentStart), r._2))
      .sortByKey(iAsc)
      .map(r => ((((r._1._1, (SparkSeqConversions.idToCoordinates(r._1._2)._1, r._1._3)), r._2))))
    return sortReads

  }

  /**
   * Sorts all reads from a given sample  by alignment start
   * @param iSampleID SampleID of a given sample
   * @param iAsc If results should be sorted ascending (by default)
   * @return RDD of((sampleID,(chrName,alignStart)),read object)
   */
  def sortSampleReadsByAlign(iSampleID: Int, iAsc: Boolean = true): RDD[((Int, (String, Int)), htsjdk.samtools.SAMRecord)] = {

    val sortReads = getSampleReads(iSampleID)
      .map(r => ((r._1, SparkSeqConversions.chrToLong(r._2.getReferenceName), r._2.getAlignmentStart), r._2))
      .sortByKey(iAsc)
      .map(r => ((((r._1._1, (SparkSeqConversions.idToCoordinates(r._1._2)._1, r._1._3)), r._2))))
    return sortReads
  }

  /**
   * Sets region map
   * @param iSC SparkContext object
   * @param bedFilePath Path to BED file (accesible by all nodes in the Spark cluster)
   * @return region hashmap
   */
  def setRegionMap(iSC: SparkContext, bedFilePath: String) = {
    regionMap = iSC.broadcast(SparkSeqConversions.BEDFileToHashMap(iSC, bedFilePath))

  }

  /**
   * Get region map
   * @return region hashmap
   */
  def getRegionMap(): org.apache.spark.broadcast.Broadcast[scala.collection.mutable.
  HashMap[String, Array[scala.collection.mutable.ArrayBuffer[(String, String, Int, Int)]]]] = {

    return regionMap
  }

  /**
   * Get array of samplesID
   * @return array of samplesID
   */
  def getSamplesID(): Array[Int] = {
    return samplesID.toArray.sortBy(r => r)
  }


}