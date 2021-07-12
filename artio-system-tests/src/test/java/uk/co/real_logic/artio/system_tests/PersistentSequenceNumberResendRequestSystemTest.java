/*
 * Copyright 2019 Adaptive Financial Consulting Ltd., Monotonic Ltd.
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
package uk.co.real_logic.artio.system_tests;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.TestFixtures;
import uk.co.real_logic.artio.decoder.NewOrderSingleDecoder;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static org.junit.Assert.assertEquals;
import static uk.co.real_logic.artio.Constants.EXECUTION_REPORT_MESSAGE_AS_STR;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.library.SessionConfiguration.AUTOMATIC_INITIAL_SEQUENCE_NUMBER;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;
import static uk.co.real_logic.artio.validation.SessionPersistenceStrategy.alwaysPersistent;

public class PersistentSequenceNumberResendRequestSystemTest extends AbstractGatewayToGatewaySystemTest
{
    private static final boolean PRINT_ERROR_MESSAGES = true;

    {
        acceptingHandler = new FakeHandler(acceptingOtfAcceptor)
        {
            private final NewOrderSingleDecoder newOrderSingle = new NewOrderSingleDecoder();
            private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer();
            private final ReportFactory reportFactory = new ReportFactory();

            public Action onMessage(
                final DirectBuffer buffer,
                final int offset,
                final int length,
                final int libraryId,
                final Session session,
                final int sequenceIndex,
                final long messageType,
                final long timestampInNs,
                final long position,
                final OnMessageInfo messageInfo)
            {
                asciiBuffer.wrap(buffer, offset, length);

                if (messageType == NewOrderSingleDecoder.MESSAGE_TYPE)
                {
                    newOrderSingle.decode(asciiBuffer, 0, length);

                    final Action action = reportFactory.trySendReportAct(session, newOrderSingle.sideAsEnum());
                    if (action == ABORT)
                    {
                        return action;
                    }
                }

                return super.onMessage(
                    buffer, offset, length, libraryId, session, sequenceIndex, messageType, timestampInNs, position,
                    messageInfo);
            }
        };
    }

    @Before
    public void setUp()
    {
        deleteLogs();
        mediaDriver = TestFixtures.launchMediaDriver();
        launch(AUTOMATIC_INITIAL_SEQUENCE_NUMBER);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldReplayMessageBeforeARestart()
    {
        final int resendSeqNum = exchangeMessages();

        assertInitiatingSequenceIndexIs(0);
        initiatingSession.startLogout();
        assertSessionsDisconnected();

        close();

        clearMessages();
        launchMediaDriverWithDirs();

        // 4. login with low received sequence number in order to force a resend request from the server.
        launch(1);

        // 5. validate resent message
        final FixMessage resentExecutionReport =
            testSystem.awaitMessageOf(initiatingOtfAcceptor, EXECUTION_REPORT_MESSAGE_AS_STR);
        assertEquals(resendSeqNum, resentExecutionReport.messageSequenceNumber());
        assertEquals("Y", resentExecutionReport.possDup());

        sendResendRequest(1, 3, initiatingOtfAcceptor, initiatingSession);
        sendResendRequest(1, 3, initiatingOtfAcceptor, initiatingSession);

        assertEventuallyTrue(() -> "Failed to receive all the resends: " + initiatingOtfAcceptor.messages(),
            () ->
            {
                testSystem.poll();

                assertEquals(2, initiatingOtfAcceptor
                    .receivedReplayGapFill(1, 2)
                    .count());

                assertEquals(2, initiatingOtfAcceptor
                    .receivedReplay(EXECUTION_REPORT_MESSAGE_AS_STR, resendSeqNum)
                    .count());

                assertEquals(2, initiatingOtfAcceptor
                    .receivedReplayGapFill(3, 4)
                    .count());
            }, 5000);
    }

    private int exchangeMessages()
    {
        OrderFactory.sendOrder(initiatingSession);

        final FixMessage executionReport =
            testSystem.awaitMessageOf(initiatingOtfAcceptor, EXECUTION_REPORT_MESSAGE_AS_STR);
        return executionReport.messageSequenceNumber();
    }

    private void launch(final int initiatorInitialReceivedSequenceNumber)
    {
        final EngineConfiguration acceptingConfig = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock);
        acceptingConfig.sessionPersistenceStrategy(alwaysPersistent());
        acceptingConfig.printStartupWarnings(PRINT_ERROR_MESSAGES);
        acceptingEngine = FixEngine.launch(acceptingConfig);

        final EngineConfiguration initiatingConfig = initiatingConfig(libraryAeronPort, nanoClock);
        initiatingConfig.printStartupWarnings(PRINT_ERROR_MESSAGES);
        initiatingEngine = FixEngine.launch(initiatingConfig);

        final LibraryConfiguration acceptingLibraryConfig = acceptingLibraryConfig(acceptingHandler, nanoClock);
        testSystem = new TestSystem();
        acceptingLibrary = testSystem.connect(acceptingLibraryConfig);

        final LibraryConfiguration initiatingLibraryConfig =
            initiatingLibraryConfig(libraryAeronPort, initiatingHandler, nanoClock);
        initiatingLibrary = testSystem.connect(initiatingLibraryConfig);

        final Reply<Session> reply = connectPersistentSessions(
            AUTOMATIC_INITIAL_SEQUENCE_NUMBER, initiatorInitialReceivedSequenceNumber, false);
        assertEquals("Reply failed: " + reply, Reply.State.COMPLETED, reply.state());
        initiatingSession = reply.resultIfPresent();
        acquireAcceptingSession();
    }
}
