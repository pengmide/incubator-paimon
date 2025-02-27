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

package org.apache.paimon.utils;

import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.RandomAccessInputView;
import org.apache.paimon.data.Segments;
import org.apache.paimon.data.SimpleCollectingOutputView;
import org.apache.paimon.data.serializer.InternalRowSerializer;
import org.apache.paimon.memory.MemorySegment;
import org.apache.paimon.memory.MemorySegmentSource;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Cache records to {@link SegmentsCache} by compacted serializer. */
public class ObjectsCache<K, V> {

    private final SegmentsCache<K> cache;
    private final ObjectSerializer<V> serializer;
    private final InternalRowSerializer rowSerializer;
    private final Function<K, CloseableIterator<InternalRow>> reader;

    public ObjectsCache(
            SegmentsCache<K> cache,
            ObjectSerializer<V> serializer,
            Function<K, CloseableIterator<InternalRow>> reader) {
        this.cache = cache;
        this.serializer = serializer;
        this.rowSerializer = new InternalRowSerializer(serializer.fieldTypes());
        this.reader = reader;
    }

    public List<V> read(K key, Filter<InternalRow> loadFilter, Filter<InternalRow> readFilter)
            throws IOException {
        Segments segments = cache.getSegments(key, k -> readSegments(k, loadFilter));
        List<V> entries = new ArrayList<>();
        RandomAccessInputView view =
                new RandomAccessInputView(
                        segments.segments(), cache.pageSize(), segments.limitInLastSegment());
        BinaryRow binaryRow = new BinaryRow(rowSerializer.getArity());
        while (true) {
            try {
                rowSerializer.mapFromPages(binaryRow, view);
                if (readFilter.test(binaryRow)) {
                    entries.add(serializer.fromRow(binaryRow));
                }
            } catch (EOFException e) {
                return entries;
            }
        }
    }

    private Segments readSegments(K key, Filter<InternalRow> loadFilter) {
        try (CloseableIterator<InternalRow> iterator = reader.apply(key)) {
            ArrayList<MemorySegment> segments = new ArrayList<>();
            MemorySegmentSource segmentSource =
                    () -> MemorySegment.allocateHeapMemory(cache.pageSize());
            SimpleCollectingOutputView output =
                    new SimpleCollectingOutputView(segments, segmentSource, cache.pageSize());
            while (iterator.hasNext()) {
                InternalRow row = iterator.next();
                if (loadFilter.test(row)) {
                    rowSerializer.serializeToPages(row, output);
                }
            }
            return new Segments(segments, output.getCurrentPositionInSegment());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
