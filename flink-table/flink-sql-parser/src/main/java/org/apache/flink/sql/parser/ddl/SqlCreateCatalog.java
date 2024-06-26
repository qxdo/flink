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

package org.apache.flink.sql.parser.ddl;

import org.apache.flink.sql.parser.SqlUnparseUtils;

import org.apache.calcite.sql.SqlCreate;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.ImmutableNullableList;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** CREATE CATALOG DDL sql call. */
public class SqlCreateCatalog extends SqlCreate {

    public static final SqlSpecialOperator OPERATOR =
            new SqlSpecialOperator("CREATE CATALOG", SqlKind.OTHER_DDL);

    private final SqlIdentifier catalogName;

    private final SqlNodeList propertyList;

    @Nullable private final SqlNode comment;

    public SqlCreateCatalog(
            SqlParserPos position,
            SqlIdentifier catalogName,
            SqlNodeList propertyList,
            @Nullable SqlNode comment,
            boolean ifNotExists) {
        super(OPERATOR, position, false, ifNotExists);
        this.catalogName = requireNonNull(catalogName, "catalogName cannot be null");
        this.propertyList = requireNonNull(propertyList, "propertyList cannot be null");
        this.comment = comment;
    }

    @Override
    public SqlOperator getOperator() {
        return OPERATOR;
    }

    @Override
    public List<SqlNode> getOperandList() {
        return ImmutableNullableList.of(catalogName, propertyList, comment);
    }

    public SqlIdentifier getCatalogName() {
        return catalogName;
    }

    public SqlNodeList getPropertyList() {
        return propertyList;
    }

    public Optional<SqlNode> getComment() {
        return Optional.ofNullable(comment);
    }

    public boolean isIfNotExists() {
        return ifNotExists;
    }

    @Override
    public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
        writer.keyword("CREATE CATALOG");
        if (isIfNotExists()) {
            writer.keyword("IF NOT EXISTS");
        }
        catalogName.unparse(writer, leftPrec, rightPrec);

        if (comment != null) {
            writer.newlineAndIndent();
            writer.keyword("COMMENT");
            comment.unparse(writer, leftPrec, rightPrec);
        }

        if (this.propertyList.size() > 0) {
            writer.keyword("WITH");
            SqlWriter.Frame withFrame = writer.startList("(", ")");
            for (SqlNode property : propertyList) {
                SqlUnparseUtils.printIndent(writer);
                property.unparse(writer, leftPrec, rightPrec);
            }
            writer.newlineAndIndent();
            writer.endList(withFrame);
        }
    }

    public String catalogName() {
        return catalogName.getSimple();
    }
}
