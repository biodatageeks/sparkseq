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

package pl.elka.pw.sparkseq.junctions

import pl.elka.pw.sparkseq.seqAnalysis.SparkSeqAnalysis
import org.apache.spark.rdd.RDD

/**
 * Created by mwiewiorka on 4/4/14.
 */

/**
 * Class for junction reads analysis
 * @param seqAnalysis
 */
class SparkSeqJunctionAnalysis(seqAnalysis: SparkSeqAnalysis) extends Serializable {

  private val jSeqAnalysis = seqAnalysis
  private var gapReads: RDD[(Int, net.sf.samtools.SAMRecord)] = _

  private def findGapReads() = {

    //reads with one gap
    val cigRegex = "^[0-9]+[MEQISXDHP]+[0-9]+N[0-9]+[MEQISXDHP]{1,1}$".r

    jSeqAnalysis.filterCigarString(cigRegex.pattern.matcher(_).matches)

  }

  /**
   * Get junctions counts across all samples in SparkSeqAnalysis object
   * @return ( (sampleID,chrName,start,End), count )
   */
  /*  def getJuncReadsCounts() : RDD[((Int,String,Int,Int),Int)]= {

      val juncReads = jSeqAnalysis.getReads()
        .map(r=>((r._1, r._2.getReferenceName,r._2.getAlignmentStart, r._2.getCigar),1) )

    }*/
}
