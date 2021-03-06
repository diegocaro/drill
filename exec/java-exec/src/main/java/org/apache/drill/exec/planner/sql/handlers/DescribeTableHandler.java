/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.planner.sql.handlers;

import static org.apache.drill.exec.store.ischema.InfoSchemaConstants.COLS_COL_COLUMN_NAME;
import static org.apache.drill.exec.store.ischema.InfoSchemaConstants.COLS_COL_DATA_TYPE;
import static org.apache.drill.exec.store.ischema.InfoSchemaConstants.COLS_COL_IS_NULLABLE;
import static org.apache.drill.exec.store.ischema.InfoSchemaConstants.IS_SCHEMA_NAME;
import static org.apache.drill.exec.store.ischema.InfoSchemaConstants.SHRD_COL_TABLE_NAME;
import static org.apache.drill.exec.store.ischema.InfoSchemaConstants.SHRD_COL_TABLE_SCHEMA;

import java.util.List;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.exec.planner.sql.SchemaUtilites;
import org.apache.drill.exec.planner.sql.SqlConverter;
import org.apache.drill.exec.planner.sql.parser.DrillParserUtil;
import org.apache.drill.exec.planner.sql.parser.DrillSqlDescribeTable;
import org.apache.drill.exec.store.ischema.InfoSchemaTableType;
import org.apache.drill.exec.work.foreman.ForemanSetupException;

import com.google.common.collect.ImmutableList;

public class DescribeTableHandler extends DefaultSqlHandler {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DescribeTableHandler.class);

  public DescribeTableHandler(SqlHandlerConfig config) { super(config); }

  /** Rewrite the parse tree as SELECT ... FROM INFORMATION_SCHEMA.COLUMNS ... */
  @Override
  public SqlNode rewrite(SqlNode sqlNode) throws ForemanSetupException {
    DrillSqlDescribeTable node = unwrap(sqlNode, DrillSqlDescribeTable.class);

    try {
      List<SqlNode> selectList =
          ImmutableList.of(new SqlIdentifier(COLS_COL_COLUMN_NAME, SqlParserPos.ZERO),
                           new SqlIdentifier(COLS_COL_DATA_TYPE, SqlParserPos.ZERO),
                           new SqlIdentifier(COLS_COL_IS_NULLABLE, SqlParserPos.ZERO));

      SqlNode fromClause = new SqlIdentifier(
          ImmutableList.of(IS_SCHEMA_NAME, InfoSchemaTableType.COLUMNS.name()), null, SqlParserPos.ZERO, null);

      final SqlIdentifier table = node.getTable();
      final SchemaPlus defaultSchema = config.getConverter().getDefaultSchema();
      final List<String> schemaPathGivenInCmd = Util.skipLast(table.names);
      final SchemaPlus schema = SchemaUtilites.findSchema(defaultSchema, schemaPathGivenInCmd);
      final String charset = Util.getDefaultCharset().name();

      if (schema == null) {
        SchemaUtilites.throwSchemaNotFoundException(defaultSchema,
            SchemaUtilites.SCHEMA_PATH_JOINER.join(schemaPathGivenInCmd));
      }

      if (SchemaUtilites.isRootSchema(schema)) {
        throw UserException.validationError()
            .message("No schema selected.")
            .build(logger);
      }

      final String tableName = Util.last(table.names);

      // find resolved schema path
      final String schemaPath = SchemaUtilites.unwrapAsDrillSchemaInstance(schema).getFullSchemaName();

      if (schema.getTable(tableName) == null) {
        throw UserException.validationError()
            .message("Unknown table [%s] in schema [%s]", tableName, schemaPath)
            .build(logger);
      }

      SqlNode schemaCondition = null;
      if (!SchemaUtilites.isRootSchema(schema)) {
        schemaCondition = DrillParserUtil.createCondition(
            new SqlIdentifier(SHRD_COL_TABLE_SCHEMA, SqlParserPos.ZERO),
            SqlStdOperatorTable.EQUALS,
            SqlLiteral.createCharString(schemaPath, charset, SqlParserPos.ZERO)
        );
      }

      SqlNode where = DrillParserUtil.createCondition(
          new SqlIdentifier(SHRD_COL_TABLE_NAME, SqlParserPos.ZERO),
          SqlStdOperatorTable.EQUALS,
          SqlLiteral.createCharString(tableName, charset, SqlParserPos.ZERO));

      where = DrillParserUtil.createCondition(schemaCondition, SqlStdOperatorTable.AND, where);

      SqlNode columnFilter = null;
      if (node.getColumn() != null) {
        columnFilter =
            DrillParserUtil.createCondition(
                new SqlIdentifier(COLS_COL_COLUMN_NAME, SqlParserPos.ZERO),
                SqlStdOperatorTable.EQUALS,
                SqlLiteral.createCharString(node.getColumn().toString(), charset, SqlParserPos.ZERO));
      } else if (node.getColumnQualifier() != null) {
        columnFilter =
            DrillParserUtil.createCondition(
                new SqlIdentifier(COLS_COL_COLUMN_NAME, SqlParserPos.ZERO),
                SqlStdOperatorTable.LIKE, node.getColumnQualifier());
      }

      where = DrillParserUtil.createCondition(where, SqlStdOperatorTable.AND, columnFilter);

      return new SqlSelect(SqlParserPos.ZERO, null, new SqlNodeList(selectList, SqlParserPos.ZERO),
          fromClause, where, null, null, null, null, null, null);
    } catch (Exception ex) {
      throw UserException.planError(ex)
          .message("Error while rewriting DESCRIBE query: %d", ex.getMessage())
          .build(logger);
    }
  }

  @Override
  protected Pair<SqlNode, RelDataType> validateNode(SqlNode sqlNode) throws ValidationException,
      RelConversionException, ForemanSetupException {
    SqlConverter converter = config.getConverter();
    // set this to true since INFORMATION_SCHEMA in the root schema, not in the default
    converter.useRootSchemaAsDefault(true);
    Pair<SqlNode, RelDataType> sqlNodeRelDataTypePair = super.validateNode(sqlNode);
    converter.useRootSchemaAsDefault(false);
    return sqlNodeRelDataTypePair;
  }
}
