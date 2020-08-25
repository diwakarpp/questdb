package io.questdb.cairo;

import org.jetbrains.annotations.Nullable;

import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cairo.sql.PageFrame;
import io.questdb.cairo.sql.PageFrameCursor;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.cairo.sql.SymbolTable;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.std.LongList;
import io.questdb.std.Misc;
import io.questdb.std.Numbers;
import io.questdb.std.Unsafe;

public class TableReplicationRecordCursorFactory extends AbstractRecordCursorFactory {
    private final TableReplicationRecordCursor cursor;
    private final CairoEngine engine;
    private final CharSequence tableName;

    private static final RecordMetadata createMetadata(CairoEngine engine, CharSequence tableName) {
        try (TableReader reader = engine.getReader(AllowAllCairoSecurityContext.INSTANCE, tableName, -1)) {
            return GenericRecordMetadata.copyOf(reader.getMetadata());
        }
    }

    public TableReplicationRecordCursorFactory(CairoEngine engine, CharSequence tableName) {
        super(createMetadata(engine, tableName));
        this.cursor = new TableReplicationRecordCursor();
        this.engine = engine;
        this.tableName = tableName;
    }

    @Override
    public TableReplicationRecordCursor getPageFrameCursor(SqlExecutionContext executionContext) {
        return cursor.of(engine.getReader(executionContext.getCairoSecurityContext(), tableName));
    }

    public TableReplicationRecordCursor getPageFrameCursorFrom(SqlExecutionContext executionContext, long nFirstRow) {
        TableReader reader = engine.getReader(executionContext.getCairoSecurityContext(), tableName);
        int partitionIndex = 0;
        int partitionCount = reader.getPartitionCount();
        while (partitionIndex < partitionCount) {
            long partitionRowCount = reader.openPartition(partitionIndex);
            if (nFirstRow < partitionRowCount) {
                break;
            }
            partitionIndex++;
            nFirstRow -= partitionRowCount;
        }
        return cursor.of(reader, partitionIndex, nFirstRow);
    }

    public TableReplicationRecordCursor getPageFrameCursor(int partitionIndex, long paritionRowCount) {
        return cursor.of(engine.getReader(AllowAllCairoSecurityContext.INSTANCE, tableName), partitionIndex, paritionRowCount);
    }

    @Override
    public void close() {
        Misc.free(cursor);
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return false;
    }

    public static class TableReplicationRecordCursor implements PageFrameCursor {
        private final LongList columnFrameAddresses = new LongList();
        private final LongList columnFrameLengths = new LongList();
        private final ReplicationPageFrame frame = new ReplicationPageFrame();
        private TableReader reader;
        private int partitionIndex;
        private int partitionCount;
        private int columnCount;
        private int timestampColumnIndex;
        private long nFirstFrameRow;
        private long nFrameRows;
        private long firstTimestamp = Long.MIN_VALUE;
        private long lastTimestamp = Numbers.LONG_NaN;;

        private TableReplicationRecordCursor() {
            super();
        }

        @Override
        public void close() {
            if (null != reader) {
                reader = Misc.free(reader);
                reader = null;
            }
        }

        @Override
        public SymbolTable getSymbolTable(int columnIndex) {
            return reader.getSymbolMapReader(columnIndex);
        }

        private TableReplicationRecordCursor of(TableReader reader, int partitionIndex, long partitionRowCount) {
            of(reader);
            this.partitionIndex = partitionIndex - 1;
            nFirstFrameRow = partitionRowCount;

            return this;
        }

        private TableReplicationRecordCursor of(TableReader reader) {
            this.reader = reader;
            columnCount = reader.getMetadata().getColumnCount();
            timestampColumnIndex = reader.getMetadata().getTimestampIndex();
            columnFrameAddresses.ensureCapacity(columnCount);
            columnFrameLengths.ensureCapacity(columnCount);
            toTop();
            return this;
        }

