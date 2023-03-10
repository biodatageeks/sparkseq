/**
 * Copyright (c) 2014. Marek Wiewiorka
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
package pl.elka.pw.sparkseq.serialization

import com.esotericsoftware.kryo.Kryo

/**
 * Class for registering various classes with KryoSerializer.
 */
class SparkSeqKryoRegistrator extends org.apache.spark.serializer.KryoRegistrator {
  /**
   * Method for registering various classes with KryoSerializer.
   * @param kryo
   */
  override def registerClasses(kryo: Kryo) {
    kryo.register(classOf[org.seqdoop.hadoop_bam.SAMRecordWritable])
    kryo.register(classOf[htsjdk.samtools.Cigar])
    kryo.register(classOf[org.seqdoop.hadoop_bam.BAMInputFormat])
    kryo.register(classOf[org.apache.hadoop.io.LongWritable])
    kryo.register(classOf[htsjdk.samtools.BAMRecord])
    //kryo.register(classOf[pl.elka.pw.sparkseq.differentialExpression.SparkSeqDiffExpr])
    //kryo.register(classOf[scala.collection.Traversable[_]], new ScalaCollectionSerializer(kryo))
    //kryo.register(classOf[scala.Product], new ScalaProductSerializer(kryo))
   }
}
