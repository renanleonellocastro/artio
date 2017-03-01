/*
 * Copyright 2015-2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.engine;

import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.CompositeAgent;
import uk.co.real_logic.fix_gateway.FixCounters;
import uk.co.real_logic.fix_gateway.engine.logger.ArchiveReader;
import uk.co.real_logic.fix_gateway.engine.logger.Archiver;
import uk.co.real_logic.fix_gateway.engine.logger.ReplayQuery;
import uk.co.real_logic.fix_gateway.engine.logger.Replayer;
import uk.co.real_logic.fix_gateway.protocol.GatewayPublication;
import uk.co.real_logic.fix_gateway.protocol.Streams;
import uk.co.real_logic.fix_gateway.replication.*;

import static org.agrona.concurrent.AgentRunner.startOnThread;
import static uk.co.real_logic.fix_gateway.GatewayProcess.INBOUND_LIBRARY_STREAM;
import static uk.co.real_logic.fix_gateway.GatewayProcess.OUTBOUND_LIBRARY_STREAM;
import static uk.co.real_logic.fix_gateway.dictionary.generation.Exceptions.closeAll;
import static uk.co.real_logic.fix_gateway.dictionary.generation.Exceptions.suppressingClose;
import static uk.co.real_logic.fix_gateway.engine.logger.LoggerUtil.newArchiveMetaData;
import static uk.co.real_logic.fix_gateway.replication.ClusterNodeConfiguration.DEFAULT_DATA_STREAM_ID;

class ClusterContext extends EngineContext
{
    private final Publication inboundPublication;
    private final StreamIdentifier dataStream;
    private final ClusterAgent clusterAgent;

    ClusterContext(
        final EngineConfiguration configuration,
        final ErrorHandler errorHandler,
        final Publication replayPublication,
        final FixCounters fixCounters,
        final Aeron aeron,
        final EngineDescriptorStore engineDescriptorStore)
    {
        super(configuration, errorHandler, fixCounters, aeron);

        Replayer replayer = null;
        Archiver localInboundArchiver = null;
        Archiver localOutboundArchiver = null;

        try
        {
            final String channel = configuration.clusterAeronChannel();
            dataStream = new StreamIdentifier(channel, DEFAULT_DATA_STREAM_ID);
            final String libraryAeronChannel = configuration.libraryAeronChannel();
            inboundPublication = aeron.addPublication(libraryAeronChannel, INBOUND_LIBRARY_STREAM);
            clusterAgent = node(configuration, fixCounters, aeron, channel, engineDescriptorStore);
            newStreams(clusterAgent.clusterStreams());
            newIndexers(inboundArchiveReader(), outboundArchiveReader(), null);

            replayer = newReplayer(replayPublication, outboundArchiveReader());

            localInboundArchiver = archiver(
                new StreamIdentifier(libraryAeronChannel, INBOUND_LIBRARY_STREAM),
                inboundCompletionPosition());
            localInboundArchiver.subscription(
                aeron.addSubscription(libraryAeronChannel, INBOUND_LIBRARY_STREAM));
            localOutboundArchiver = archiver(
                new StreamIdentifier(libraryAeronChannel, OUTBOUND_LIBRARY_STREAM),
                outboundLibraryCompletionPosition());
            localOutboundArchiver.subscription(
                aeron.addSubscription(libraryAeronChannel, OUTBOUND_LIBRARY_STREAM));

            final ClusterPositionSender positionSender = new ClusterPositionSender(
                outboundLibrarySubscription("positionSender"),
                outboundClusterSubscription(),
                inboundLibraryPublication(),
                configuration.agentNamePrefix());

            localOutboundArchiver.positionHandler(positionSender);

            loggingRunner = newRunner(
                new CompositeAgent(
                    inboundIndexer,
                    outboundIndexer,
                    clusterAgent,
                    replayer,
                    localInboundArchiver,
                    localOutboundArchiver,
                    positionSender));
        }
        catch (final Exception e)
        {
            completeDuringStartup();

            closeAll(replayer, localInboundArchiver, localOutboundArchiver);

            suppressingClose(this, e);

            throw e;
        }
    }

    private ClusterAgent node(
        final EngineConfiguration configuration,
        final FixCounters fixCounters,
        final Aeron aeron,
        final String clusterAeronChannel,
        final EngineDescriptorStore engineDescriptorStore)
    {
        final int cacheNumSets = configuration.loggerCacheNumSets();
        final int cacheSetSize = configuration.loggerCacheSetSize();
        final String logFileDir = configuration.logFileDir();

        final Archiver archiver = new Archiver(
            newArchiveMetaData(logFileDir), cacheNumSets, cacheSetSize, dataStream, configuration.agentNamePrefix(),
            outboundClusterCompletionPosition());

        final ClusterNodeConfiguration clusterNodeConfiguration = new ClusterNodeConfiguration()
            .nodeId(configuration.nodeId())
            .otherNodes(configuration.otherNodes())
            .timeoutIntervalInMs(configuration.clusterTimeoutIntervalInMs())
            .idleStrategy(configuration.framerIdleStrategy())
            .archiver(archiver)
            .archiveReaderSupplier(() -> archiveReader(dataStream))
            .failCounter(fixCounters.failedRaftPublications())
            .maxClaimAttempts(configuration.inboundMaxClaimAttempts())
            .copyTo(inboundPublication)
            .aeronChannel(clusterAeronChannel)
            .aeron(aeron)
            .nodeState(EngineDescriptorFactory.make(configuration.libraryAeronChannel()))
            .nodeStateHandler(engineDescriptorStore)
            .nodeHandler(configuration.roleHandler())
            .agentNamePrefix(configuration.agentNamePrefix());

        return new ClusterAgent(clusterNodeConfiguration, System.currentTimeMillis());
    }

    private ArchiveReader outboundArchiveReader()
    {
        return archiveReader(dataStream, OUTBOUND_LIBRARY_STREAM);
    }

    private ArchiveReader inboundArchiveReader()
    {
        return archiveReader(dataStream, INBOUND_LIBRARY_STREAM);
    }

    public ReplayQuery inboundReplayQuery()
    {
        return newReplayQuery(inboundArchiveReader());
    }

    public ClusterableStreams streams()
    {
        return clusterAgent.clusterStreams();
    }

    public void start()
    {
        startOnThread(loggingRunner);
    }

    public GatewayPublication inboundLibraryPublication()
    {
        return new GatewayPublication(
            ClusterablePublication.solo(inboundPublication),
            fixCounters.failedInboundPublications(),
            configuration.framerIdleStrategy(),
            nanoClock,
            configuration.inboundMaxClaimAttempts()
        );
    }

    public Streams outboundLibraryStreams()
    {
        return outboundLibraryStreams;
    }

    public Streams inboundLibraryStreams()
    {
        return inboundLibraryStreams;
    }

    public ClusterSubscription outboundClusterSubscription()
    {
        return (ClusterSubscription) outboundLibraryStreams().subscription("outboundClusterSubscription");
    }

    public void close()
    {
        closeAll(loggingRunner, super::close);
    }
}
