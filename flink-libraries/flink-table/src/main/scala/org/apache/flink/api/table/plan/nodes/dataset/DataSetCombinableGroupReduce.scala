/*
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

package org.apache.flink.api.table.plan.nodes.dataset

import org.apache.calcite.plan.{RelOptCluster, RelTraitSet}
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.{RelNode, RelWriter, SingleRel}
import org.apache.flink.api.common.functions.GroupReduceFunction
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.DataSet
import org.apache.flink.api.table.{TableConfig, Row}
import org.apache.flink.api.java.typeutils.TupleTypeInfo
import org.apache.flink.api.table.typeinfo.RowTypeInfo
import org.apache.flink.api.common.typeinfo.TypeInformation
import scala.collection.JavaConverters._
import org.apache.flink.api.table.plan.TypeConverter
import org.apache.flink.api.table.plan.TypeConverter.determineReturnType
import org.apache.flink.api.common.functions.MapFunction

/**
  * Flink RelNode which is used for combinable aggregations.
  */
class DataSetCombinableGroupReduce(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    input: RelNode,
    rowType: RelDataType,
    opName: String,
    groupingKeys: Array[Int],
    projection: Boolean,
    mapFunc: (TableConfig, TypeInformation[Any], TypeInformation[Any]) => MapFunction[Any, Any],
    reduceFunc: GroupReduceFunction[Row, Row])
  extends SingleRel(cluster, traitSet, input)
  with DataSetRel {

  override def deriveRowType() = rowType

  override def copy(traitSet: RelTraitSet, inputs: java.util.List[RelNode]): RelNode = {
    new DataSetCombinableGroupReduce(
      cluster,
      traitSet,
      inputs.get(0),
      rowType,
      opName,
      groupingKeys,
      projection,
      mapFunc,
      reduceFunc
    )
  }

  override def explainTerms(pw: RelWriter): RelWriter = {
    super.explainTerms(pw).item("name", opName)
  }

  override def translateToPlan(
      config: TableConfig,
      expectedType: Option[TypeInformation[Any]]): DataSet[Any] = {
	
    val inputDS = input.asInstanceOf[DataSetRel].translateToPlan(config, expectedType)

    // get the output types
    val returnType = determineReturnType(
      getRowType,
      expectedType,
      config.getNullCheck,
      config.getEfficientTypeUsage)

    if (projection) {
      //TODO: is the returnType for the projection the same as the one of this RelNode?
      // I think it should be...
      val mapper = mapFunc.apply(config, inputDS.getType, returnType)
      val mappedDataSet = inputDS.map(mapper).asInstanceOf[DataSet[Row]]
      if (groupingKeys.length > 0) {
        mappedDataSet
        .groupBy(groupingKeys: _*)
        .reduceGroup(reduceFunc)
        .returns(returnType.asInstanceOf[TypeInformation[Row]])
        .asInstanceOf[DataSet[Any]]
      }
      else {
        // global aggregation
        mappedDataSet
        .reduceGroup(reduceFunc)
        .returns(returnType.asInstanceOf[TypeInformation[Row]])
        .asInstanceOf[DataSet[Any]]
      }
    }
    else { // no projection
      if (groupingKeys.length > 0) {
        inputDS.asInstanceOf[DataSet[Row]]
        .groupBy(groupingKeys: _*)
        .reduceGroup(reduceFunc)
        .returns(returnType.asInstanceOf[TypeInformation[Row]])
        .asInstanceOf[DataSet[Any]]
      }
      else {
        // global aggregation
        inputDS.asInstanceOf[DataSet[Row]]
        .reduceGroup(reduceFunc)
        .returns(returnType.asInstanceOf[TypeInformation[Row]])
        .asInstanceOf[DataSet[Any]]
      }
    }
  }
}