        @Override
        public @Nullable ReplicationPageFrame next() {
            while (++partitionIndex < partitionCount) {
                nFrameRows = reader.openPartition(partitionIndex);
                if (nFrameRows > nFirstFrameRow) {
                    final int base = reader.getColumnBase(partitionIndex);
                    final long maxRows = reader.getPartitionRowCount(partitionIndex);
                    for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                        final ReadOnlyColumn col = reader.getColumn(TableReader.getPrimaryColumnIndex(base, columnIndex));
                        assert col.getPageCount() == 1;
                        long columnPageAddress = col.getPageAddress(0);
                        long columnPageLength;

                        int columnType = reader.getMetadata().getColumnType(columnIndex);
                        switch (columnType) {
                            case ColumnType.STRING: {
                                final ReadOnlyColumn strLenCol = reader.getColumn(TableReader.getPrimaryColumnIndex(base, columnIndex) + 1);
                                columnPageLength = calculateStringPagePosition(col, strLenCol, nFrameRows, maxRows);

                                if (nFirstFrameRow > 0) {
                                    long columnPageBegin = calculateStringPagePosition(col, strLenCol, nFirstFrameRow, maxRows);
                                    columnPageAddress += columnPageBegin;
                                    columnPageLength -= columnPageBegin;
                                }

                                break;
                            }

                            case ColumnType.BINARY: {
                                final ReadOnlyColumn binLenCol = reader.getColumn(TableReader.getPrimaryColumnIndex(base, columnIndex) + 1);
                                columnPageLength = calculateBinaryPagePosition(col, binLenCol, nFrameRows, maxRows);

                                if (nFirstFrameRow > 0) {
                                    long columnPageBegin = calculateBinaryPagePosition(col, binLenCol, nFirstFrameRow, maxRows);
                                    columnPageAddress += columnPageBegin;
                                    columnPageLength -= columnPageBegin;
                                }

                                break;
                            }

                            default: {
                                int columnSizeBinaryPower = Numbers.msb(ColumnType.sizeOf(reader.getMetadata().getColumnType(columnIndex)));
                                columnPageLength = nFrameRows << columnSizeBinaryPower;
                                if (nFirstFrameRow > 0) {
                                    long columnPageBegin = nFirstFrameRow << columnSizeBinaryPower;
                                    columnPageAddress += columnPageBegin;
                                    columnPageLength -= columnPageBegin;
                                }
                            }
                        }

                        columnFrameAddresses.setQuick(columnIndex, columnPageAddress);
                        columnFrameLengths.setQuick(columnIndex, columnPageLength);

                        if (timestampColumnIndex == columnIndex) {
                            firstTimestamp = Unsafe.getUnsafe().getLong(columnPageAddress);
                            lastTimestamp = Unsafe.getUnsafe().getLong(columnPageAddress + columnPageLength - Long.BYTES);
                        }
                    }

                    nFrameRows -= nFirstFrameRow;
                    nFirstFrameRow = 0;
                    return frame;
                }
                nFirstFrameRow = 0;
            }
            return null;
        }

        private long calculateBinaryPagePosition(final ReadOnlyColumn col, final ReadOnlyColumn binLenCol, long row, long maxRows) {
            assert row > 0;

            if (row < (maxRows - 1)) {
                long binLenOffset = row << 3;
                return binLenCol.getLong(binLenOffset);
            }

            long columnPageLength;
            long lastBinLenOffset = (row - 1) << 3;
            long lastBinOffset = binLenCol.getLong(lastBinLenOffset);
            long lastBinLen = col.getBinLen(lastBinOffset);
            if (lastBinLen == TableUtils.NULL_LEN) {
                lastBinLen = 0;
            }
            columnPageLength = lastBinOffset + Long.BYTES + lastBinLen;
            return columnPageLength;
        }

        private long calculateStringPagePosition(final ReadOnlyColumn col, final ReadOnlyColumn strLenCol, long row, long maxRows) {
            assert row > 0;

            if (row < (maxRows - 1)) {
                long strLenOffset = row << 3;
                return strLenCol.getLong(strLenOffset);
            }

            long columnPageLength;
            long lastStrLenOffset = (row - 1) << 3;
            long lastStrOffset = strLenCol.getLong(lastStrLenOffset);
            int lastStrLen = col.getStrLen(lastStrOffset);
            if (lastStrLen == TableUtils.NULL_LEN) {
                lastStrLen = 0;
            }
            columnPageLength = lastStrOffset + VirtualMemory.STRING_LENGTH_BYTES + lastStrLen * 2;
            return columnPageLength;
        }

        @Override
        public void toTop() {
            partitionIndex = -1;
            partitionCount = reader.getPartitionCount();
            firstTimestamp = Long.MIN_VALUE;
            lastTimestamp = 0;
        }

        @Override
        public long size() {
            return reader.size();
        }

        public class ReplicationPageFrame implements PageFrame {

            @Override
            public long getPageAddress(int columnIndex) {
                return columnFrameAddresses.getQuick(columnIndex);
            }

            @Override
            public long getPageValueCount(int columnIndex) {
                return nFrameRows;
            }

            @Override
            public long getFirstTimestamp() {
                return firstTimestamp;
            }

            @Override
            public long getLastTimestamp() {
                return lastTimestamp;
            }

            public int getPartitionIndex() {
                return partitionIndex;
            }

            @Override
            public long getPageLength(int columnIndex) {
                return columnFrameLengths.getQuick(columnIndex);
            }
        }
    }
}
