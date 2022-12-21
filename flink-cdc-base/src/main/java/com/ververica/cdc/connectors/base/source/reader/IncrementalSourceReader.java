/*
 * Copyright 2022 Ververica Inc.
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

package com.ververica.cdc.connectors.base.source.reader;

import org.apache.flink.annotation.Experimental;
import org.apache.flink.api.connector.source.SourceEvent;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.base.source.reader.RecordEmitter;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.SingleThreadMultiplexSourceReaderBase;
import org.apache.flink.connector.base.source.reader.fetcher.SingleThreadFetcherManager;
import org.apache.flink.connector.base.source.reader.synchronization.FutureCompletingBlockingQueue;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.Preconditions;

import com.ververica.cdc.connectors.base.config.SourceConfig;
import com.ververica.cdc.connectors.base.dialect.DataSourceDialect;
import com.ververica.cdc.connectors.base.source.meta.events.FinishedSnapshotSplitsAckEvent;
import com.ververica.cdc.connectors.base.source.meta.events.FinishedSnapshotSplitsReportEvent;
import com.ververica.cdc.connectors.base.source.meta.events.FinishedSnapshotSplitsRequestEvent;
import com.ververica.cdc.connectors.base.source.meta.events.LatestFinishedSplitsSizeEvent;
import com.ververica.cdc.connectors.base.source.meta.events.LatestFinishedSplitsSizeRequestEvent;
import com.ververica.cdc.connectors.base.source.meta.events.StreamSplitMetaEvent;
import com.ververica.cdc.connectors.base.source.meta.events.StreamSplitMetaRequestEvent;
import com.ververica.cdc.connectors.base.source.meta.events.SuspendStreamReaderAckEvent;
import com.ververica.cdc.connectors.base.source.meta.events.SuspendStreamReaderEvent;
import com.ververica.cdc.connectors.base.source.meta.events.WakeupReaderEvent;
import com.ververica.cdc.connectors.base.source.meta.offset.Offset;
import com.ververica.cdc.connectors.base.source.meta.split.FinishedSnapshotSplitInfo;
import com.ververica.cdc.connectors.base.source.meta.split.SnapshotSplit;
import com.ververica.cdc.connectors.base.source.meta.split.SnapshotSplitState;
import com.ververica.cdc.connectors.base.source.meta.split.SourceRecords;
import com.ververica.cdc.connectors.base.source.meta.split.SourceSplitBase;
import com.ververica.cdc.connectors.base.source.meta.split.SourceSplitSerializer;
import com.ververica.cdc.connectors.base.source.meta.split.SourceSplitState;
import com.ververica.cdc.connectors.base.source.meta.split.StreamSplit;
import com.ververica.cdc.connectors.base.source.meta.split.StreamSplitState;
import io.debezium.relational.TableId;
import io.debezium.relational.history.TableChanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.ververica.cdc.connectors.base.source.meta.split.StreamSplit.toNormalStreamSplit;
import static com.ververica.cdc.connectors.base.source.meta.split.StreamSplit.toSuspendedStreamSplit;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * The multi-parallel source reader for table snapshot phase from {@link SnapshotSplit} and then
 * single-parallel source reader for table stream phase from {@link StreamSplit}.
 */
