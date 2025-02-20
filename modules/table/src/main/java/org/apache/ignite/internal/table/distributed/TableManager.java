/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.table.distributed;

import static java.util.Collections.unmodifiableMap;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.apache.ignite.internal.configuration.util.ConfigurationUtil.getByInternalId;
import static org.apache.ignite.internal.schema.SchemaManager.INITIAL_SCHEMA_VERSION;
import static org.apache.ignite.internal.util.IgniteUtils.shutdownAndAwaitTermination;
import static org.apache.ignite.internal.utils.RebalanceUtil.PENDING_ASSIGNMENTS_PREFIX;
import static org.apache.ignite.internal.utils.RebalanceUtil.STABLE_ASSIGNMENTS_PREFIX;
import static org.apache.ignite.internal.utils.RebalanceUtil.extractPartitionNumber;
import static org.apache.ignite.internal.utils.RebalanceUtil.extractTableId;
import static org.apache.ignite.internal.utils.RebalanceUtil.pendingPartAssignmentsKey;
import static org.apache.ignite.internal.utils.RebalanceUtil.recoverable;
import static org.apache.ignite.internal.utils.RebalanceUtil.stablePartAssignmentsKey;
import static org.apache.ignite.internal.utils.RebalanceUtil.updatePendingAssignmentsKeys;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.ignite.configuration.ConfigurationChangeException;
import org.apache.ignite.configuration.ConfigurationProperty;
import org.apache.ignite.configuration.NamedListView;
import org.apache.ignite.configuration.notifications.ConfigurationNamedListListener;
import org.apache.ignite.configuration.notifications.ConfigurationNotificationEvent;
import org.apache.ignite.configuration.schemas.table.TableChange;
import org.apache.ignite.configuration.schemas.table.TableConfiguration;
import org.apache.ignite.configuration.schemas.table.TableView;
import org.apache.ignite.configuration.schemas.table.TablesConfiguration;
import org.apache.ignite.configuration.validation.ConfigurationValidationException;
import org.apache.ignite.internal.affinity.AffinityUtils;
import org.apache.ignite.internal.baseline.BaselineManager;
import org.apache.ignite.internal.causality.VersionedValue;
import org.apache.ignite.internal.configuration.schema.ExtendedTableChange;
import org.apache.ignite.internal.configuration.schema.ExtendedTableConfiguration;
import org.apache.ignite.internal.configuration.schema.ExtendedTableView;
import org.apache.ignite.internal.configuration.util.ConfigurationUtil;
import org.apache.ignite.internal.manager.EventListener;
import org.apache.ignite.internal.manager.IgniteComponent;
import org.apache.ignite.internal.manager.Producer;
import org.apache.ignite.internal.metastorage.MetaStorageManager;
import org.apache.ignite.internal.metastorage.client.Entry;
import org.apache.ignite.internal.metastorage.client.WatchEvent;
import org.apache.ignite.internal.metastorage.client.WatchListener;
import org.apache.ignite.internal.raft.Loza;
import org.apache.ignite.internal.raft.server.RaftGroupEventsListener;
import org.apache.ignite.internal.schema.SchemaDescriptor;
import org.apache.ignite.internal.schema.SchemaManager;
import org.apache.ignite.internal.schema.SchemaUtils;
import org.apache.ignite.internal.schema.event.SchemaEvent;
import org.apache.ignite.internal.schema.event.SchemaEventParameters;
import org.apache.ignite.internal.schema.marshaller.schema.SchemaSerializerImpl;
import org.apache.ignite.internal.storage.DataStorageManager;
import org.apache.ignite.internal.storage.engine.TableStorage;
import org.apache.ignite.internal.table.IgniteTablesInternal;
import org.apache.ignite.internal.table.InternalTable;
import org.apache.ignite.internal.table.TableImpl;
import org.apache.ignite.internal.table.distributed.raft.PartitionListener;
import org.apache.ignite.internal.table.distributed.raft.RebalanceRaftGroupEventsListener;
import org.apache.ignite.internal.table.distributed.storage.InternalTableImpl;
import org.apache.ignite.internal.table.distributed.storage.VersionedRowStore;
import org.apache.ignite.internal.table.event.TableEvent;
import org.apache.ignite.internal.table.event.TableEventParameters;
import org.apache.ignite.internal.thread.NamedThreadFactory;
import org.apache.ignite.internal.tx.TxManager;
import org.apache.ignite.internal.util.ByteUtils;
import org.apache.ignite.internal.util.IgniteObjectName;
import org.apache.ignite.internal.util.IgniteSpinBusyLock;
import org.apache.ignite.lang.ByteArray;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.lang.IgniteInternalException;
import org.apache.ignite.lang.IgniteLogger;
import org.apache.ignite.lang.IgniteStringFormatter;
import org.apache.ignite.lang.IgniteSystemProperties;
import org.apache.ignite.lang.NodeStoppingException;
import org.apache.ignite.lang.TableAlreadyExistsException;
import org.apache.ignite.lang.TableNotFoundException;
import org.apache.ignite.network.ClusterNode;
import org.apache.ignite.network.NetworkAddress;
import org.apache.ignite.network.TopologyService;
import org.apache.ignite.raft.client.Peer;
import org.apache.ignite.raft.client.service.RaftGroupListener;
import org.apache.ignite.raft.client.service.RaftGroupService;
import org.apache.ignite.raft.jraft.util.Utils;
import org.apache.ignite.table.Table;
import org.apache.ignite.table.manager.IgniteTables;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * Table manager.
 */
