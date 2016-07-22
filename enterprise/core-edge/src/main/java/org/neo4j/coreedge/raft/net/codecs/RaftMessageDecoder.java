/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.net.codecs;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.io.IOException;
import java.util.List;

import org.neo4j.coreedge.raft.RaftMessages;
import org.neo4j.coreedge.raft.log.RaftLogEntry;
import org.neo4j.coreedge.raft.net.NetworkReadableClosableChannelNetty4;
import org.neo4j.coreedge.raft.replication.ReplicatedContent;
import org.neo4j.coreedge.raft.replication.storeid.StoreIdMarshal;
import org.neo4j.coreedge.raft.state.ChannelMarshal;
import org.neo4j.coreedge.raft.state.EndOfStreamException;
import org.neo4j.coreedge.server.MemberId;
import org.neo4j.coreedge.server.StoreId;
import org.neo4j.storageengine.api.ReadableChannel;

import static org.neo4j.coreedge.raft.RaftMessages.Type.APPEND_ENTRIES_REQUEST;
import static org.neo4j.coreedge.raft.RaftMessages.Type.APPEND_ENTRIES_RESPONSE;
import static org.neo4j.coreedge.raft.RaftMessages.Type.HEARTBEAT;
import static org.neo4j.coreedge.raft.RaftMessages.Type.LOG_COMPACTION_INFO;
import static org.neo4j.coreedge.raft.RaftMessages.Type.NEW_ENTRY_REQUEST;
import static org.neo4j.coreedge.raft.RaftMessages.Type.VOTE_REQUEST;
import static org.neo4j.coreedge.raft.RaftMessages.Type.VOTE_RESPONSE;

public class RaftMessageDecoder extends MessageToMessageDecoder<ByteBuf>
{
    private final ChannelMarshal<ReplicatedContent> marshal;

    public RaftMessageDecoder( ChannelMarshal<ReplicatedContent> marshal )
    {
        this.marshal = marshal;
    }

    @Override
    protected void decode( ChannelHandlerContext ctx, ByteBuf buffer, List<Object> list ) throws Exception
    {
        ReadableChannel channel = new NetworkReadableClosableChannelNetty4( buffer );
        StoreId storeId = StoreIdMarshal.unmarshal( channel );

        int messageTypeWire = channel.getInt();
        RaftMessages.Type[] values = RaftMessages.Type.values();
        RaftMessages.Type messageType = values[messageTypeWire];

        MemberId from = retrieveMember( channel );
        RaftMessages.RaftMessage result;

        if ( messageType.equals( VOTE_REQUEST ) )
        {
            MemberId candidate = retrieveMember( channel );

            long term = channel.getLong();
            long lastLogIndex = channel.getLong();
            long lastLogTerm = channel.getLong();

            result = new RaftMessages.Vote.Request(
                    from, term, candidate, lastLogIndex, lastLogTerm );
        }
        else if ( messageType.equals( VOTE_RESPONSE ) )
        {
            long term = channel.getLong();
            boolean voteGranted = channel.get() == 1;

            result = new RaftMessages.Vote.Response( from, term, voteGranted );
        }
        else if ( messageType.equals( APPEND_ENTRIES_REQUEST ) )
        {
            // how many
            long term = channel.getLong();
            long prevLogIndex = channel.getLong();
            long prevLogTerm = channel.getLong();

            long leaderCommit = channel.getLong();
            long count = channel.getLong();

            RaftLogEntry[] entries = new RaftLogEntry[(int) count];
            for ( int i = 0; i < count; i++ )
            {
                long entryTerm = channel.getLong();
                final ReplicatedContent content = marshal.unmarshal( channel );
                entries[i] = new RaftLogEntry( entryTerm, content );
            }

            result = new RaftMessages.AppendEntries.Request( from, term, prevLogIndex, prevLogTerm, entries,
                    leaderCommit );
        }
        else if ( messageType.equals( APPEND_ENTRIES_RESPONSE ) )
        {
            long term = channel.getLong();
            boolean success = channel.get() == 1;
            long matchIndex = channel.getLong();
            long appendIndex = channel.getLong();

            result = new RaftMessages.AppendEntries.Response( from, term, success, matchIndex, appendIndex );
        }
        else if ( messageType.equals( NEW_ENTRY_REQUEST ) )
        {
            ReplicatedContent content = marshal.unmarshal( channel );

            result = new RaftMessages.NewEntry.Request( from, content );
        }
        else if ( messageType.equals( HEARTBEAT ) )
        {
            long leaderTerm = channel.getLong();
            long commitIndexTerm = channel.getLong();
            long commitIndex = channel.getLong();

            result = new RaftMessages.Heartbeat( from, leaderTerm, commitIndex, commitIndexTerm );
        }
        else if ( messageType.equals( LOG_COMPACTION_INFO ) )
        {
            long leaderTerm = channel.getLong();
            long prevIndex = channel.getLong();

            result = new RaftMessages.LogCompactionInfo( from, leaderTerm, prevIndex );
        }
        else
        {
            throw new IllegalArgumentException( "Unknown message type" );
        }

        list.add( new RaftMessages.StoreIdAwareMessage( storeId, result ) );
    }

    private MemberId retrieveMember( ReadableChannel buffer ) throws IOException, EndOfStreamException
    {
        MemberId.MemberIdMarshal memberIdMarshal = new MemberId.MemberIdMarshal();
        return memberIdMarshal.unmarshal( buffer );
    }
}
