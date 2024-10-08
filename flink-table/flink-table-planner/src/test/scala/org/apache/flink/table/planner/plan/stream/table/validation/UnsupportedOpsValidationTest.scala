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
package org.apache.flink.table.planner.plan.stream.table.validation

import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.table.api._
import org.apache.flink.table.api.bridge.scala._
import org.apache.flink.table.planner.runtime.utils.{StreamingEnvUtil, TestData}
import org.apache.flink.table.planner.utils.TableTestUtil
import org.apache.flink.test.junit5.MiniClusterExtension

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class UnsupportedOpsValidationTest {

  @Test
  def testJoin(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = StreamTableEnvironment.create(env, TableTestUtil.STREAM_SETTING)
    val t1 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)
    val t2 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)

    assertThatThrownBy(() => t1.join(t2)).isInstanceOf(classOf[ValidationException])
  }

  @Test
  def testUnion(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = StreamTableEnvironment.create(env, TableTestUtil.STREAM_SETTING)
    val t1 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)
    val t2 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)

    assertThatThrownBy(() => t1.union(t2)).isInstanceOf(classOf[ValidationException])
  }

  @Test
  def testIntersect(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = StreamTableEnvironment.create(env, TableTestUtil.STREAM_SETTING)
    val t1 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)
    val t2 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)

    assertThatThrownBy(() => t1.intersect(t2)).isInstanceOf(classOf[ValidationException])
  }

  @Test
  def testIntersectAll(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = StreamTableEnvironment.create(env, TableTestUtil.STREAM_SETTING)
    val t1 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)
    val t2 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)

    assertThatThrownBy(() => t1.intersectAll(t2)).isInstanceOf(classOf[ValidationException])
  }

  @Test
  def testMinus(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = StreamTableEnvironment.create(env, TableTestUtil.STREAM_SETTING)
    val t1 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)
    val t2 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)

    assertThatThrownBy(() => t1.minus(t2)).isInstanceOf(classOf[ValidationException])
  }

  @Test
  def testMinusAll(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = StreamTableEnvironment.create(env, TableTestUtil.STREAM_SETTING)
    val t1 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)
    val t2 = StreamingEnvUtil.fromCollection(env, TestData.smallTupleData3).toTable(tEnv)

    assertThatThrownBy(() => t1.minusAll(t2)).isInstanceOf(classOf[ValidationException])
  }
}

object UnsupportedOpsValidationTest {
  @RegisterExtension
  private val _: MiniClusterExtension = new MiniClusterExtension(
    () =>
      new MiniClusterResourceConfiguration.Builder()
        .setNumberTaskManagers(1)
        .setNumberSlotsPerTaskManager(4)
        .build())
}
