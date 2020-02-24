/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.index.label;

import org.eclipse.collections.api.iterator.LongIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.neo4j.collection.PrimitiveLongResourceIterator;
import org.neo4j.index.internal.gbptree.GBPTree;
import org.neo4j.index.internal.gbptree.Seeker;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.kernel.api.index.IndexProgressor;

import static org.neo4j.internal.index.label.LabelScanValue.RANGE_SIZE;
import static org.neo4j.internal.index.label.NativeLabelScanWriter.rangeOf;

/**
 * {@link LabelScanReader} for reading data from {@link NativeLabelScanStore}.
 * Each {@link LongIterator} returned from each of the methods has a {@link Seeker}
 * directly from {@link GBPTree#seek(Object, Object, PageCursorTracer)} backing it.
 */
class NativeLabelScanReader implements LabelScanReader
{
    /**
     * Index that is queried when calling the methods below.
     */
    private final GBPTree<LabelScanKey,LabelScanValue> index;

    NativeLabelScanReader( GBPTree<LabelScanKey,LabelScanValue> index )
    {
        this.index = index;
    }

    @Override
    public PrimitiveLongResourceIterator nodesWithLabel( int labelId, PageCursorTracer cursorTracer )
    {
        Seeker<LabelScanKey,LabelScanValue> cursor;
        try
        {
            cursor = seekerForLabel( 0, labelId, cursorTracer );
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }

        return new LabelScanValueIterator( cursor, LabelScanReader.NO_ID );
    }

    @Override
    public PrimitiveLongResourceIterator nodesWithAnyOfLabels( long fromId, int[] labelIds, PageCursorTracer cursorTracer )
    {
        List<PrimitiveLongResourceIterator> iterators = iteratorsForLabels( fromId, cursorTracer, labelIds );
        return new CompositeLabelScanValueIterator( iterators, false );
    }

    @Override
    public LabelScan nodeLabelScan( int labelId, PageCursorTracer cursorTracer )
    {
        try
        {
            long highestNodeIdForLabel = highestNodeIdForLabel( labelId, cursorTracer );
            return new NativeLabelScan( labelId, highestNodeIdForLabel );
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
    }

    private long highestNodeIdForLabel( int labelId, PageCursorTracer cursorTracer ) throws IOException
    {
        try ( Seeker<LabelScanKey,LabelScanValue> seeker = index.seek( new LabelScanKey( labelId, Long.MAX_VALUE ),
                new LabelScanKey( labelId, Long.MIN_VALUE ), cursorTracer ) )
        {
            return seeker.next() ? (seeker.key().idRange + 1) * RANGE_SIZE : 0;
        }
    }

    private List<PrimitiveLongResourceIterator> iteratorsForLabels( long fromId, PageCursorTracer cursorTracer, int[] labelIds )
    {
        List<PrimitiveLongResourceIterator> iterators = new ArrayList<>();
        try
        {
            for ( int labelId : labelIds )
            {
                Seeker<LabelScanKey,LabelScanValue> cursor = seekerForLabel( fromId, labelId, cursorTracer );
                iterators.add( new LabelScanValueIterator( cursor, fromId ) );
            }
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
        return iterators;
    }

    private Seeker<LabelScanKey,LabelScanValue> seekerForLabel( long startId, int labelId, PageCursorTracer cursorTracer ) throws IOException
    {
        LabelScanKey from = new LabelScanKey( labelId, rangeOf( startId ) );
        LabelScanKey to = new LabelScanKey( labelId, Long.MAX_VALUE );
        return index.seek( from, to, cursorTracer );
    }

    private Seeker<LabelScanKey,LabelScanValue> seekerForLabel( long startId, long stopId, int labelId, PageCursorTracer cursorTracer ) throws IOException
    {
        LabelScanKey from = new LabelScanKey( labelId, rangeOf( startId ) );
        LabelScanKey to = new LabelScanKey( labelId, rangeOf( stopId ) );

        return index.seek( from, to, cursorTracer );
    }

    private class NativeLabelScan implements LabelScan
    {
        private final AtomicLong nextStart;
        private final int labelId;
        private final long max;

        NativeLabelScan( int labelId, long max )
        {
            this.labelId = labelId;
            this.max = max;
            nextStart = new AtomicLong( 0 );
        }

        @Override
        public IndexProgressor initialize( IndexProgressor.NodeLabelClient client, PageCursorTracer cursorTracer )
        {
            return init( client, 0L, Long.MAX_VALUE, cursorTracer );
        }

        @Override
        public IndexProgressor initializeBatch( IndexProgressor.NodeLabelClient client, int sizeHint, PageCursorTracer cursorTracer )
        {
            if ( sizeHint == 0 )
            {
                return IndexProgressor.EMPTY;
            }
            long size = roundUp( sizeHint );
            long start = nextStart.getAndAdd( size );
            long stop = Math.min( start + size, max );
            if ( start >= max )
            {
                return IndexProgressor.EMPTY;
            }
            return init( client, start, stop, cursorTracer );
        }

        private IndexProgressor init( IndexProgressor.NodeLabelClient client, long start, long stop, PageCursorTracer cursorTracer )
        {
            Seeker<LabelScanKey,LabelScanValue> cursor;
            try
            {
                cursor = seekerForLabel( start, stop, labelId, cursorTracer );
            }
            catch ( IOException e )
            {
                throw new UncheckedIOException( e );
            }

            return new LabelScanValueIndexProgressor( cursor, client );
        }

        private long roundUp( long sizeHint )
        {
            return (sizeHint / RANGE_SIZE + 1) * RANGE_SIZE;
        }
    }
}