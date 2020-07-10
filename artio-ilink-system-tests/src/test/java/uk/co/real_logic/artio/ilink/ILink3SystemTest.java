/*
 * Copyright 2020 Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.ilink;

import iLinkBinary.*;
import iLinkBinary.PartyDetailsDefinitionRequest518Encoder.NoPartyDetailsEncoder;
import iLinkBinary.PartyDetailsDefinitionRequest518Encoder.NoTrdRegPublicationsEncoder;
import io.aeron.archive.ArchivingMediaDriver;
import org.agrona.CloseHelper;
import org.agrona.LangUtil;
import org.agrona.collections.IntArrayList;
import org.agrona.concurrent.errors.ErrorConsumer;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Test;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.Timing;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.LowResourceEngineScheduler;
import uk.co.real_logic.artio.library.*;
import uk.co.real_logic.artio.system_tests.Backup;
import uk.co.real_logic.artio.system_tests.TestSystem;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.LongSupplier;

import static iLinkBinary.KeepAliveLapsed.NotLapsed;
import static io.aeron.CommonContext.IPC_CHANNEL;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.TestFixtures.*;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.ilink.ILink3TestServer.RETRANSMIT_REJECT_ERROR_CODES;
import static uk.co.real_logic.artio.ilink.ILink3TestServer.RETRANSMIT_REJECT_REASON;
import static uk.co.real_logic.artio.library.ILink3Connection.NOT_AWAITING_RETRANSMIT;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class ILink3SystemTest
{
    private static final int TEST_KEEP_ALIVE_INTERVAL_IN_MS = 500;

    static final String ACCESS_KEY_ID = "12345678901234567890";
    static final String SESSION_ID = "ABC";
    static final String FIRM_ID = "DEFGH";
    static final String USER_KEY = "somethingprivate";
    static final String CL_ORD_ID = "123";
    public static final int ER_STATUS_ID = ExecutionReportStatus532Decoder.TEMPLATE_ID;

    private FakeILink3ConnectionHandler handler = spy(new FakeILink3ConnectionHandler(NotAppliedResponse::gapfill));

    private int testKeepAliveIntervalInMs = TEST_KEEP_ALIVE_INTERVAL_IN_MS;
    private final int port = unusedPort();
    private ArchivingMediaDriver mediaDriver;
    private TestSystem testSystem;
    private FixEngine engine;
    private FixLibrary library;
    private ILink3TestServer testServer;
    private Reply<ILink3Connection> reply;
    private ILink3Connection connection;
    private final ErrorConsumer errorConsumer = mock(ErrorConsumer.class);

    private boolean noExpectedError;

    public void launch(final boolean noExpectedError)
    {
        this.noExpectedError = noExpectedError;
        delete(CLIENT_LOGS);

        mediaDriver = launchMediaDriver();

        launchArtio();
    }

    private void launchArtio()
    {
        final EngineConfiguration engineConfig = new EngineConfiguration()
            .logFileDir(CLIENT_LOGS)
            .scheduler(new LowResourceEngineScheduler())
            .replyTimeoutInMs(TEST_REPLY_TIMEOUT_IN_MS)
            .libraryAeronChannel(IPC_CHANNEL)
            .lookupDefaultAcceptorfixDictionary(false)
            .customErrorConsumer(errorConsumer);
        engine = FixEngine.launch(engineConfig);

        testSystem = new TestSystem();

        final LibraryConfiguration libraryConfig = new LibraryConfiguration()
            .libraryAeronChannels(singletonList(IPC_CHANNEL))
            .replyTimeoutInMs(TEST_REPLY_TIMEOUT_IN_MS);
        libraryConfig.customErrorConsumer(errorConsumer);
        library = testSystem.connect(libraryConfig);
    }

    @After
    public void close()
    {
        closeArtio();
        cleanupMediaDriver(mediaDriver);

        if (!noExpectedError)
        {
            verifyNoInteractions(errorConsumer);
        }
    }

    private void closeArtio()
    {
        testSystem.awaitBlocking(() -> CloseHelper.close(engine));
        CloseHelper.close(library);
    }

    @Test
    public void shouldEstablishConnectionAtBeginningOfWeek() throws IOException
    {
        launch(true);

        establishNewConnection();
    }

    @Test
    public void shouldCloseShouldShutdownOpenConnections() throws IOException
    {
        launch(true);

        establishNewConnection();
    }

    @Test
    public void shouldSupportInitiatorTerminateConnection() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        terminateAndDisconnect();
    }

    @Test
    public void shouldAcceptExchangeInitiatedTerminate() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        testServer.writeTerminate();

        testSystem.awaitUnbind(connection);

        testServer.readTerminate();

        assertDisconnected();
    }

    @Test
    public void shouldNotifyIncorrectUuidExchangeInitiatedTerminate() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        testServer.writeTerminate(0);

        testSystem.awaitUnbind(connection);

        testServer.readTerminate();

        assertDisconnected();

        final List<Exception> exceptions = handler.exceptions();

        Timing.assertEventuallyTrue("Failed to receive error", () -> !exceptions.isEmpty());

        assertThat(exceptions, hasSize(1));
        assertThat(exceptions.get(0).getMessage(), containsString("Invalid uuid=0"));
    }

    @Test
    public void shouldExchangeBusinessMessage() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        sendNewOrderSingle();

        testServer.readNewOrderSingle(1);
        testServer.writeExecutionReportStatus(1, false);

        agreeRecvSeqNo(2);
        final IntArrayList messageIds = handler.messageIds();
        assertThat(messageIds, hasSize(1));
        assertEquals(messageIds.getInt(0), ER_STATUS_ID);

        terminateAndDisconnect();
    }

    @Test
    public void shouldExchangeVariableLengthBusinessMessage() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        sendPartyDetailsDefinitionRequest();

        testServer.readPartyDetailsDefinitionRequest(1, 1);

        terminateAndDisconnect();
    }

    private void sendPartyDetailsDefinitionRequest()
    {
        final PartyDetailsDefinitionRequest518Encoder partyDetailsDefinitionRequest =
            new PartyDetailsDefinitionRequest518Encoder();
        final int variableLength = NoPartyDetailsEncoder.HEADER_SIZE + NoPartyDetailsEncoder.sbeBlockLength() +
            NoTrdRegPublicationsEncoder.HEADER_SIZE;
        assertThat(connection.tryClaim(partyDetailsDefinitionRequest, variableLength), greaterThan(0L));

        partyDetailsDefinitionRequest
            .partyDetailsListReqID(1)
            .listUpdateAction(ListUpdAct.Add)
            .memo(0, PartyDetailsDefinitionRequest518Encoder.memoNullValue())
            .avgPxGroupID(0, PartyDetailsDefinitionRequest518Encoder.avgPxGroupIDNullValue())
            .selfMatchPreventionID(PartyDetailsDefinitionRequest518Encoder.selfMatchPreventionIDNullValue())
            .cmtaGiveupCD(CmtaGiveUpCD.GiveUp)
            .custOrderCapacity(CustOrderCapacity.Clearingfirmtradingforitsproprietaryaccount)
            .clearingAccountType(ClearingAcctType.Firm)
            .selfMatchPreventionInstruction(SMPI.CancelOldest)
            .avgPxIndicator(AvgPxInd.NoAveragePricing)
            .clearingTradePriceType(SLEDS.TradeClearingatExecutionPrice)
            .custOrderHandlingInst(CustOrdHandlInst.AlgoEngine)
            .executor(PartyDetailsDefinitionRequest518Encoder.executorNullValue())
            .iDMShortCode(PartyDetailsDefinitionRequest518Encoder.iDMShortCodeNullValue())
            .noPartyDetailsCount(1)
                .next()
                .partyDetailID("abc")
                .partyDetailRole(PartyDetailRole.ExecutingFirm);
        partyDetailsDefinitionRequest.noTrdRegPublicationsCount(0);

        connection.commit();
    }

    @Test
    public void shouldProvideErrorUponConnectionFailure()
    {
        launch(true);

        reply = library.initiate(connectionConfiguration().build());
        assertConnectError(containsString("UNABLE_TO_CONNECT"));
    }

    @Test
    public void shouldResendNegotiateAndEstablishOnTimeout() throws IOException
    {
        launch(true);

        connectToTestServer(connectionConfiguration());

        readNegotiate();
        readNegotiate();

        testServer.writeNegotiateResponse();

        readEstablish();
        readEstablish();
        testServer.writeEstablishmentAck(0, 0, 1);

        acquireSession();
    }

    @Test
    public void shouldDisconnectIfNegotiateNotRespondedTo() throws IOException
    {
        launch(true);

        connectToTestServer(connectionConfiguration());

        readNegotiate();
        readNegotiate();
        assertConnectError(containsString(""));
        assertDisconnected();
    }

    @Test
    public void shouldSupportNegotiationReject() throws IOException
    {
        launch(true);

        connectToTestServer(connectionConfiguration());

        readNegotiate();

        testServer.writeNegotiateReject();

        assertConnectError(containsString("Negotiate rejected"));
        assertDisconnected();
    }

    @Test
    public void shouldSupportEstablishmentReject() throws IOException
    {
        launch(true);

        connectToTestServer(connectionConfiguration());

        readNegotiate();

        testServer.writeEstablishmentReject();

        assertConnectError(containsString("Establishment rejected"));
        assertDisconnected();
    }

    @Test
    public void shouldSupportReestablishingConnections() throws IOException
    {
        shouldExchangeBusinessMessage();

        final long lastUuid = connection.uuid();

        connectToTestServer(connectionConfiguration().reEstablishLastConnection(true));

        testServer.expectedUuid(lastUuid);

        readEstablish(2);
        testServer.writeEstablishmentAck(1, lastUuid, 2);

        acquireSession();
    }

    @Test
    public void shouldSupportReestablishingConnectionsAfterRestart() throws IOException
    {
        shouldExchangeBusinessMessage();

        final long lastUuid = connection.uuid();

        closeArtio();
        launchArtio();

        final ILink3ConnectionConfiguration.Builder connectionConfiguration = connectionConfiguration()
            .reEstablishLastConnection(true);
        connectToTestServer(connectionConfiguration);

        testServer.expectedUuid(lastUuid);

        readEstablish(2);
        testServer.writeEstablishmentAck(1, lastUuid, 2);

        acquireSession();
    }

    @Test
    public void shouldAllowRestablishmentFirstTime() throws IOException
    {
        launch(true);

        connectToTestServer(connectionConfiguration().reEstablishLastConnection(true));

        establishConnection();
    }

    @Test
    public void shouldSupportResetState() throws IOException
    {
        final Backup backup = new Backup();

        try
        {
            shouldExchangeBusinessMessage();

            final long lastUuid = connection.uuid();

            closeArtio();

            backup.resetState(engine);
            backup.assertStateReset(mediaDriver, is(0));
            backup.assertRecordingsTruncated();
            // Idempotence
            backup.resetState(engine);

            // Test that a gateway can be restarted and a new session established after the state reset.
            launchArtio();
            final ILink3ConnectionConfiguration.Builder connectionConfiguration = connectionConfiguration()
                .reEstablishLastConnection(true);
            connectToTestServer(connectionConfiguration);
            establishConnection();
            assertNotEquals(connection.uuid(), lastUuid);
        }
        finally
        {
            backup.cleanup();
        }
    }

    @Test
    public void shouldSupportSequenceMessageHeartbeating() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        // From customer - as a heartbeat message to be sent when a KeepAliveInterval interval from customer lapses
        // and no other message is sent to CME
        sleepHalfInterval();
        testServer.writeSequence(1, NotLapsed);
        testServer.readSequence(1, NotLapsed);

        // From CME - as a heartbeat message to be sent when a KeepAliveInterval interval from CME lapses and
        // no other message is sent to customer
        final InternalILink3Connection session = (InternalILink3Connection)this.connection;
        final long oldTimeout = session.nextReceiveMessageTimeInMs();
        testServer.writeSequence(1, NotLapsed);

        assertEventuallyTrue("Timeout error", () ->
        {
            testSystem.poll();

            final long timeout = session.nextReceiveMessageTimeInMs();

            return timeout > oldTimeout && timeout > System.currentTimeMillis();
        });

        // From CME - when a KeepAliveInterval of the customer lapses without having received any message from them then
        // send message with KeepAliveIntervalLapsed=1 as a warning before initiating disconnect of socket connection
        // Interpret this as a must-reply to these messages
        final long timeout = session.nextSendMessageTimeInMs();
        testServer.writeSequence(1, KeepAliveLapsed.Lapsed);
        testServer.readSequence(1, NotLapsed);
        assertThat(System.currentTimeMillis(), lessThan(timeout));

        // From customer - when a KeepAliveInterval of CME lapses without having received any message from CME then send
        // message with KeepAliveIntervalLapsed=1 as a warning before initiating disconnect of socket connection
        // Send a message in order to suppress our own NotLapsed sequence keepalive and force a Lapsed one.
        sleepHalfInterval();
        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);
        testServer.readSequence(2, KeepAliveLapsed.Lapsed);
        testServer.readTerminate();
        testServer.assertDisconnected();
    }

    @Test
    public void shouldSupportNotAppliedMessageSequenceMessageResponse() throws IOException
    {
        // From customer - to reset sequence number in response to Not Applied message sent by CME when CME detects a
        // sequence gap from customer
        shouldEstablishConnectionAtBeginningOfWeek();

        connection.nextSentSeqNo(3);
        sendNewOrderSingle();

        testServer.readNewOrderSingle(3);
        testServer.writeNotApplied(1, 3);

        assertEventuallyTrue("", () ->
        {
            testSystem.poll();
            return handler.hasReceivedNotApplied();
        });

        testServer.readSequence(4, NotLapsed);
    }

    @Test
    public void shouldSupportRetransmitInResponseToNotAppliedMessage() throws IOException
    {
        handler = new FakeILink3ConnectionHandler(response ->
        {
            response.retransmit();

            // We shouldn't be allowed to send messages whilst a retransmit is occurring.
            assertThrows(IllegalStateException.class, this::sendNewOrderSingle);
        });

        shouldEstablishConnectionAtBeginningOfWeek();

        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);

        sendNewOrderSingle();
        testServer.readNewOrderSingle(2);

        sendNewOrderSingle();
        testServer.readNewOrderSingle(3);

        // Let's pretend we haven't received 1 and 2 and initiate a resend.
        testServer.writeNotApplied(1, 2);

        testServer.readNewOrderSingle(1);
        testServer.readNewOrderSingle(2);

        assertEventuallyTrue("Session never re-establishes", () ->
        {
            testSystem.poll();
            return connection.state() == ILink3Connection.State.ESTABLISHED;
        });

        sendNewOrderSingle();
    }

    @Test
    public void shouldRequestRetransmitForSequenceNumberGap() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);

        testServer.writeExecutionReportStatus(3, false);

        testServer.acceptRetransRequest(1, 2);

        testServer.writeExecutionReportStatus(1, true);
        testServer.writeExecutionReportStatus(4, false);
        testServer.writeExecutionReportStatus(2, true);

        agreeRecvSeqNo(5);

        terminateAndDisconnect();
    }

    @Test
    public void shouldRequestRetransmitForSequenceNumberGapWithSequenceMessage() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);

        testServer.writeExecutionReportStatus(1, false);
        testServer.writeSequence(4, NotLapsed);

        testServer.acceptRetransRequest(2, 2);

        testServer.writeExecutionReportStatus(2, true);
        testServer.writeExecutionReportStatus(4, false);
        testServer.writeExecutionReportStatus(3, true);

        agreeRecvSeqNo(5);

        terminateAndDisconnect();
    }

    @Test
    public void shouldAcceptSequenceMessageAsAGapFillForARetransmission() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);

        testServer.writeExecutionReportStatus(1, false);
        testServer.writeSequence(4, NotLapsed);

        testServer.acceptRetransRequest(2, 2);
        testServer.writeSequence(4, NotLapsed);

        agreeRecvSeqNo(4);

        // Check there's no second retransmission
        testServer.readSequence(2, NotLapsed);

        testServer.writeExecutionReportStatus(4, false);
        agreeRecvSeqNo(5);

        terminateAndDisconnect();
    }

    @Test
    public void shouldRequestRetransmitForSequenceNumberGapWithinRetransmission() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);

        testServer.writeExecutionReportStatus(4, false);

        testServer.acceptRetransRequest(1, 3);

        testServer.writeExecutionReportStatus(1, true);
        testServer.writeExecutionReportStatus(3, true);

        testServer.acceptRetransRequest(2, 1);

        testServer.writeExecutionReportStatus(2, true);

        agreeRecvSeqNo(5);

        terminateAndDisconnect();
    }

    @Test
    public void shouldOnlyHaveASingleRequestRetransmitInflight() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        testServer.writeExecutionReportStatus(2, false);
        testServer.writeExecutionReportStatus(4, false);

        testServer.acceptRetransRequest(1, 1);
        agreeRecvSeqNo(5);

        // Ensure that the second retransmit request isn't sent yet
        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);

        // Fill First
        assertEquals(1, connection.retransmitFillSeqNo());
        testServer.writeExecutionReportStatus(1, true);

        testServer.acceptRetransRequest(3, 1);

        // Fill second
        assertEquals(3, connection.retransmitFillSeqNo());
        testServer.writeExecutionReportStatus(3, true);

        agreeRetransmitFillSeqNo(NOT_AWAITING_RETRANSMIT);
        agreeRecvSeqNo(5);

        terminateAndDisconnect();
    }

    @Test
    public void shouldLimitLargeRetransmitRequestsIntoBatches() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        testServer.writeExecutionReportStatus(5000, false);

        testServer.acceptRetransRequest(1, 2500);
        writeExecutionReports(1, 2500);

        testServer.canSkip(Sequence506Decoder.TEMPLATE_ID);

        testServer.acceptRetransRequest(2501, 2499);
        writeExecutionReports(2501, 2499);

        agreeRetransmitFillSeqNo(NOT_AWAITING_RETRANSMIT);
        agreeRecvSeqNo(5001);

        terminateAndDisconnect();
    }

    @Test
    public void shouldNotStallUponARetransmitReject() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();

        testServer.writeExecutionReportStatus(5000, false);

        testServer.rejectRetransRequest(1, 2500);
        testServer.rejectRetransRequest(2501, 2499);

        agreeRetransmitFillSeqNo(NOT_AWAITING_RETRANSMIT);
        agreeRecvSeqNo(5001);

        verify(handler, times(2))
            .onRetransmitReject(eq(RETRANSMIT_REJECT_REASON), anyLong(), anyLong(), eq(RETRANSMIT_REJECT_ERROR_CODES));

        terminateAndDisconnect();
    }

    @Test
    public void shouldRequestRetransmitForEstablishGap() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();
        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);
        terminateAndDisconnect();
        // nextSent=2,nextRecv=1

        final long lastUuid = connection.uuid();

        connectToTestServer(connectionConfiguration().reEstablishLastConnection(true));

        testServer.expectedUuid(lastUuid);

        readEstablish(2);
        // Initiator missed receiving message 1
        testServer.writeEstablishmentAck(1, lastUuid, 2);
        acquireSession();

        // retransmit message 1
        testServer.acceptRetransRequest(1, 1);
        agreeRecvSeqNo(2);

        testServer.writeExecutionReportStatus(2, false);
        testServer.writeExecutionReportStatus(1, true);

        agreeRecvSeqNo(3);
        agreeRetransmitFillSeqNo(NOT_AWAITING_RETRANSMIT);

        terminateAndDisconnect();
    }

    @Test
    public void shouldRequestRetransmitForEstablishGapOnPreviousUuid() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();
        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);
        terminateAndDisconnect();
        // nextSent=2,nextRecv=1

        final long lastUuid = connection.uuid();

        connectToTestServer(connectionConfiguration());

        readNegotiate();
        testServer.writeNegotiateResponse();

        readEstablish(1);
        // Initiator missed receiving message 1
        testServer.writeEstablishmentAck(1, lastUuid, 1);
        acquireSession();
        assertRecvSeqNo(1);

        // retransmit message 1
        testServer.acceptRetransRequest(lastUuid, 1, 1);
        testServer.writeExecutionReportStatus(1, false);
        testServer.writeExecutionReportStatus(lastUuid, 1, true);

        agreeRecvSeqNo(2);
        agreeRetransmitFillSeqNo(NOT_AWAITING_RETRANSMIT);

        assertThat(handler.messageIds(), contains(ER_STATUS_ID, ER_STATUS_ID));

        terminateAndDisconnect();
    }

    @Test
    public void shouldRequestRetransmitForEstablishGapOnUsedPreviousUuid() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();
        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);
        assertRecvSeqNo(1);
        testServer.writeExecutionReportStatus(1, false);
        agreeRecvSeqNo(2);
        terminateAndDisconnect();
        // nextSent=2,nextRecv=1

        final long lastUuid = connection.uuid();

        connectToTestServer(connectionConfiguration());

        readNegotiate();
        testServer.writeNegotiateResponse();

        readEstablish(1);
        // Initiator missed receiving message 2
        testServer.writeEstablishmentAck(2, lastUuid, 1);
        acquireSession();
        assertRecvSeqNo(1);

        // retransmit message 1
        testServer.acceptRetransRequest(lastUuid, 2, 1);
        testServer.writeExecutionReportStatus(1, false);
        testServer.writeExecutionReportStatus(lastUuid, 2, true);

        agreeRecvSeqNo(2);
        agreeRetransmitFillSeqNo(NOT_AWAITING_RETRANSMIT);

        assertThat(handler.messageIds(), contains(ER_STATUS_ID, ER_STATUS_ID, ER_STATUS_ID));

        terminateAndDisconnect();
    }

    private void assertRecvSeqNo(final int nextRecvSeqNo)
    {
        assertEquals(connection.nextRecvSeqNo(), nextRecvSeqNo);
    }

    @Test
    public void shouldNotRequestRetransmitForNoEstablishGapOnPreviousUuid() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();
        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);
        terminateAndDisconnect();
        // nextSent=2,nextRecv=1

        final long lastUuid = connection.uuid();

        connectToTestServer(connectionConfiguration());

        readNegotiate();
        testServer.writeNegotiateResponse();

        readEstablish(1);
        // Initiator missed receiving message 1
        testServer.writeEstablishmentAck(0, lastUuid, 1);
        acquireSession();
        assertRecvSeqNo(1);
        agreeRetransmitFillSeqNo(NOT_AWAITING_RETRANSMIT);

        terminateAndDisconnect();
    }

    @Test
    public void shouldTerminateALowSequenceNumberSequenceMessage() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();
        testServer.writeExecutionReportStatus(1, false);

        testServer.writeSequence(1, NotLapsed);

        testServer.readTerminate();
        serverAcceptsTerminate();
    }

    @Test
    public void shouldTerminateALowSequenceNumberBusinessMessage() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();
        testServer.writeExecutionReportStatus(1, false);

        testServer.writeExecutionReportStatus(1, false);

        testServer.readTerminate();
        serverAcceptsTerminate();
    }

    @Test
    public void shouldTerminateALowSequenceNumberEstablishWithSameUuid() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();
        testServer.writeExecutionReportStatus(1, false);
        agreeRecvSeqNo(2);
        terminateAndDisconnect();
        final long lastUuid = connection.uuid();
        connectToTestServer(connectionConfiguration().reEstablishLastConnection(true));
        testServer.expectedUuid(lastUuid);
        readEstablish(1);

        testServer.writeEstablishmentAck(1, lastUuid, 1);

        testServer.readTerminate();
        serverAcceptsTerminate();
    }

    @Test
    public void shouldAcceptALowSequenceNumberEstablishWithNewUuid() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();
        testServer.writeExecutionReportStatus(1, false);
        agreeRecvSeqNo(2);
        terminateAndDisconnect();

        establishNewConnection();
    }

    @Test
    public void shouldTerminateOnATimeout() throws IOException
    {
        launch(false);

        establishNewConnection();

        startTerminate();

        testServer.readTerminate();
        testSystem.awaitUnbind(connection);
        assertDisconnected();
    }

    @Test
    public void shouldDisconnectSessionsForClosedLibrary() throws IOException
    {
        launch(true);
        establishNewConnection();

        library.close();
        awaitLibraryDisconnect(engine);

        testServer.assertDisconnected();
    }

    @Test
    public void shouldDisconnectSessionsForTimedOutLibrary() throws IOException
    {
        // Use large keep alive timeout to ensure that it doesn't accidentally trigger the unbind
        testKeepAliveIntervalInMs = ILink3ConnectionConfiguration.KEEP_ALIVE_INTERVAL_MAX_VALUE;

        launch(true);
        establishNewConnection();

        awaitLibraryDisconnect(engine);
        testServer.assertDisconnected();

        // Library reconnects and receives a control notification
        testSystem.awaitUnbind(connection);
        assertArtioShowsSessionDisconnected();
    }

    @Test
    public void shouldContinueSequenceWithBackup() throws IOException
    {
        shouldEstablishConnectionAtBeginningOfWeek();
        sendNewOrderSingle();
        testServer.readNewOrderSingle(1);
        testServer.writeExecutionReportStatus(1, false);
        terminateAndDisconnect();
        // nextSent=2,nextRecv=2

        final long lastUuid = connection.uuid();

        connectToTestServer(connectionConfiguration()
            .reEstablishLastConnection(true)
            .useBackupHost(true));

        testServer.expectedUuid(lastUuid);

        readEstablish(2);
        testServer.writeEstablishmentAck(1, lastUuid, 2);

        acquireSession();
        assertRecvSeqNo(2);
        assertEquals(connection.nextSentSeqNo(), 2);

        sendNewOrderSingle();
        testServer.readNewOrderSingle(2);
        testServer.writeExecutionReportStatus(2, false);

        agreeRecvSeqNo(3);
        agreeRetransmitFillSeqNo(NOT_AWAITING_RETRANSMIT);

        terminateAndDisconnect();
    }

    @Test
    public void shouldNotAllowDuplicateConnections() throws IOException
    {
        // The same session can be used to connect to different market segments but not
        // the same external host simultaneously.
        shouldEstablishConnectionAtBeginningOfWeek();

        final Reply<ILink3Connection> reply = library.initiate(connectionConfiguration().build());
        testSystem.awaitReply(reply);
        assertEquals(Reply.State.ERRORED, reply.state());

        final String message = reply.error().getMessage();
        assertThat(message, containsString("Duplicate iLink3 Connection"));
    }

    private void establishNewConnection() throws IOException
    {
        connectToTestServer(connectionConfiguration());

        establishConnection();
    }

    private void establishConnection()
    {
        readNegotiate();
        testServer.writeNegotiateResponse();

        readEstablish();
        testServer.writeEstablishmentAck(0, 0, 1);

        acquireSession();

        assertEquals(connection.state(), ILink3Connection.State.ESTABLISHED);
        assertEquals(testServer.uuid(), connection.uuid());
    }

    private void acquireSession()
    {
        testSystem.awaitCompletedReplies(reply);
        connection = reply.resultIfPresent();
        assertNotNull(connection);
    }

    private void writeExecutionReports(final int fromSeqNo, final int msgCount)
    {
        final int lastSeqNo = fromSeqNo + msgCount;
        for (int i = fromSeqNo; i < lastSeqNo; i++)
        {
            testServer.writeExecutionReportStatus(i, true);
        }
    }

    private void agreeRecvSeqNo(final long nextRecvSeqNo)
    {
        agreeEquals(connection::nextRecvSeqNo, nextRecvSeqNo);
    }

    private void agreeRetransmitFillSeqNo(final long retransmitFillSeqNo)
    {
        agreeEquals(connection::retransmitFillSeqNo, retransmitFillSeqNo);
    }

    private void agreeEquals(final LongSupplier supplier, final long value)
    {
        assertEventuallyTrue(
            () -> "Fails to agree value " + value + " currently: " + supplier.getAsLong(),
            () ->
            {
                testSystem.poll();
                return supplier.getAsLong() == value;
            },
            Timing.DEFAULT_TIMEOUT_IN_MS,
            () -> {});
    }

    private void sleepHalfInterval()
    {
        testSystem.awaitBlocking(() -> sleep(TEST_KEEP_ALIVE_INTERVAL_IN_MS / 2));
    }

    private void sleep(final int timeInMs)
    {
        try
        {
            Thread.sleep(timeInMs);
        }
        catch (final InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    private void connectToTestServer(
        final ILink3ConnectionConfiguration.Builder connectionConfiguration)
        throws IOException
    {
        final ILink3ConnectionConfiguration config = connectionConfiguration.build();
        testServer = new ILink3TestServer(
            config, () -> reply = library.initiate(config), testSystem);
    }

    private void assertConnectError(final Matcher<String> messageMatcher)
    {
        testSystem.awaitReply(reply);
        assertEquals(Reply.State.ERRORED, reply.state());
        assertThat(reply.error().getMessage(), messageMatcher);
    }

    private void readEstablish()
    {
        readEstablish(1L);
    }

    private void readEstablish(final long expectedNextSeqNo)
    {
        testServer.readEstablish(ACCESS_KEY_ID, FIRM_ID, SESSION_ID, testKeepAliveIntervalInMs, expectedNextSeqNo);
    }

    private void readNegotiate()
    {
        testServer.readNegotiate(ACCESS_KEY_ID, FIRM_ID);
    }

    private void startTerminate()
    {
        connection.terminate("shutdown", 0);
    }

    private ILink3ConnectionConfiguration.Builder connectionConfiguration()
    {
        try
        {
            return ILink3ConnectionConfiguration.builder()
                .host("127.0.0.1")
                // NB: only some systems (but not debian/ubuntu) this may generate the same ip address as the primary.
                .backupHost(InetAddress.getLocalHost().getHostName())
                .port(port)
                .sessionId(SESSION_ID)
                .firmId(FIRM_ID)
                .userKey(USER_KEY)
                .accessKeyId(ACCESS_KEY_ID)
                .requestedKeepAliveIntervalInMs(testKeepAliveIntervalInMs)
                .handler(handler);
        }
        catch (final UnknownHostException e)
        {
            LangUtil.rethrowUnchecked(e);
            return null;
        }
    }

    private void assertDisconnected()
    {
        testServer.assertDisconnected();
        assertArtioShowsSessionDisconnected();
    }

    private void assertArtioShowsSessionDisconnected()
    {
        assertThat(library.iLink3Sessions(), hasSize(0));
        assertThat(engine.allSessions(), hasSize(0));
    }

    private void sendNewOrderSingle()
    {
        final NewOrderSingle514Encoder newOrderSingle = new NewOrderSingle514Encoder();
        assertThat(connection.tryClaim(newOrderSingle), greaterThan(0L));
        newOrderSingle
            .partyDetailsListReqID(1)
            .orderQty(1)
            .senderID(FIRM_ID)
            .side(SideReq.Buy)
            .clOrdID(CL_ORD_ID)
            .partyDetailsListReqID(1)
            .orderRequestID(1);

        connection.commit();
    }

    private void terminateAndDisconnect()
    {
        startTerminate();

        testServer.readTerminate();
        serverAcceptsTerminate();
    }

    private void serverAcceptsTerminate()
    {
        testServer.writeTerminate();
        testSystem.awaitUnbind(connection);
        assertDisconnected();
    }
}
