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
package org.apache.flink.table.planner.calcite

import org.apache.flink.sql.parser.`type`.SqlMapTypeNameSpec
import org.apache.flink.table.api.ValidationException
import org.apache.flink.table.planner.calcite.PreValidateReWriter.{newValidationError, notSupported}
import org.apache.flink.table.planner.calcite.SqlRewriterUtils.{rewriteSqlCall, rewriteSqlSelect, rewriteSqlValues}
import org.apache.flink.util.Preconditions.checkArgument

import org.apache.calcite.rel.`type`.{RelDataType, RelDataTypeFactory}
import org.apache.calcite.runtime.{CalciteContextException, Resources}
import org.apache.calcite.sql.`type`.SqlTypeUtil
import org.apache.calcite.sql.{SqlCall, SqlDataTypeSpec, SqlKind, SqlNode, SqlNodeList, SqlOrderBy, SqlSelect, SqlUtil}
import org.apache.calcite.sql.fun.SqlStdOperatorTable
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.validate.SqlValidatorException
import org.apache.calcite.util.Static.RESOURCE

import java.util
import java.util.Collections

import scala.collection.JavaConversions._

class SqlRewriterUtils(validator: FlinkCalciteSqlValidator) {
  def rewriteSelect(
      select: SqlSelect,
      targetRowType: RelDataType,
      assignedFields: util.LinkedHashMap[Integer, SqlNode],
      targetPosition: util.List[Int]): SqlCall = {
    rewriteSqlSelect(validator, select, targetRowType, assignedFields, targetPosition)
  }

  def rewriteValues(
      svalues: SqlCall,
      targetRowType: RelDataType,
      assignedFields: util.LinkedHashMap[Integer, SqlNode],
      targetPosition: util.List[Int]): SqlCall = {
    rewriteSqlValues(svalues, targetRowType, assignedFields, targetPosition)
  }

  def rewriteCall(
      rewriterUtils: SqlRewriterUtils,
      validator: FlinkCalciteSqlValidator,
      call: SqlCall,
      targetRowType: RelDataType,
      assignedFields: util.LinkedHashMap[Integer, SqlNode],
      targetPosition: util.List[Int],
      unsupportedErrorMessage: () => String): SqlCall = {
    rewriteSqlCall(
      rewriterUtils,
      validator,
      call,
      targetRowType,
      assignedFields,
      targetPosition,
      unsupportedErrorMessage)
  }

  // This code snippet is copied from the SqlValidatorImpl.
  def maybeCast(
      node: SqlNode,
      currentType: RelDataType,
      desiredType: RelDataType,
      typeFactory: RelDataTypeFactory): SqlNode = {
    if (
      currentType == desiredType
      || (currentType.isNullable != desiredType.isNullable
        && typeFactory.createTypeWithNullability(currentType, desiredType.isNullable)
        == desiredType)
    ) {
      node
    } else {
      // See FLINK-26460 for more details
      val sqlDataTypeSpec =
        if (SqlTypeUtil.isNull(currentType) && SqlTypeUtil.isMap(desiredType)) {
          val keyType = desiredType.getKeyType
          val valueType = desiredType.getValueType
          new SqlDataTypeSpec(
            new SqlMapTypeNameSpec(
              SqlTypeUtil.convertTypeToSpec(keyType).withNullable(keyType.isNullable),
              SqlTypeUtil.convertTypeToSpec(valueType).withNullable(valueType.isNullable),
              SqlParserPos.ZERO),
            SqlParserPos.ZERO)
        } else {
          SqlTypeUtil.convertTypeToSpec(desiredType)
        }
      SqlStdOperatorTable.CAST.createCall(SqlParserPos.ZERO, node, sqlDataTypeSpec)
    }
  }
}

object SqlRewriterUtils {
  def rewriteSqlCall(
      rewriterUtils: SqlRewriterUtils,
      validator: FlinkCalciteSqlValidator,
      call: SqlCall,
      targetRowType: RelDataType,
      assignedFields: util.LinkedHashMap[Integer, SqlNode],
      targetPosition: util.List[Int],
      unsupportedErrorMessage: () => String): SqlCall = {

    def rewrite(node: SqlNode): SqlCall = {
      checkArgument(node.isInstanceOf[SqlCall], node)
      rewriteSqlCall(
        rewriterUtils,
        validator,
        node.asInstanceOf[SqlCall],
        targetRowType,
        assignedFields,
        targetPosition,
        unsupportedErrorMessage)
    }

    call.getKind match {
      case SqlKind.SELECT =>
        val sqlSelect = call.asInstanceOf[SqlSelect]

        if (targetPosition.nonEmpty && sqlSelect.getSelectList.size() != targetPosition.size()) {
          throw newValidationError(call, RESOURCE.columnCountMismatch())
        }
        rewriterUtils.rewriteSelect(sqlSelect, targetRowType, assignedFields, targetPosition)
      case SqlKind.VALUES =>
        call.getOperandList.toSeq.foreach {
          case sqlCall: SqlCall => {
            if (targetPosition.nonEmpty && sqlCall.getOperandList.size() != targetPosition.size()) {
              throw newValidationError(call, RESOURCE.columnCountMismatch())
            }
          }
        }
        rewriterUtils.rewriteValues(call, targetRowType, assignedFields, targetPosition)
      case kind if SqlKind.SET_QUERY.contains(kind) =>
        call.getOperandList.zipWithIndex.foreach {
          case (operand, index) => call.setOperand(index, rewrite(operand))
        }
        call
      case SqlKind.ORDER_BY =>
        val operands = call.getOperandList
        new SqlOrderBy(
          call.getParserPosition,
          rewrite(operands.get(0)),
          operands.get(1).asInstanceOf[SqlNodeList],
          operands.get(2),
          operands.get(3))
      // Not support:
      // case SqlKind.WITH =>
      // case SqlKind.EXPLICIT_TABLE =>
      case _ => throw new ValidationException(unsupportedErrorMessage())
    }
  }

