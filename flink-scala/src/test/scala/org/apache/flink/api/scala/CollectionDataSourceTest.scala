/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.flink.api.scala

import org.apache.flink.api.java.record.operators.{CollectionDataSource => JCollectionDataSource}
import org.apache.flink.types.{DoubleValue, Record}
import org.scalatest.junit.AssertionsForJUnit
import org.junit.Assert._
import org.junit.Test


class CollectionDataSourceTest extends AssertionsForJUnit {
  @Test def testScalaCollectionInput() {
    val expected = List(1.0, 2.0, 3.0)
    val datasource = org.apache.flink.api.scala.CollectionDataSource(expected)

    val javaCDS = datasource.contract.asInstanceOf[JCollectionDataSource]

    val inputFormat = javaCDS.getFormatWrapper.getUserCodeObject()
    val splits = inputFormat.createInputSplits(1)
    inputFormat.open(splits(0))

    val record = new Record()
    var result = List[Double]()

    while(!inputFormat.reachedEnd()){
      inputFormat.nextRecord(record)
      assertTrue(record.getNumFields == 1)
      val value = record.getField[DoubleValue](0, classOf[DoubleValue])
      result = value.getValue :: result
    }

    assertEquals(expected, result.reverse)
  }

}