public class TableManager extends Producer<TableEvent, TableEventParameters> implements IgniteTables, IgniteTablesInternal,
        IgniteComponent {
    /** The logger. */
    private static final IgniteLogger LOG = IgniteLogger.forClass(TableManager.class);

    /**
     * If this property is set to {@code true} then an attempt to get the configuration property directly from the meta storage will be
     * skipped, and the local property will be returned.
     * TODO: IGNITE-16774 This property and overall approach, access configuration directly through the Metostorage,
     * TODO: will be removed after fix of the issue.
     */
    private final boolean getMetadataLocallyOnly = IgniteSystemProperties.getBoolean("IGNITE_GET_METADATA_LOCALLY_ONLY");

    /** Tables configuration. */
    private final TablesConfiguration tablesCfg;

    /** Raft manager. */
    private final Loza raftMgr;

    /** Baseline manager. */
    private final BaselineManager baselineMgr;

    /** Transaction manager. */
    private final TxManager txManager;

    /** Meta storage manager. */
    private final MetaStorageManager metaStorageMgr;

    /** Data storage manager. */
    private final DataStorageManager dataStorageMgr;

    /** Here a table future stores during creation (until the table can be provided to client). */
    private final Map<UUID, CompletableFuture<Table>> tableCreateFuts = new ConcurrentHashMap<>();

    /** Versioned store for tables by id. */
    private final VersionedValue<Map<UUID, TableImpl>> tablesByIdVv;

    /** Resolver that resolves a network address to node id. */
    private final Function<NetworkAddress, String> netAddrResolver;

    /** Resolver that resolves a network address to cluster node. */
    private final Function<NetworkAddress, ClusterNode> clusterNodeResolver;

    /** Busy lock to stop synchronously. */
    private final IgniteSpinBusyLock busyLock = new IgniteSpinBusyLock();

    /** Prevents double stopping the component. */
    private final AtomicBoolean stopGuard = new AtomicBoolean();

    /** Schema manager. */
    private final SchemaManager schemaManager;

    /** Executor for scheduling retries of a rebalance. */
    private final ScheduledExecutorService rebalanceScheduler;

    /** Rebalance scheduler pool size. */
    private static final int REBALANCE_SCHEDULER_POOL_SIZE = Math.min(Utils.cpus() * 3, 20);

    /**
     * Creates a new table manager.
     *
     * @param registry Registry for versioned values.
     * @param tablesCfg Tables configuration.
     * @param raftMgr Raft manager.
     * @param baselineMgr Baseline manager.
     * @param txManager Transaction manager.
     * @param dataStorageMgr Data storage manager.
     * @param schemaManager Schema manager.
     */
    public TableManager(
            Consumer<Function<Long, CompletableFuture<?>>> registry,
            TablesConfiguration tablesCfg,
            Loza raftMgr,
            BaselineManager baselineMgr,
            TopologyService topologyService,
            TxManager txManager,
            DataStorageManager dataStorageMgr,
            MetaStorageManager metaStorageMgr,
            SchemaManager schemaManager
    ) {
        this.tablesCfg = tablesCfg;
        this.raftMgr = raftMgr;
        this.baselineMgr = baselineMgr;
        this.txManager = txManager;
        this.dataStorageMgr = dataStorageMgr;
        this.metaStorageMgr = metaStorageMgr;
        this.schemaManager = schemaManager;

        netAddrResolver = addr -> {
            ClusterNode node = topologyService.getByAddress(addr);

            if (node == null) {
                throw new IllegalStateException("Can't resolve ClusterNode by its networkAddress=" + addr);
            }

            return node.id();
        };
        clusterNodeResolver = topologyService::getByAddress;

        tablesByIdVv = new VersionedValue<>(null, HashMap::new);

        rebalanceScheduler = new ScheduledThreadPoolExecutor(REBALANCE_SCHEDULER_POOL_SIZE,
                new NamedThreadFactory("rebalance-scheduler"));
    }

    /** {@inheritDoc} */
    @Override
    public void start() {
        tablesCfg.tables().any().replicas().listen(this::onUpdateReplicas);

        registerRebalanceListeners();

        ((ExtendedTableConfiguration) tablesCfg.tables().any()).assignments().listen(this::onUpdateAssignments);

        tablesCfg.tables().listenElements(new ConfigurationNamedListListener<>() {
            @Override
            public CompletableFuture<?> onCreate(ConfigurationNotificationEvent<TableView> ctx) {
                return onTableCreate(ctx);
            }

            @Override
            public CompletableFuture<?> onRename(String oldName, String newName, ConfigurationNotificationEvent<TableView> ctx) {
                // TODO: IGNITE-15485 Support table rename operation.

                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<?> onDelete(ConfigurationNotificationEvent<TableView> ctx) {
                return onTableDelete(ctx);
            }
        });

        schemaManager.listen(SchemaEvent.CREATE, new EventListener<>() {
            /** {@inheritDoc} */
            @Override
            public boolean notify(@NotNull SchemaEventParameters parameters, @Nullable Throwable exception) {
                if (tablesByIdVv.latest().get(parameters.tableId()) != null) {
                    fireEvent(
                            TableEvent.ALTER,
                            new TableEventParameters(parameters.causalityToken(), tablesByIdVv.latest().get(parameters.tableId())), null
                    );
                }

                return false;
            }
        });
    }

    /**
     * Completes all table futures.
     * TODO: Get rid of it after IGNITE-17062.
     *
     * @param causalityToken Causality token.
     */
    public void onSqlSchemaReady(long causalityToken) {
        tablesByIdVv.complete(causalityToken);
    }

    /**
     * Listener of table create configuration change.
     *
     * @param ctx Table configuration context.
     * @return A future.
     */
    private CompletableFuture<?> onTableCreate(ConfigurationNotificationEvent<TableView> ctx) {
        if (!busyLock.enterBusy()) {
            String tblName = ctx.newValue().name();
            UUID tblId = ((ExtendedTableView) ctx.newValue()).id();

            fireEvent(TableEvent.CREATE,
                    new TableEventParameters(ctx.storageRevision(), tblId, tblName),
                    new NodeStoppingException()
            );

            return failedFuture(new NodeStoppingException());
        }

        try {
            return createTableLocally(
                    ctx.storageRevision(),
                    ctx.newValue().name(),
                    ((ExtendedTableView) ctx.newValue()).id(),
                    ctx.newValue().partitions()
            );
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Listener of table drop configuration change.
     *
     * @param ctx Table configuration context.
     * @return A future.
     */
    private CompletableFuture<?> onTableDelete(ConfigurationNotificationEvent<TableView> ctx) {
        if (!busyLock.enterBusy()) {
            String tblName = ctx.oldValue().name();
            UUID tblId = ((ExtendedTableView) ctx.oldValue()).id();

            fireEvent(
                    TableEvent.DROP,
                    new TableEventParameters(ctx.storageRevision(), tblId, tblName),
                    new NodeStoppingException()
            );

            return failedFuture(new NodeStoppingException());
        }

        try {
            dropTableLocally(
                    ctx.storageRevision(),
                    ctx.oldValue().name(),
                    ((ExtendedTableView) ctx.oldValue()).id(),
                    (List<List<ClusterNode>>) ByteUtils.fromBytes(((ExtendedTableView) ctx.oldValue()).assignments())
            );
        } finally {
            busyLock.leaveBusy();
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Listener of replicas configuration changes.
     *
     * @param replicasCtx Replicas configuration event context.
     * @return A future, which will be completed, when event processed by listener.
     */
    private CompletableFuture<?> onUpdateReplicas(ConfigurationNotificationEvent<Integer> replicasCtx) {
        if (!busyLock.enterBusy()) {
            return CompletableFuture.completedFuture(new NodeStoppingException());
        }

        try {
            if (replicasCtx.oldValue() != null && replicasCtx.oldValue() > 0) {
                TableConfiguration tblCfg = replicasCtx.config(TableConfiguration.class);

                int partCnt = tblCfg.partitions().value();

                int newReplicas = replicasCtx.newValue();

                CompletableFuture<?>[] futures = new CompletableFuture<?>[partCnt];

                for (int i = 0; i < partCnt; i++) {
                    String partId = partitionRaftGroupName(((ExtendedTableConfiguration) tblCfg).id().value(), i);

                    futures[i] = updatePendingAssignmentsKeys(
                            partId, baselineMgr.nodes(),
                            partCnt, newReplicas,
                            replicasCtx.storageRevision(), metaStorageMgr, i);
                }

                return CompletableFuture.allOf(futures);
            } else {
                return CompletableFuture.completedFuture(null);
            }
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Listener of assignment configuration changes.
     *
     * @param assignmentsCtx Assignment configuration context.
     * @return A future.
     */
    private CompletableFuture<?> onUpdateAssignments(ConfigurationNotificationEvent<byte[]> assignmentsCtx) {
        if (!busyLock.enterBusy()) {
            return failedFuture(new NodeStoppingException());
        }


        try {
            updateAssignmentInternal(assignmentsCtx);
        } finally {
            busyLock.leaveBusy();
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Updates or creates partition raft groups.
     *
     * @param assignmentsCtx Change assignment event.
     */
    private void updateAssignmentInternal(ConfigurationNotificationEvent<byte[]> assignmentsCtx) {
        ExtendedTableConfiguration tblCfg = assignmentsCtx.config(ExtendedTableConfiguration.class);

        UUID tblId = tblCfg.id().value();

        long causalityToken = assignmentsCtx.storageRevision();

        List<List<ClusterNode>> oldAssignments = assignmentsCtx.oldValue() == null ? null :
                (List<List<ClusterNode>>) ByteUtils.fromBytes(assignmentsCtx.oldValue());

        List<List<ClusterNode>> newAssignments = (List<List<ClusterNode>>) ByteUtils.fromBytes(assignmentsCtx.newValue());

        // Empty assignments might be a valid case if tables are created from within cluster init HOCON
        // configuration, which is not supported now.
        assert newAssignments != null : IgniteStringFormatter.format("Table [id={}] has empty assignments.", tblId);

        int partitions = newAssignments.size();

        CompletableFuture<?>[] futures = new CompletableFuture<?>[partitions];

        // TODO: IGNITE-15554 Add logic for assignment recalculation in case of partitions or replicas changes
        // TODO: Until IGNITE-15554 is implemented it's safe to iterate over partitions and replicas cause there will
        // TODO: be exact same amount of partitions and replicas for both old and new assignments
        for (int i = 0; i < partitions; i++) {
            int partId = i;

            List<ClusterNode> oldPartAssignment = oldAssignments == null ? Collections.emptyList() :
                    oldAssignments.get(partId);

            List<ClusterNode> newPartAssignment = newAssignments.get(partId);

            // Create new raft nodes according to new assignments.
            tablesByIdVv.update(causalityToken, (tablesById, e) -> {
                if (e != null) {
                    return failedFuture(e);
                }

                InternalTable internalTbl = tablesById.get(tblId).internalTable();

                try {
                    futures[partId] = raftMgr.updateRaftGroup(
                            partitionRaftGroupName(tblId, partId),
                            newPartAssignment,
                            // start new nodes, only if it is table creation
                            // other cases will be covered by rebalance logic
                            (oldPartAssignment.isEmpty()) ? newPartAssignment : Collections.emptyList(),
                            () -> new PartitionListener(tblId,
                                    new VersionedRowStore(internalTbl.storage().getOrCreatePartition(partId), txManager)),
                            () -> new RebalanceRaftGroupEventsListener(
                                    metaStorageMgr,
                                    tablesCfg.tables().get(tablesById.get(tblId).name()),
                                    partitionRaftGroupName(tblId, partId),
                                    partId,
                                    busyLock,
                                    movePartition(() -> internalTbl.partitionRaftGroupService(partId)),
                                    rebalanceScheduler)
                    ).thenAccept(
                            updatedRaftGroupService -> ((InternalTableImpl) internalTbl)
                                    .updateInternalTableRaftGroupService(partId, updatedRaftGroupService)
                    ).exceptionally(th -> {
                        LOG.error("Failed to update raft groups one the node", th);

                        return null;
                    });
                } catch (NodeStoppingException ex) {
                    throw new AssertionError("Loza was stopped before Table manager", ex);
                }

                return completedFuture(tablesById);
            });
        }

        CompletableFuture.allOf(futures).join();
    }

    /** {@inheritDoc} */
    @Override
    public void stop() {
        if (!stopGuard.compareAndSet(false, true)) {
            return;
        }

        busyLock.block();

        Map<UUID, TableImpl> tables = tablesByIdVv.latest();

        for (TableImpl table : tables.values()) {
            try {
                table.internalTable().storage().stop();
                table.internalTable().close();

                for (int p = 0; p < table.internalTable().partitions(); p++) {
                    raftMgr.stopRaftGroup(partitionRaftGroupName(table.tableId(), p));
                }
            } catch (Exception e) {
                LOG.error("Failed to stop a table {}", e, table.name());
            }
        }

        shutdownAndAwaitTermination(rebalanceScheduler, 10, TimeUnit.SECONDS);
    }

    /**
     * Creates local structures for a table.
     *
     * @param causalityToken Causality token.
     * @param name  Table name.
     * @param tblId Table id.
     * @param partitions Count of partitions.
     * @return Future that will be completed when local changes related to the table creation are applied.
     */
    private CompletableFuture<?> createTableLocally(long causalityToken, String name, UUID tblId, int partitions) {
        TableConfiguration tableCfg = tablesCfg.tables().get(name);

        TableStorage tableStorage = dataStorageMgr.engine(tableCfg.dataStorage()).createTable(tableCfg);

        tableStorage.start();


        InternalTableImpl internalTable = new InternalTableImpl(name, tblId, new Int2ObjectOpenHashMap<>(partitions),
                partitions, netAddrResolver, clusterNodeResolver, txManager, tableStorage);

        var table = new TableImpl(internalTable);

        tablesByIdVv.update(causalityToken, (previous, e) -> {
            if (e != null) {
                return failedFuture(e);
            }

            var val = new HashMap<>(previous);

            val.put(tblId, table);

            return completedFuture(val);
        });

        schemaManager.schemaRegistry(causalityToken, tblId)
                .thenAccept(table::schemaView)
                .thenRun(() -> fireEvent(TableEvent.CREATE, new TableEventParameters(causalityToken, table), null));

        // TODO should be reworked in IGNITE-16763
        return tablesByIdVv.get(causalityToken).thenRun(() -> completeApiCreateFuture(table));
    }

    /**
     * Completes appropriate future to return result from API {@link TableManager#createTable(String, Consumer)}.
     *
     * @param table Table.
     */
    private void completeApiCreateFuture(TableImpl table) {
        CompletableFuture<Table> tblFut = tableCreateFuts.get(table.tableId());

        if (tblFut != null) {
            tblFut.complete(table);

            tableCreateFuts.values().removeIf(fut -> fut == tblFut);
        }
    }

    /**
     * Drops local structures for a table.
     *
     * @param causalityToken Causality token.
     * @param name           Table name.
     * @param tblId          Table id.
     * @param assignment     Affinity assignment.
     */
    private void dropTableLocally(long causalityToken, String name, UUID tblId, List<List<ClusterNode>> assignment) {
        try {
            int partitions = assignment.size();

            for (int p = 0; p < partitions; p++) {
                raftMgr.stopRaftGroup(partitionRaftGroupName(tblId, p));
            }

            tablesByIdVv.update(causalityToken, (previousVal, e) -> {
                if (e != null) {
                    return failedFuture(e);
                }

                var map = new HashMap<>(previousVal);

                map.remove(tblId);

                return completedFuture(map);
            });

            TableImpl table = tablesByIdVv.latest().get(tblId);

            assert table != null : IgniteStringFormatter.format("There is no table with the name specified [name={}, id={}]",
                    name, tblId);

            table.internalTable().storage().destroy();

            fireEvent(TableEvent.DROP, new TableEventParameters(causalityToken, table), null);

            schemaManager.dropRegistry(causalityToken, table.tableId());
        } catch (Exception e) {
            fireEvent(TableEvent.DROP, new TableEventParameters(causalityToken, tblId, name), e);
        }
    }

    /**
     * Compounds a RAFT group unique name.
     *
     * @param tblId Table identifier.
     * @param partition Number of table partitions.
     * @return A RAFT group name.
     */
    @NotNull
    private String partitionRaftGroupName(UUID tblId, int partition) {
        return tblId + "_part_" + partition;
    }

    /** {@inheritDoc} */
    @Override
    public Table createTable(String name, Consumer<TableChange> tableInitChange) {
        return join(createTableAsync(name, tableInitChange));
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Table> createTableAsync(String name, Consumer<TableChange> tableInitChange) {
        if (!busyLock.enterBusy()) {
            throw new IgniteException(new NodeStoppingException());
        }
        try {
            return createTableAsyncInternal(IgniteObjectName.parseCanonicalName(name), tableInitChange);
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Internal method that creates a new table with the given {@code name} asynchronously. If a table with the same name already exists,
     * a future will be completed with {@link TableAlreadyExistsException}.
     *
     * @param name            Table name.
     * @param tableInitChange Table changer.
     * @return Future representing pending completion of the operation.
     * @throws IgniteException If an unspecified platform exception has happened internally. Is thrown when:
     *                         <ul>
     *                             <li>the node is stopping.</li>
     *                         </ul>
     * @see TableAlreadyExistsException
     */
    private CompletableFuture<Table> createTableAsyncInternal(String name, Consumer<TableChange> tableInitChange) {
        CompletableFuture<Table> tblFut = new CompletableFuture<>();

        tableAsyncInternal(name).thenAccept(tbl -> {
            if (tbl != null) {
                tblFut.completeExceptionally(new TableAlreadyExistsException(name));
            } else {
                tablesCfg.change(tablesChange -> tablesChange.changeTables(tablesListChange -> {
                    if (tablesListChange.get(name) != null) {
                        throw new TableAlreadyExistsException(name);
                    }

                    tablesListChange.create(name, (tableChange) -> {
                        tableChange.changeDataStorage(
                                dataStorageMgr.defaultTableDataStorageConsumer(tablesChange.defaultDataStorage())
                        );

                        tableInitChange.accept(tableChange);

                        var extConfCh = ((ExtendedTableChange) tableChange);

                        tableCreateFuts.put(extConfCh.id(), tblFut);

                        // Affinity assignments calculation.
                        extConfCh.changeAssignments(ByteUtils.toBytes(AffinityUtils.calculateAssignments(
                                        baselineMgr.nodes(),
                                        tableChange.partitions(),
                                        tableChange.replicas())))
                                // Table schema preparation.
                                .changeSchemas(schemasCh -> schemasCh.create(
                                        String.valueOf(INITIAL_SCHEMA_VERSION),
                                        schemaCh -> {
                                            SchemaDescriptor schemaDesc;

                                            //TODO IGNITE-15747 Remove try-catch and force configuration
                                            // validation here to ensure a valid configuration passed to
                                            // prepareSchemaDescriptor() method.
                                            try {
                                                schemaDesc = SchemaUtils.prepareSchemaDescriptor(
                                                        ((ExtendedTableView) tableChange).schemas().size(),
                                                        tableChange);
                                            } catch (IllegalArgumentException ex) {
                                                throw new ConfigurationValidationException(ex.getMessage());
                                            }

                                            schemaCh.changeSchema(SchemaSerializerImpl.INSTANCE.serialize(schemaDesc));
                                        }
                                ));
                    });
                })).exceptionally(t -> {
                    Throwable ex = getRootCause(t);

                    if (ex instanceof TableAlreadyExistsException) {
                        tblFut.completeExceptionally(ex);
                    } else {
                        LOG.error(IgniteStringFormatter.format("Table wasn't created [name={}]", name), ex);

                        tblFut.completeExceptionally(ex);

                        tableCreateFuts.values().removeIf(fut -> fut == tblFut);
                    }

                    return null;
                });
            }
        });

        return tblFut;
    }

    /** {@inheritDoc} */
    @Override
    public void alterTable(String name, Consumer<TableChange> tableChange) {
        join(alterTableAsync(name, tableChange));
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Void> alterTableAsync(String name, Consumer<TableChange> tableChange) {
        if (!busyLock.enterBusy()) {
            throw new IgniteException(new NodeStoppingException());
        }
        try {
            return alterTableAsyncInternal(IgniteObjectName.parseCanonicalName(name), tableChange);
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Internal method that alters a cluster table. If an appropriate table does not exist, a future will be
     * completed with {@link TableNotFoundException}.
     *
     * @param name        Table name.
     * @param tableChange Table changer.
     * @return Future representing pending completion of the operation.
     * @throws IgniteException If an unspecified platform exception has happened internally. Is thrown when:
     *                         <ul>
     *                             <li>the node is stopping.</li>
     *                         </ul>
     * @see TableNotFoundException
     */
    @NotNull
    private CompletableFuture<Void> alterTableAsyncInternal(String name, Consumer<TableChange> tableChange) {
        CompletableFuture<Void> tblFut = new CompletableFuture<>();

        tableAsync(name).thenAccept(tbl -> {
            if (tbl == null) {
                tblFut.completeExceptionally(new TableNotFoundException(name));
            } else {
                TableImpl tblImpl = (TableImpl) tbl;

                tablesCfg.tables().change(ch -> {
                    if (ch.get(name) == null) {
                        throw new TableNotFoundException(name);
                    }

                    ch.update(name, tblCh -> {
                                tableChange.accept(tblCh);

                                ((ExtendedTableChange) tblCh).changeSchemas(schemasCh ->
                                        schemasCh.createOrUpdate(String.valueOf(schemasCh.size() + 1), schemaCh -> {
                                            ExtendedTableView currTableView = (ExtendedTableView) tablesCfg.tables().get(name).value();

                                            SchemaDescriptor descriptor;

                                            //TODO IGNITE-15747 Remove try-catch and force configuration validation
                                            // here to ensure a valid configuration passed to prepareSchemaDescriptor() method.
                                            try {
                                                descriptor = SchemaUtils.prepareSchemaDescriptor(
                                                        ((ExtendedTableView) tblCh).schemas().size(),
                                                        tblCh);

                                                descriptor.columnMapping(SchemaUtils.columnMapper(
                                                        tblImpl.schemaView().schema(currTableView.schemas().size()),
                                                        currTableView,
                                                        descriptor,
                                                        tblCh));
                                            } catch (IllegalArgumentException ex) {
                                                // Convert unexpected exceptions here,
                                                // because validation actually happens later,
                                                // when bulk configuration update is applied.
                                                ConfigurationValidationException e =
                                                        new ConfigurationValidationException(ex.getMessage());

                                                e.addSuppressed(ex);

                                                throw e;
                                            }

                                            schemaCh.changeSchema(SchemaSerializerImpl.INSTANCE.serialize(descriptor));
                                        }));
                            }
                    );
                }).whenComplete((res, t) -> {
                    if (t != null) {
                        Throwable ex = getRootCause(t);

                        if (ex instanceof TableNotFoundException) {
                            tblFut.completeExceptionally(ex);
                        } else {
                            LOG.error(IgniteStringFormatter.format("Table wasn't altered [name={}]", name), ex);

                            tblFut.completeExceptionally(ex);
                        }
                    } else {
                        tblFut.complete(res);
                    }
                });
            }
        });

        return tblFut;
    }

    /**
     * Gets a cause exception for a client.
     *
     * @param t Exception wrapper.
     * @return A root exception which will be acceptable to throw for public API.
     */
    //TODO: IGNITE-16051 Implement exception converter for public API.
    private @NotNull IgniteException getRootCause(Throwable t) {
        Throwable ex;

        if (t instanceof CompletionException) {
            if (t.getCause() instanceof ConfigurationChangeException) {
                ex = t.getCause().getCause();
            } else {
                ex = t.getCause();
            }

        } else {
            ex = t;
        }

        return ex instanceof IgniteException ? (IgniteException) ex : new IgniteException(ex);
    }

    /** {@inheritDoc} */
    @Override
    public void dropTable(String name) {
        join(dropTableAsync(name));
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Void> dropTableAsync(String name) {
        if (!busyLock.enterBusy()) {
            throw new IgniteException(new NodeStoppingException());
        }
        try {
            return dropTableAsyncInternal(IgniteObjectName.parseCanonicalName(name));
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Internal method that drops a table with the name specified. If appropriate table does not be found, a future will be
     * completed with {@link TableNotFoundException}.
     *
     * @param name Table name.
     * @return Future representing pending completion of the operation.
     * @throws IgniteException If an unspecified platform exception has happened internally. Is thrown when:
     *                         <ul>
     *                             <li>the node is stopping.</li>
     *                         </ul>
     * @see TableNotFoundException
     */
    @NotNull
    private CompletableFuture<Void> dropTableAsyncInternal(String name) {
        CompletableFuture<Void> dropTblFut = new CompletableFuture<>();

        tableAsyncInternal(name).thenAccept(tbl -> {
            // In case of drop it's an optimization that allows not to fire drop-change-closure if there's no such
            // distributed table and the local config has lagged behind.
            if (tbl == null) {
                dropTblFut.completeExceptionally(new TableNotFoundException(name));
            } else {
                tablesCfg.tables()
                        .change(change -> {
                            if (change.get(name) == null) {
                                throw new TableNotFoundException(name);
                            }

                            change.delete(name);
                        })
                        .whenComplete((res, t) -> {
                            if (t != null) {
                                Throwable ex = getRootCause(t);

                                if (ex instanceof TableNotFoundException) {
                                    dropTblFut.completeExceptionally(ex);
                                } else {
                                    LOG.error(IgniteStringFormatter.format("Table wasn't dropped [name={}]", name), ex);

                                    dropTblFut.completeExceptionally(ex);
                                }
                            } else {
                                dropTblFut.complete(res);
                            }
                        });
            }
        });

        return dropTblFut;
    }

    /** {@inheritDoc} */
    @Override
    public List<Table> tables() {
        return join(tablesAsync());
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<List<Table>> tablesAsync() {
        if (!busyLock.enterBusy()) {
            throw new IgniteException(new NodeStoppingException());
        }
        try {
            return tablesAsyncInternal();
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Internal method for getting table.
     *
     * @return Future representing pending completion of the operation.
     */
    private CompletableFuture<List<Table>> tablesAsyncInternal() {
        // TODO: IGNITE-16288 directTableIds should use async configuration API
        return CompletableFuture.supplyAsync(this::directTableIds)
                .thenCompose(tableIds -> {
                    var tableFuts = new CompletableFuture[tableIds.size()];

                    var i = 0;

                    for (UUID tblId : tableIds) {
                        tableFuts[i++] = tableAsyncInternal(tblId, false);
                    }

                    return CompletableFuture.allOf(tableFuts).thenApply(unused -> {
                        var tables = new ArrayList<Table>(tableIds.size());

                        try {
                            for (var fut : tableFuts) {
                                var table = fut.get();

                                if (table != null) {
                                    tables.add((Table) table);
                                }
                            }
                        } catch (Throwable t) {
                            throw new CompletionException(t);
                        }

                        return tables;
                    });
                });
    }

    /**
     * Collects a list of direct table ids.
     *
     * @return A list of direct table ids.
     */
    private List<UUID> directTableIds() {
        NamedListView<TableView> views = directProxy(tablesCfg.tables()).value();

        List<UUID> tableUuids = new ArrayList<>();

        for (int i = 0; i < views.size(); i++) {
            ExtendedTableView extView = (ExtendedTableView) views.get(i);

            tableUuids.add(extView.id());
        }

        return tableUuids;
    }

    /**
     * Gets direct id of table with {@code tblName}.
     *
     * @param tblName Name of the table.
     * @return Direct id of the table, or {@code null} if the table with the {@code tblName} has not been found.
     */
    @Nullable
    private UUID directTableId(String tblName) {
        try {
            ExtendedTableConfiguration exTblCfg = ((ExtendedTableConfiguration) directProxy(tablesCfg.tables()).get(tblName));

            if (exTblCfg == null) {
                return null;
            } else {
                return exTblCfg.id().value();
            }
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    /**
     * Actual tables map.
     *
     * @return Actual tables map.
     */
    @TestOnly
    public Map<UUID, TableImpl> latestTables() {
        return unmodifiableMap(tablesByIdVv.latest());
    }

    /** {@inheritDoc} */
    @Override
    public Table table(String name) {
        return join(tableAsync(name));
    }

    /** {@inheritDoc} */
    @Override
    public TableImpl table(UUID id) throws NodeStoppingException {
        return join(tableAsync(id));
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Table> tableAsync(String name) {
        return tableAsyncInternal(IgniteObjectName.parseCanonicalName(name))
                .thenApply(Function.identity());
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<TableImpl> tableAsync(UUID id) {
        if (!busyLock.enterBusy()) {
            throw new IgniteException(new NodeStoppingException());
        }
        try {
            return tableAsyncInternal(id, true);
        } finally {
            busyLock.leaveBusy();
        }
    }

    /** {@inheritDoc} */
    @Override
    public TableImpl tableImpl(String name) {
        return join(tableImplAsync(name));
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<TableImpl> tableImplAsync(String name) {
        return tableAsyncInternal(IgniteObjectName.parseCanonicalName(name));
    }

    /**
     * Gets a table by name, if it was created before. Doesn't parse canonical name.
     *
     * @param name Table name.
     * @return Future representing pending completion of the {@code TableManager#tableAsyncInternal} operation.
     * */
    private CompletableFuture<TableImpl> tableAsyncInternal(String name) {
        if (!busyLock.enterBusy()) {
            throw new IgniteException(new NodeStoppingException());
        }
        try {
            UUID tableId = directTableId(name);

            if (tableId == null) {
                return CompletableFuture.completedFuture(null);
            }

            return tableAsyncInternal(tableId, false);
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Internal method for getting table by id.
     *
     * @param id Table id.
     * @param checkConfiguration {@code True} when the method checks a configuration before trying to get a table, {@code false} otherwise.
     * @return Future representing pending completion of the operation.
     */
    private CompletableFuture<TableImpl> tableAsyncInternal(UUID id, boolean checkConfiguration) {
        if (checkConfiguration && !isTableConfigured(id)) {
            return CompletableFuture.completedFuture(null);
        }

        var tbl = tablesByIdVv.latest().get(id);

        if (tbl != null) {
            return CompletableFuture.completedFuture(tbl);
        }

        CompletableFuture<TableImpl> getTblFut = new CompletableFuture<>();

        EventListener<TableEventParameters> clo = new EventListener<>() {
            @Override
            public boolean notify(@NotNull TableEventParameters parameters, @Nullable Throwable e) {
                if (!id.equals(parameters.tableId())) {
                    return false;
                }

                if (e == null) {
                    tablesByIdVv.get(parameters.causalityToken()).thenRun(() -> getTblFut.complete(parameters.table()));
                } else {
                    getTblFut.completeExceptionally(e);
                }

                return true;
            }

            @Override
            public void remove(@NotNull Throwable e) {
                getTblFut.completeExceptionally(e);
            }
        };

        listen(TableEvent.CREATE, clo);

        tbl = tablesByIdVv.latest().get(id);

        if (tbl != null && getTblFut.complete(tbl) || !isTableConfigured(id) && getTblFut.complete(null)) {
            removeListener(TableEvent.CREATE, clo, null);
        }

        return getTblFut;
    }

    /**
     * Checks that the table is configured with specific id.
     *
     * @param id Table id.
     * @return True when the table is configured into cluster, false otherwise.
     */
    private boolean isTableConfigured(UUID id) {
        try {
            ((ExtendedTableConfiguration) getByInternalId(directProxy(tablesCfg.tables()), id)).id().value();

            return true;
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    /**
     * Waits for future result and return, or unwraps {@link CompletionException} to {@link IgniteException} if failed.
     *
     * @param future Completable future.
     * @return Future result.
     */
    private <T> T join(CompletableFuture<T> future) {
        if (!busyLock.enterBusy()) {
            throw new IgniteException(new NodeStoppingException());
        }

        try {
            return future.join();
        } catch (CompletionException ex) {
            throw convertThrowable(ex.getCause());
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Convert to public throwable.
     *
     * @param th Throwable.
     * @return Public throwable.
     */
    private RuntimeException convertThrowable(Throwable th) {
        if (th instanceof RuntimeException) {
            return (RuntimeException) th;
        }

        return new IgniteException(th);
    }

    /**
     * Register the new meta storage listener for changes in the rebalance-specific keys.
     */
    private void registerRebalanceListeners() {
        metaStorageMgr.registerWatchByPrefix(ByteArray.fromString(PENDING_ASSIGNMENTS_PREFIX), new WatchListener() {
            @Override
            public boolean onUpdate(@NotNull WatchEvent evt) {
                if (!busyLock.enterBusy()) {
                    throw new IgniteInternalException(new NodeStoppingException());
                }

                try {
                    assert evt.single();

                    Entry pendingAssignmentsWatchEvent = evt.entryEvent().newEntry();

                    if (pendingAssignmentsWatchEvent.value() == null) {
                        return true;
                    }

                    int part = extractPartitionNumber(pendingAssignmentsWatchEvent.key());
                    UUID tblId = extractTableId(pendingAssignmentsWatchEvent.key(), PENDING_ASSIGNMENTS_PREFIX);

                    String partId = partitionRaftGroupName(tblId, part);

                    // Assignments of the pending rebalance that we received through the meta storage watch mechanism.
                    List<ClusterNode> newPeers = ((List<ClusterNode>) ByteUtils.fromBytes(pendingAssignmentsWatchEvent.value()));

                    var pendingAssignments = metaStorageMgr.get(pendingPartAssignmentsKey(partId)).join();

                    assert pendingAssignmentsWatchEvent.revision() <= pendingAssignments.revision()
                            : "Meta Storage watch cannot notify about an event with the revision that is more than the actual revision.";

                    TableImpl tbl = tablesByIdVv.latest().get(tblId);

                    ExtendedTableConfiguration tblCfg = (ExtendedTableConfiguration) tablesCfg.tables().get(tbl.name());

                    Supplier<RaftGroupListener> raftGrpLsnrSupplier = () -> new PartitionListener(tblId,
                            new VersionedRowStore(
                                    tbl.internalTable().storage().getOrCreatePartition(part), txManager));

                    Supplier<RaftGroupEventsListener> raftGrpEvtsLsnrSupplier = () -> new RebalanceRaftGroupEventsListener(
                            metaStorageMgr,
                            tblCfg,
                            partId,
                            part,
                            busyLock,
                            movePartition(() -> tbl.internalTable().partitionRaftGroupService(part)),
                            rebalanceScheduler);

                    // Stable assignments from the meta store, which revision is bounded by the current pending event.
                    byte[] stableAssignments = metaStorageMgr.get(stablePartAssignmentsKey(partId),
                            pendingAssignmentsWatchEvent.revision()).join().value();

                    List<ClusterNode> assignments = stableAssignments == null
                            // This is for the case when the first rebalance occurs.
                            ? ((List<List<ClusterNode>>) ByteUtils.fromBytes(tblCfg.assignments().value())).get(part)
                            : (List<ClusterNode>) ByteUtils.fromBytes(stableAssignments);

                    var deltaPeers = newPeers.stream()
                            .filter(p -> !assignments.contains(p))
                            .collect(Collectors.toList());

                    try {
                        raftMgr.startRaftGroupNode(partId, assignments, deltaPeers, raftGrpLsnrSupplier,
                                raftGrpEvtsLsnrSupplier);
                    } catch (NodeStoppingException e) {
                        // no-op
                    }

                    // Do not change peers of the raft group if this is a stale event.
                    // Note that we start raft node before for the sake of the consistency in a starting and stopping raft nodes.
                    if (pendingAssignmentsWatchEvent.revision() < pendingAssignments.revision()) {
                        return true;
                    }

                    var newNodes = newPeers.stream().map(n -> new Peer(n.address())).collect(Collectors.toList());

                    RaftGroupService partGrpSvc = tbl.internalTable().partitionRaftGroupService(part);

                    IgniteBiTuple<Peer, Long> leaderWithTerm = partGrpSvc.refreshAndGetLeaderWithTerm().join();

                    ClusterNode localMember = raftMgr.server().clusterService().topologyService().localMember();

                    // run update of raft configuration if this node is a leader
                    if (localMember.address().equals(leaderWithTerm.get1().address())) {
                        partGrpSvc.changePeersAsync(newNodes, leaderWithTerm.get2()).join();
                    }

                    return true;
                } finally {
                    busyLock.leaveBusy();
                }
            }

            @Override
            public void onError(@NotNull Throwable e) {
                LOG.error("Error while processing pending assignments event", e);
            }
        });

        metaStorageMgr.registerWatchByPrefix(ByteArray.fromString(STABLE_ASSIGNMENTS_PREFIX), new WatchListener() {
            @Override
            public boolean onUpdate(@NotNull WatchEvent evt) {
                if (!busyLock.enterBusy()) {
                    throw new IgniteInternalException(new NodeStoppingException());
                }

                try {
                    assert evt.single();

                    Entry stableAssignmentsWatchEvent = evt.entryEvent().newEntry();

                    if (stableAssignmentsWatchEvent.value() == null) {
                        return true;
                    }

                    int part = extractPartitionNumber(stableAssignmentsWatchEvent.key());
                    UUID tblId = extractTableId(stableAssignmentsWatchEvent.key(), STABLE_ASSIGNMENTS_PREFIX);

                    String partId = partitionRaftGroupName(tblId, part);

                    var stableAssignments = (List<ClusterNode>) ByteUtils.fromBytes(stableAssignmentsWatchEvent.value());

                    byte[] pendingFromMetastorage = metaStorageMgr.get(pendingPartAssignmentsKey(partId),
                            stableAssignmentsWatchEvent.revision()).join().value();

                    List<ClusterNode> pendingAssignments = pendingFromMetastorage == null
                            ? Collections.emptyList()
                            : (List<ClusterNode>) ByteUtils.fromBytes(pendingFromMetastorage);

                    List<ClusterNode> appliedPeers = Stream.concat(stableAssignments.stream(), pendingAssignments.stream())
                            .collect(Collectors.toList());

                    try {
                        ClusterNode localMember = raftMgr.server().clusterService().topologyService().localMember();

                        if (!appliedPeers.contains(localMember)) {
                            raftMgr.stopRaftGroup(partId);
                        }
                    } catch (NodeStoppingException e) {
                        // no-op
                    }

                    return true;
                } finally {
                    busyLock.leaveBusy();
                }
            }

            @Override
            public void onError(@NotNull Throwable e) {
                LOG.error("Error while processing stable assignments event", e);
            }
        });
    }

    /**
     * Performs {@link RaftGroupService#changePeersAsync(java.util.List, long)} on a provided raft group service of a partition, so nodes
     * of the corresponding raft group can be reconfigured.
     * Retry mechanism is applied to repeat {@link RaftGroupService#changePeersAsync(java.util.List, long)} if previous one
     * failed with some exception.
     *
     * @param raftGroupServiceSupplier Raft groups service of a partition.
     * @return Function which performs {@link RaftGroupService#changePeersAsync(java.util.List, long)}.
     */
    BiFunction<List<Peer>, Long, CompletableFuture<Void>> movePartition(Supplier<RaftGroupService> raftGroupServiceSupplier) {
        return (List<Peer> peers, Long term) -> {
            if (!busyLock.enterBusy()) {
                throw new IgniteInternalException(new NodeStoppingException());
            }
            try {
                return raftGroupServiceSupplier.get().changePeersAsync(peers, term).handleAsync((resp, err) -> {
                    if (!busyLock.enterBusy()) {
                        throw new IgniteInternalException(new NodeStoppingException());
                    }
                    try {
                        if (err != null) {
                            if (recoverable(err)) {
                                LOG.warn("Recoverable error received during changePeersAsync invocation, retrying", err);
                            } else {
                                // TODO: Ideally, rebalance, which has initiated this invocation should be canceled,
                                // TODO: https://issues.apache.org/jira/browse/IGNITE-17056
                                // TODO: Also it might be reasonable to delegate such exceptional case to a general failure handler.
                                // TODO: At the moment, we repeat such intents as well.
                                LOG.error("Unrecoverable error received during changePeersAsync invocation, retrying", err);
                            }
                            return movePartition(raftGroupServiceSupplier).apply(peers, term);
                        }

                        return CompletableFuture.<Void>completedFuture(null);
                    } finally {
                        busyLock.leaveBusy();
                    }
                }, rebalanceScheduler).thenCompose(Function.identity());
            } finally {
                busyLock.leaveBusy();
            }
        };
    }

    /**
     * Gets a direct accessor for the configuration distributed property.
     * If the metadata access only locally configured the method will return local property accessor.
     *
     * @param property Distributed configuration property to receive direct access.
     * @param <T> Type of the property accessor.
     * @return An accessor for distributive property.
     * @see #getMetadataLocallyOnly
     */
    private <T extends ConfigurationProperty<?>> T directProxy(T property) {
        return getMetadataLocallyOnly ? property : ConfigurationUtil.directProxy(property);
    }
}