@Experimental
public class IncrementalSourceReader<T, C extends SourceConfig>
        extends SingleThreadMultiplexSourceReaderBase<
                SourceRecords, T, SourceSplitBase, SourceSplitState> {

    private static final Logger LOG = LoggerFactory.getLogger(IncrementalSourceReader.class);

    private final Map<String, SnapshotSplit> finishedUnackedSplits;
    private final Map<String, StreamSplit> uncompletedStreamSplits;
    private final int subtaskId;
    private final SourceSplitSerializer sourceSplitSerializer;
    private final C sourceConfig;
    private final DataSourceDialect<C> dialect;
    private final IncrementalSourceReaderContext sourceReaderContext;
    private StreamSplit suspendedStreamSplit;

    public IncrementalSourceReader(
            FutureCompletingBlockingQueue<RecordsWithSplitIds<SourceRecords>> elementQueue,
            Supplier<IncrementalSourceSplitReader<C>> splitReaderSupplier,
            RecordEmitter<SourceRecords, T, SourceSplitState> recordEmitter,
            Configuration config,
            IncrementalSourceReaderContext context,
            C sourceConfig,
            SourceSplitSerializer sourceSplitSerializer,
            DataSourceDialect<C> dialect) {
        super(
                elementQueue,
                new SingleThreadFetcherManager<>(elementQueue, splitReaderSupplier::get),
                recordEmitter,
                config,
                context.getSourceReaderContext());
        this.sourceConfig = sourceConfig;
        this.finishedUnackedSplits = new HashMap<>();
        this.uncompletedStreamSplits = new HashMap<>();
        this.subtaskId = context.getSourceReaderContext().getIndexOfSubtask();
        this.sourceSplitSerializer = checkNotNull(sourceSplitSerializer);
        this.dialect = dialect;
        this.sourceReaderContext = context;
        this.suspendedStreamSplit = null;
    }

    @Override
    public void start() {
        if (getNumberOfCurrentlyAssignedSplits() == 0) {
            context.sendSplitRequest();
        }
    }

    @Override
    protected SourceSplitState initializedState(SourceSplitBase split) {
        if (split.isSnapshotSplit()) {
            return new SnapshotSplitState(split.asSnapshotSplit());
        } else {
            return new StreamSplitState(split.asStreamSplit());
        }
    }

    @Override
    public List<SourceSplitBase> snapshotState(long checkpointId) {
        // unfinished splits
        List<SourceSplitBase> stateSplits = super.snapshotState(checkpointId);

        // unfinished splits
        List<SourceSplitBase> unfinishedSplits =
                stateSplits.stream()
                        .filter(split -> !finishedUnackedSplits.containsKey(split.splitId()))
                        .collect(Collectors.toList());

        // add finished snapshot splits that didn't receive ack yet
        unfinishedSplits.addAll(finishedUnackedSplits.values());

        // add stream splits who are uncompleted
        unfinishedSplits.addAll(uncompletedStreamSplits.values());

        // add suspended stream split
        if (suspendedStreamSplit != null) {
            unfinishedSplits.add(suspendedStreamSplit);
        }

        return unfinishedSplits;
    }

    @Override
    protected void onSplitFinished(Map<String, SourceSplitState> finishedSplitIds) {
        boolean requestNextSplit = true;
        for (SourceSplitState splitState : finishedSplitIds.values()) {
            SourceSplitBase sourceSplit = splitState.toSourceSplit();

            if (sourceSplit.isStreamSplit()) {
                LOG.info(
                        "stream split reader suspended due to newly added table, offset {}",
                        sourceSplit.asStreamSplit().getStartingOffset());

                sourceReaderContext.resetStopStreamSplitReader();
                suspendedStreamSplit = toSuspendedStreamSplit(sourceSplit.asStreamSplit());
                context.sendSourceEventToCoordinator(new SuspendStreamReaderAckEvent());
                // do not request next split when the reader is suspended, the suspended reader will
                // automatically request the next split after it has been wakeup
                requestNextSplit = false;
            } else {
                finishedUnackedSplits.put(sourceSplit.splitId(), sourceSplit.asSnapshotSplit());
            }
        }
        reportFinishedSnapshotSplitsIfNeed();
        if (requestNextSplit) {
            context.sendSplitRequest();
        }
    }

    @Override
    public void addSplits(List<SourceSplitBase> splits) {
        // restore for finishedUnackedSplits
        List<SourceSplitBase> unfinishedSplits = new ArrayList<>();
        for (SourceSplitBase split : splits) {
            LOG.info("Add Split: " + split);
            if (split.isSnapshotSplit()) {
                SnapshotSplit snapshotSplit = split.asSnapshotSplit();
                if (snapshotSplit.isSnapshotReadFinished()) {
                    finishedUnackedSplits.put(snapshotSplit.splitId(), snapshotSplit);
                } else {
                    unfinishedSplits.add(split);
                }
            } else {
                StreamSplit streamSplit = split.asStreamSplit();
                // the stream split is suspended
                if (streamSplit.isSuspended()) {
                    suspendedStreamSplit = streamSplit;
                } else if (!streamSplit.isCompletedSplit()) {
                    uncompletedStreamSplits.put(split.splitId(), streamSplit);
                    requestStreamSplitMetaIfNeeded(streamSplit);
                } else {
                    uncompletedStreamSplits.remove(split.splitId());
                    streamSplit = discoverTableSchemasForStreamSplit(streamSplit);
                    unfinishedSplits.add(streamSplit);
                }
            }
        }
        // notify split enumerator again about the finished unacked snapshot splits
        reportFinishedSnapshotSplitsIfNeed();
        // add all un-finished splits (including stream split) to SourceReaderBase
        if (!unfinishedSplits.isEmpty()) {
            super.addSplits(unfinishedSplits);
        }
    }

    private StreamSplit discoverTableSchemasForStreamSplit(StreamSplit split) {
        final String splitId = split.splitId();
        if (split.getTableSchemas().isEmpty()) {
            try {
                Map<TableId, TableChanges.TableChange> tableSchemas =
                        dialect.discoverDataCollectionSchemas(sourceConfig);
                LOG.info("The table schema discovery for stream split {} success", splitId);
                return StreamSplit.fillTableSchemas(split, tableSchemas);
            } catch (Exception e) {
                LOG.error("Failed to obtains table schemas due to {}", e.getMessage());
                throw new FlinkRuntimeException(e);
            }
        } else {
            LOG.warn(
                    "The stream split {} has table schemas yet, skip the table schema discovery",
                    split);
            return split;
        }
    }

    @Override
    public void handleSourceEvents(SourceEvent sourceEvent) {
        if (sourceEvent instanceof FinishedSnapshotSplitsAckEvent) {
            FinishedSnapshotSplitsAckEvent ackEvent = (FinishedSnapshotSplitsAckEvent) sourceEvent;
            LOG.debug(
                    "The subtask {} receives ack event for {} from enumerator.",
                    subtaskId,
                    ackEvent.getFinishedSplits());
            for (String splitId : ackEvent.getFinishedSplits()) {
                this.finishedUnackedSplits.remove(splitId);
            }
        } else if (sourceEvent instanceof FinishedSnapshotSplitsRequestEvent) {
            // report finished snapshot splits
            LOG.debug(
                    "The subtask {} receives request to report finished snapshot splits.",
                    subtaskId);
            reportFinishedSnapshotSplitsIfNeed();
        } else if (sourceEvent instanceof StreamSplitMetaEvent) {
            LOG.debug(
                    "The subtask {} receives stream meta with group id {}.",
                    subtaskId,
                    ((StreamSplitMetaEvent) sourceEvent).getMetaGroupId());
            fillMetaDataForStreamSplit((StreamSplitMetaEvent) sourceEvent);
        } else if (sourceEvent instanceof SuspendStreamReaderEvent) {
            sourceReaderContext.setStopStreamSplitReader();
        } else if (sourceEvent instanceof WakeupReaderEvent) {
            WakeupReaderEvent wakeupReaderEvent = (WakeupReaderEvent) sourceEvent;
            if (wakeupReaderEvent.getTarget() == WakeupReaderEvent.WakeUpTarget.SNAPSHOT_READER) {
                context.sendSplitRequest();
            } else {
                if (suspendedStreamSplit != null) {
                    context.sendSourceEventToCoordinator(
                            new LatestFinishedSplitsSizeRequestEvent());
                }
            }
        } else if (sourceEvent instanceof LatestFinishedSplitsSizeEvent) {
            if (suspendedStreamSplit != null) {
                final int finishedSplitsSize =
                        ((LatestFinishedSplitsSizeEvent) sourceEvent).getLatestFinishedSplitsSize();
                final StreamSplit streamSplit =
                        toNormalStreamSplit(suspendedStreamSplit, finishedSplitsSize);
                suspendedStreamSplit = null;
                this.addSplits(Collections.singletonList(streamSplit));
            }
        } else {
            super.handleSourceEvents(sourceEvent);
        }
    }

    private void fillMetaDataForStreamSplit(StreamSplitMetaEvent metadataEvent) {
        StreamSplit streamSplit = uncompletedStreamSplits.get(metadataEvent.getSplitId());
        if (streamSplit != null) {
            final int receivedMetaGroupId = metadataEvent.getMetaGroupId();
            final int expectedMetaGroupId =
                    getNextMetaGroupId(
                            streamSplit.getFinishedSnapshotSplitInfos().size(),
                            sourceConfig.getSplitMetaGroupSize());
            if (receivedMetaGroupId == expectedMetaGroupId) {
                List<FinishedSnapshotSplitInfo> metaDataGroup =
                        metadataEvent.getMetaGroup().stream()
                                .map(sourceSplitSerializer::deserialize)
                                .collect(Collectors.toList());
                uncompletedStreamSplits.put(
                        streamSplit.splitId(),
                        StreamSplit.appendFinishedSplitInfos(streamSplit, metaDataGroup));

                LOG.info("Fill meta data of group {} to stream split", metaDataGroup.size());
            } else {
                LOG.warn(
                        "Received out of order metadata event for split {}, the received meta group id is {}, but expected is {}, ignore it",
                        metadataEvent.getSplitId(),
                        receivedMetaGroupId,
                        expectedMetaGroupId);
            }
            requestStreamSplitMetaIfNeeded(uncompletedStreamSplits.get(streamSplit.splitId()));
        } else {
            LOG.warn(
                    "Received metadata event for split {}, but the uncompleted split map does not contain it",
                    metadataEvent.getSplitId());
        }
    }

    private void requestStreamSplitMetaIfNeeded(StreamSplit streamSplit) {
        final String splitId = streamSplit.splitId();
        if (!streamSplit.isCompletedSplit()) {
            final int nextMetaGroupId =
                    getNextMetaGroupId(
                            streamSplit.getFinishedSnapshotSplitInfos().size(),
                            sourceConfig.getSplitMetaGroupSize());
            StreamSplitMetaRequestEvent splitMetaRequestEvent =
                    new StreamSplitMetaRequestEvent(splitId, nextMetaGroupId);
            context.sendSourceEventToCoordinator(splitMetaRequestEvent);
        } else {
            LOG.info("The meta of stream split {} has been collected success", splitId);
            this.addSplits(Collections.singletonList(streamSplit));
        }
    }

    private void reportFinishedSnapshotSplitsIfNeed() {
        if (!finishedUnackedSplits.isEmpty()) {
            final Map<String, Offset> finishedOffsets = new HashMap<>();
            for (SnapshotSplit split : finishedUnackedSplits.values()) {
                finishedOffsets.put(split.splitId(), split.getHighWatermark());
            }
            FinishedSnapshotSplitsReportEvent reportEvent =
                    new FinishedSnapshotSplitsReportEvent(finishedOffsets);
            context.sendSourceEventToCoordinator(reportEvent);
            LOG.debug(
                    "The subtask {} reports offsets of finished snapshot splits {}.",
                    subtaskId,
                    finishedOffsets);
        }
    }

    /** Returns next meta group id according to received meta number and meta group size. */
    public static int getNextMetaGroupId(int receivedMetaNum, int metaGroupSize) {
        Preconditions.checkState(metaGroupSize > 0);
        return receivedMetaNum % metaGroupSize == 0
                ? (receivedMetaNum / metaGroupSize)
                : (receivedMetaNum / metaGroupSize) + 1;
    }

    @Override
    protected SourceSplitBase toSplitType(String splitId, SourceSplitState splitState) {
        return splitState.toSourceSplit();
    }
}