  def rewriteSqlSelect(
      validator: FlinkCalciteSqlValidator,
      select: SqlSelect,
      targetRowType: RelDataType,
      assignedFields: util.LinkedHashMap[Integer, SqlNode],
      targetPosition: util.List[Int]): SqlCall = {
    // Expands the select list first in case there is a star(*).
    // Validates the select first to register the where scope.
    validator.validate(select)
    val sourceList = validator.expandStar(select.getSelectList, select, false).getList

    val fixedNodes = new util.ArrayList[SqlNode]
    val currentNodes =
      if (targetPosition.isEmpty) {
        new util.ArrayList[SqlNode](sourceList)
      } else {
        reorder(new util.ArrayList[SqlNode](sourceList), targetPosition)
      }
    (0 until targetRowType.getFieldList.length).foreach {
      idx =>
        if (assignedFields.containsKey(idx)) {
          fixedNodes.add(assignedFields.get(idx))
        } else if (currentNodes.size() > 0) {
          fixedNodes.add(currentNodes.remove(0))
        }
    }
    // Although it is error case, we still append the old remaining
    // projection nodes to new projection.
    if (currentNodes.size > 0) {
      fixedNodes.addAll(currentNodes)
    }
    select.setSelectList(new SqlNodeList(fixedNodes, select.getSelectList.getParserPosition))
    select
  }

  def rewriteSqlValues(
      values: SqlCall,
      targetRowType: RelDataType,
      assignedFields: util.LinkedHashMap[Integer, SqlNode],
      targetPosition: util.List[Int]): SqlCall = {
    val fixedNodes = new util.ArrayList[SqlNode]
    (0 until values.getOperandList.size()).foreach {
      valueIdx =>
        val value = values.getOperandList.get(valueIdx)
        val valueAsList = if (value.getKind == SqlKind.ROW) {
          value.asInstanceOf[SqlCall].getOperandList
        } else {
          Collections.singletonList(value)
        }
        val currentNodes =
          if (targetPosition.isEmpty) {
            new util.ArrayList[SqlNode](valueAsList)
          } else {
            reorder(new util.ArrayList[SqlNode](valueAsList), targetPosition)
          }
        val fieldNodes = new util.ArrayList[SqlNode]
        (0 until targetRowType.getFieldList.length).foreach {
          fieldIdx =>
            if (assignedFields.containsKey(fieldIdx)) {
              fieldNodes.add(assignedFields.get(fieldIdx))
            } else if (currentNodes.size() > 0) {
              fieldNodes.add(currentNodes.remove(0))
            }
        }
        // Although it is error case, we still append the old remaining
        // value items to new item list.
        if (currentNodes.size > 0) {
          fieldNodes.addAll(currentNodes)
        }
        fixedNodes.add(SqlStdOperatorTable.ROW.createCall(value.getParserPosition, fieldNodes))
    }
    SqlStdOperatorTable.VALUES.createCall(values.getParserPosition, fixedNodes)
  }

  def newValidationError(
      node: SqlNode,
      e: Resources.ExInst[SqlValidatorException]): CalciteContextException = {
    assert(node != null)
    val pos = node.getParserPosition
    SqlUtil.newContextException(pos, e)
  }

  /**
   * Reorder sourceList to targetPosition. For example:
   *   - sourceList(f0, f1, f2).
   *   - targetPosition(1, 2, 0).
   *   - Output(f1, f2, f0).
   *
   * @param sourceList
   *   input fields.
   * @param targetPosition
   *   reorder mapping.
   * @return
   *   reorder fields.
   */
  private def reorder(
      sourceList: util.ArrayList[SqlNode],
      targetPosition: util.List[Int]): util.ArrayList[SqlNode] = {
    new util.ArrayList[SqlNode](targetPosition.map(sourceList.get))
  }
}
