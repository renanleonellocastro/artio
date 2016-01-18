/*
 * Copyright 2015 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.replication;

import org.junit.*;
import org.junit.rules.Timeout;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.fix_gateway.DebugLogger;

import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.*;
import static uk.co.real_logic.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH;

/**
 * Test simulated cluster.
 */
public class ClusterReplicationTest
{

    public static final int BUFFER_SIZE = 16;
    public static final int POSITION_AFTER_MESSAGE = BUFFER_SIZE + HEADER_LENGTH;

    private UnsafeBuffer buffer = new UnsafeBuffer(new byte[BUFFER_SIZE]);

    private final NodeRunner node1 = new NodeRunner(1, 2, 3);
    private final NodeRunner node2 = new NodeRunner(2, 1, 3);
    private final NodeRunner node3 = new NodeRunner(3, 1, 2);
    private final NodeRunner[] allNodes = { node1, node2, node3 };

    @Rule
    public Timeout timeout = Timeout.seconds(10);

    @Before
    public void hasElectedLeader()
    {
        while (!foundLeader())
        {
            pollAll();
        }

        DebugLogger.log("Leader elected");
    }

    @Test
    public void shouldEstablishCluster()
    {
        checkClusterStable();
    }

    @Test
    public void shouldReplicateMessage()
    {
        final NodeRunner leader = leader();

        DebugLogger.log("Leader is %s\n", leader.raftNode().nodeId());

        final long position = sendMessageTo(leader);

        DebugLogger.log("Leader @ %s\n", position);

        assertMessageReceived();
    }

    @Test
    public void shouldReformClusterAfterLeaderPause()
    {
        awaitLeadershipConcensus();

        final NodeRunner leader = leader();
        final NodeRunner[] followers = followers();

        while (!foundLeader(followers))
        {
            poll(followers);
        }

        assertBecomesFollower(leader);
    }

    // TODO: unignore once its easy to loss generated both inbound and outbound traffic.
    @Ignore
    @Test
    public void shouldReformClusterAfterLeaderNetsplit()
    {
        final NodeRunner leader = leader();
        final NodeRunner[] followers = followers();

        leader.dropFrames(true);

        assertElectsNewLeader(followers);

        leader.dropFrames(false);

        assertBecomesFollower(leader);
    }

    @Ignore
    @Test
    public void shouldRejoinClusterAfterFollowerNetsplit()
    {
        final NodeRunner follower = aFollower();

        follower.dropFrames(true);

        assertBecomesCandidate(follower);

        follower.dropFrames(false);

        assertBecomesFollower(follower);
    }

    @Ignore
    @Test
    public void shouldNotReplicateMessageUntilClusterReformed()
    {
        final NodeRunner leader = leader();
        final NodeRunner follower = aFollower();

        follower.dropFrames(true);

        sendMessageTo(leader);

        assertBecomesCandidate(follower);

        assertTrue(notAllNodesReceivedMessage());

        follower.dropFrames(false);

        assertBecomesFollower(follower);

        assertMessageReceived();
    }

    @Ignore
    @Test
    public void shouldReformClusterAfterFollowerNetsplit()
    {
        final NodeRunner[] followers = followers();

        nodes().forEach(nodeRunner -> nodeRunner.dropFrames(true));

        assertBecomesCandidate(followers);

        nodes().forEach(nodeRunner -> nodeRunner.dropFrames(false));

        assertBecomesFollower(followers);

        assertTrue(foundLeader());
    }

    private NodeRunner aFollower()
    {
        return followers()[0];
    }

    private void assertBecomesCandidate(final NodeRunner ... nodes)
    {
        assertBecomes(RaftNode::isCandidate, allNodes, nodes);
    }

    private void assertBecomesFollower(final NodeRunner ... nodes)
    {
        assertBecomes(RaftNode::isFollower, allNodes, nodes);
    }

