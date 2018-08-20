/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.index.internal.gbptree;

import java.util.Queue;
import java.util.StringJoiner;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import org.neo4j.scheduler.JobScheduler;

import static org.neo4j.scheduler.JobScheduler.Groups.recoveryCleanup;

/**
 * Collects recovery cleanup work to be performed and schedule them one by one in {@link #start()}}.
 * <p>
 * Also see {@link RecoveryCleanupWorkCollector}
 */
public class GroupingRecoveryCleanupWorkCollector extends RecoveryCleanupWorkCollector
{
    private final Queue<CleanupJob> jobs;
    private final JobScheduler jobScheduler;

    /**
     * @param jobScheduler {@link JobScheduler} to queue {@link CleanupJob} into.
     */
    public GroupingRecoveryCleanupWorkCollector( JobScheduler jobScheduler )
    {
        this.jobScheduler = jobScheduler;
        this.jobs = new LinkedBlockingQueue<>();
    }

    @Override
    public void init() throws Throwable
    {
        if ( !jobs.isEmpty() )
        {
            StringJoiner joiner = new StringJoiner( "\n  ", "Did not expect there to be any cleanup jobs still here. Jobs[", "]" );
            consumeAndCloseJobs( cj -> joiner.add( jobs.toString() ) );
            throw new IllegalStateException( joiner.toString() );
        }
    }

    @Override
    public void add( CleanupJob job )
    {
        jobs.add( job );
    }

    @Override
    public void start() throws Throwable
    {
        jobScheduler.schedule( recoveryCleanup, allJobs() );
    }

    @Override
    public void stop() throws Throwable
    {   // no-op
    }

    @Override
    public void shutdown() throws Throwable
    {
        consumeAndCloseJobs( cj -> {} );
    }

    private Runnable allJobs()
    {
        return () ->
        {
            executeWithExecutor( executor ->
            {
                CleanupJob job;
                while ( (job = jobs.poll()) != null )
                {
                    try
                    {
                        job.run( executor );
                    }
                    finally
                    {
                        job.close();
                    }
                }
            } );
        };
    }

    private void consumeAndCloseJobs( Consumer<CleanupJob> consumer )
    {
        CleanupJob job;
        while ( (job = jobs.poll()) != null )
        {
            consumer.accept( job );
            job.close();
        }
    }
}
