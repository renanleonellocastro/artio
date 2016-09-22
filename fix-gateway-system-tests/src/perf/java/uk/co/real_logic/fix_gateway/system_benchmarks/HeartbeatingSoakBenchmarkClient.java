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
package uk.co.real_logic.fix_gateway.system_benchmarks;

import uk.co.real_logic.fix_gateway.builder.HeartbeatEncoder;

import java.io.IOException;
import java.nio.channels.SocketChannel;

import static uk.co.real_logic.fix_gateway.system_benchmarks.BenchmarkConfiguration.INITIATOR_ID;

public final class HeartbeatingSoakBenchmarkClient extends AbstractBenchmarkClient
{

    public static void main(String[] args) throws IOException
    {
        new HeartbeatingSoakBenchmarkClient().runBenchmark();
    }

    public void runBenchmark() throws IOException
    {
        final String initiatorId = INITIATOR_ID;
        final HeartbeatEncoder heartbeat = new HeartbeatEncoder();

        try (final SocketChannel socketChannel = open())
        {
            logon(socketChannel, initiatorId, 1);

            setupHeader(initiatorId, heartbeat.header());

            for (int seqNum = 2; true; seqNum++)
            {
                read(socketChannel);

                final int length = encode(heartbeat, heartbeat.header(), seqNum);

                write(socketChannel, length);

                System.out.println("Sent " + seqNum);
            }
        }
    }

}
