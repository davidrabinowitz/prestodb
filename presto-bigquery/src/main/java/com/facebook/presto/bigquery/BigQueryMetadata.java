/*
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
package com.facebook.presto.bigquery;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.ConnectorTableLayoutResult;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.NotFoundException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.SchemaTablePrefix;
import com.facebook.presto.spi.TableNotFoundException;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.google.api.gax.paging.Page;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.cloud.bigquery.TableDefinition.Type.TABLE;
import static com.google.cloud.bigquery.TableDefinition.Type.VIEW;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

public class BigQueryMetadata
        implements ConnectorMetadata
{
    static final int NUMERIC_DATA_TYPE_PRECISION = 38;
    static final int NUMERIC_DATA_TYPE_SCALE = 9;
    static final String INFORMATION_SCHEMA = "information_schema";
    private static final Logger log = Logger.get(BigQueryMetadata.class);
    private BigQueryClient bigQueryClient;
    private String projectId;

    @Inject
    public BigQueryMetadata(BigQueryClient bigQueryClient, BigQueryConfig config)
    {
        this.bigQueryClient = bigQueryClient;
        this.projectId = config.getProjectId().orElse(bigQueryClient.getProjectId());
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        log.debug("listSchemaNames(session=%s)", session);
        return Streams.stream(bigQueryClient.listDatasets(projectId))
                .map(dataset -> dataset.getDatasetId().getDataset())
                .filter(schemaName -> !schemaName.equalsIgnoreCase(INFORMATION_SCHEMA))
                .collect(toImmutableList());
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName)
    {
        log.debug("listTables(session=%s, schemaName=%s)", session, schemaName);
        return listTablesWithTypes(session, schemaName, TABLE);
    }

    @Override
    public List<SchemaTableName> listViews(ConnectorSession session, Optional<String> schemaName)
    {
        log.debug("listViews(session=%s, schemaName=%s)", session, schemaName);
        return listTablesWithTypes(session, schemaName, VIEW);
    }

    private List<SchemaTableName> listTablesWithTypes(ConnectorSession session, Optional<String> schemaName, TableDefinition.Type... types)
    {
        if (schemaName.isPresent() && schemaName.get().equalsIgnoreCase(INFORMATION_SCHEMA)) {
            return ImmutableList.of();
        }
        Set<String> schemaNames = schemaName.map(ImmutableSet::of)
                .orElseGet(() -> ImmutableSet.copyOf(listSchemaNames(session)));

        ImmutableList.Builder<SchemaTableName> tableNames = ImmutableList.builder();
        for (String datasetId : schemaNames) {
            for (Table table : bigQueryClient.listTables(DatasetId.of(projectId, datasetId), types)) {
                tableNames.add(new SchemaTableName(datasetId, table.getTableId().getTable()));
            }
        }
        return tableNames.build();
    }

    <T> ImmutableList<T> collectAll(Page<T> page)
    {
        return ImmutableList.copyOf(page.iterateAll());
    }

    @Override
    public ConnectorTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        log.debug("getTableHandle(session=%s, tableName=%s)", session, tableName);
        TableInfo tableInfo = getBigQueryTable(tableName);
        if (tableInfo == null) {
            log.debug("Table [%s.%s] was not found", tableName.getSchemaName(), tableName.getTableName());
            return null;
        }
        return BigQueryTableHandle.from(tableInfo);
    }

    @Override
    public List<ConnectorTableLayoutResult> getTableLayouts(
            ConnectorSession session,
            ConnectorTableHandle table,
            Constraint<ColumnHandle> constraint,
            Optional<Set<ColumnHandle>> desiredColumns)
    {
        log.debug("getTableMetadata(session=%s, table=%s, constraint=%s, desiredColumns=%s)", session, table, constraint, desiredColumns);
        BigQueryTableHandle bigQueryTableHandle = (BigQueryTableHandle) table;
        if (desiredColumns.isPresent()) {
            bigQueryTableHandle = bigQueryTableHandle.withProjectedColumns(ImmutableList.copyOf(desiredColumns.get()));
        }
        BigQueryTableLayoutHandle bigQueryTableLayoutHandle = new BigQueryTableLayoutHandle(bigQueryTableHandle);
        return ImmutableList.of(new ConnectorTableLayoutResult(new ConnectorTableLayout(bigQueryTableLayoutHandle), constraint.getSummary()));
    }

    @Override
    public ConnectorTableLayout getTableLayout(ConnectorSession session, ConnectorTableLayoutHandle layoutHandle)
    {
        log.debug("getTableMetadata(session=%s, layoutHandle=%s)", session, layoutHandle);
        BigQueryTableLayoutHandle bigQueryTableLayoutHandle = (BigQueryTableLayoutHandle) layoutHandle;
        return new ConnectorTableLayout(
                bigQueryTableLayoutHandle,
                Optional.empty(), // columns of the table, not projected
                bigQueryTableLayoutHandle.getTupleDomain(), // predicate
                Optional.empty(), // tablePartitioning
                Optional.empty(), // streamPartitioningColumns
                Optional.empty(), // discretePredicates
                ImmutableList.of()); // localProperties
    }

    // May return null
    private TableInfo getBigQueryTable(SchemaTableName tableName)
    {
        return bigQueryClient.getTable(TableId.of(projectId, tableName.getSchemaName(), tableName.getTableName()));
    }

    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, SchemaTableName schemaTableName)
    {
        ConnectorTableHandle table = getTableHandle(session, schemaTableName);
        if (table == null) {
            throw new TableNotFoundException(schemaTableName);
        }
        return getTableMetadata(session, table);
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        log.debug("getTableMetadata(session=%s, tableHandle=%s)", session, tableHandle);
        TableInfo table = bigQueryClient.getTable(((BigQueryTableHandle) tableHandle).getTableId());
        SchemaTableName schemaTableName = new SchemaTableName(table.getTableId().getDataset(), table.getTableId().getTable());
        Schema schema = table.getDefinition().getSchema();
        List<ColumnMetadata> columns = schema == null ?
                ImmutableList.of() :
                schema.getFields().stream()
                        .map(Conversions::toColumnMetadata)
                        .collect(toImmutableList());
        return new ConnectorTableMetadata(schemaTableName, columns);
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        log.debug("getColumnHandles(session=%s, tableHandle=%s)", session, tableHandle);
        TableInfo table = bigQueryClient.getTable(((BigQueryTableHandle) tableHandle).getTableId());
        Schema schema = table.getDefinition().getSchema();
        return schema == null ?
                ImmutableMap.of() :
                schema.getFields().stream().collect(toMap(Field::getName, Conversions::toColumnHandle));
    }

    @Override
    public ColumnMetadata getColumnMetadata(
            ConnectorSession session,
            ConnectorTableHandle tableHandle,
            ColumnHandle columnHandle)
    {
        log.debug("getColumnMetadata(session=%s, tableHandle=%s, columnHandle=%s)", session, columnHandle, columnHandle);
        return ((BigQueryColumnHandle) columnHandle).getColumnMetadata();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        log.debug("listTableColumns(session=%s, prefix=%s)", session, prefix);
        requireNonNull(prefix, "prefix is null");
        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> columns = ImmutableMap.builder();
        for (SchemaTableName tableName : listTables(session, prefix)) {
            try {
                columns.put(tableName, getTableMetadata(session, tableName).getColumns());
            }
            catch (NotFoundException e) {
                // table disappeared during listing operation
            }
        }
        return columns.build();
    }

    private List<SchemaTableName> listTables(ConnectorSession session, SchemaTablePrefix prefix)
    {
        if (prefix.getTableName() == null) {
            return listTables(session, Optional.ofNullable(prefix.getSchemaName()));
        }
        SchemaTableName tableName = prefix.toSchemaTableName();
        TableInfo tableInfo = getBigQueryTable(tableName);
        return tableInfo == null ?
                ImmutableList.of() : // table does not exist
                ImmutableList.of(tableName);
    }

//    @Override
//    public boolean usesLegacyTableLayouts()
//    {
//        return false;
//    }
//
//    @Override
//    public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle table)
//    {
//        log.debug("getTableProperties(session=%s, prefix=%s)", session, table);
//        return new ConnectorTableProperties();
//    }
//
//    @Override
//    public Optional<LimitApplicationResult<ConnectorTableHandle>> applyLimit(
//            ConnectorSession session,
//            ConnectorTableHandle handle,
//            long limit)
//    {
//        log.debug("applyLimit(session=%s, handle=%s, limit=%s)", session, handle, limit);
//        BigQueryTableHandle bigQueryTableHandle = (BigQueryTableHandle) handle;
//
//        if (bigQueryTableHandle.getLimit().isPresent() && bigQueryTableHandle.getLimit().getAsLong() <= limit) {
//            return Optional.empty();
//        }
//
//        bigQueryTableHandle = bigQueryTableHandle.withLimit(limit);
//
//        return Optional.of(new LimitApplicationResult<>(bigQueryTableHandle, false));
//    }
//
//    @Override
//    public Optional<ProjectionApplicationResult<ConnectorTableHandle>> applyProjection(
//            ConnectorSession session,
//            ConnectorTableHandle handle,
//            List<ConnectorExpression> projections,
//            Map<String, ColumnHandle> assignments)
//    {
//        log.debug("applyProjection(session=%s, handle=%s, projections=%s, assignments=%s)",
//                session, handle, projections, assignments);
//        BigQueryTableHandle bigQueryTableHandle = (BigQueryTableHandle) handle;
//
//        if (bigQueryTableHandle.getProjectedColumns().isPresent()) {
//            return Optional.empty();
//        }
//
//        ImmutableList.Builder<ColumnHandle> projectedColumns = ImmutableList.builder();
//        ImmutableList.Builder<Assignment> assignmentList = ImmutableList.builder();
//        assignments.forEach((name, column) -> {
//            projectedColumns.add(column);
//            assignmentList.add(new Assignment(name, column, ((BigQueryColumnHandle) column).getPrestoType()));
//        });
//
//        bigQueryTableHandle = bigQueryTableHandle.withProjectedColumns(projectedColumns.build());
//
//        return Optional.of(new ProjectionApplicationResult<>(bigQueryTableHandle, projections, assignmentList.build()));
//    }
//
//    @Override
//    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(ConnectorSession session, ConnectorTableHandle handle, Constraint constraint)
//    {
//        log.debug("applyFilter(session=%s, handle=%s, summary=%s, predicate=%s, columns=%s)", session, handle, constraint.getSummary().toString(session), constraint.predicate(), constraint.getColumns());
//        return Optional.empty();
//    }
}