    private void assertBecomes(
        final Predicate<RaftNode> predicate,
        final NodeRunner[] toPoll,
        final NodeRunner... nodes)
    {
        final RaftNode[] raftNodes = getRaftNodes(nodes);
        assertFalse(allMatch(raftNodes, predicate));
        while (!allMatch(raftNodes, predicate))
        {
            poll(toPoll);
        }
        assertTrue(allMatch(raftNodes, predicate));
    }

    private RaftNode[] getRaftNodes(final NodeRunner[] nodes)
    {
        return Stream.of(nodes).map(NodeRunner::raftNode).toArray(RaftNode[]::new);
    }

    private static <T> boolean allMatch(final T[] values, final Predicate<T> predicate)
    {
        return Stream.of(values).allMatch(predicate);
    }

    private void assertElectsNewLeader(final NodeRunner ... followers)
    {
        while (!foundLeader(followers))
        {
            pollAll();
        }
    }

    private void assertMessageReceived()
    {
        while (notAllNodesReceivedMessage())
        {
            pollAll();
        }
    }

    private boolean notAllNodesReceivedMessage()
    {
        return notReceivedMessage(node1) && notReceivedMessage(node2) && notReceivedMessage(node3);
    }

    private void checkClusterStable()
    {
        for (int i = 0; i < 10; i++)
        {
            pollAll();
        }

        hasElectedLeader();

        assertAllNodesSeeSameLeader();

        DebugLogger.log("Cluster Stable");
    }

    private void awaitLeadershipConcensus()
    {
        final TermState state1 = node1.raftNode().termState();
        final TermState state2 = node2.raftNode().termState();
        final TermState state3 = node3.raftNode().termState();

        while (!(state1.leaderSessionId() == state2.leaderSessionId() &&
                 state1.leaderSessionId() == state3.leaderSessionId()))
        {
            pollAll();
        }
    }

    private void assertAllNodesSeeSameLeader()
    {
        final TermState state1 = node1.raftNode().termState();
        final TermState state2 = node2.raftNode().termState();
        final TermState state3 = node3.raftNode().termState();

        final int leaderSessionId = state1.leaderSessionId();
        assertEquals("1 and 2 disagree on leader", leaderSessionId, state2.leaderSessionId());
        assertEquals("1 and 3 disagree on leader", leaderSessionId, state3.leaderSessionId());
    }

    private void assertIsFollower(final NodeRunner follower)
    {
        final RaftNode node = follower.raftNode();
        assertTrue(node.nodeId() + " no longer follower", node.isFollower());
    }

    private boolean notReceivedMessage(final NodeRunner node)
    {
        return node.replicatedPosition() < POSITION_AFTER_MESSAGE;
    }

    private long sendMessageTo(final NodeRunner leader)
    {
        final ConsistentPublication publication = leader.raftNode().publication();

        long position = 0;
        while (position <= 0)
        {
            position = publication.offer(buffer, 0, BUFFER_SIZE);
            pause();
            pollAll();
        }
        return position;
    }

    private void pause()
    {
        LockSupport.parkNanos(1000);
    }

    private void pollAll()
    {
        poll(allNodes);
    }

    private void poll(final NodeRunner ... nodes)
    {
        final int fragmentLimit = 1;
        for (final NodeRunner node : nodes)
        {
            node.poll(fragmentLimit, System.currentTimeMillis());
        }
        LockSupport.parkNanos(MILLISECONDS.toNanos(1));
    }

    private boolean foundLeader()
    {
        return foundLeader(node1, node2, node3);
    }

    private boolean foundLeader(NodeRunner ... nodes)
    {
        final long leaderCount = Stream.of(nodes).filter(NodeRunner::isLeader).count();
        return leaderCount == 1;
    }

    private NodeRunner leader()
    {
        return nodes()
            .filter(NodeRunner::isLeader)
            .findFirst()
            .get(); // Just error the test if there's not a leader
    }

    private NodeRunner[] followers()
    {
        return nodes().filter(node -> !node.isLeader()).toArray(NodeRunner[]::new);
    }

    private Stream<NodeRunner> nodes()
    {
        return Stream.of(node1, node2, node3);
    }

    @After
    public void shutdown()
    {
        node1.close();
        node2.close();
        node3.close();
    }

}
