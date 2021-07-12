/*
 * Copyright 2015-2021 Real Logic Limited, Adaptive Financial Consulting Ltd., Monotonic Ltd.
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

import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import org.agrona.LangUtil;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.status.CountersReader;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.builder.ExampleMessageEncoder;
import uk.co.real_logic.artio.builder.ExecutionReportEncoder;
import uk.co.real_logic.artio.builder.ResendRequestEncoder;
import uk.co.real_logic.artio.builder.UserRequestEncoder;
import uk.co.real_logic.artio.engine.*;
import uk.co.real_logic.artio.engine.framer.LibraryInfo;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.messages.ReplayMessagesStatus;
import uk.co.real_logic.artio.messages.SessionReplyStatus;
import uk.co.real_logic.artio.messages.SessionState;
import uk.co.real_logic.artio.session.Session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.Constants.*;
import static uk.co.real_logic.artio.FixCounters.FixCountersId.INVALID_LIBRARY_ATTEMPTS_TYPE_ID;
import static uk.co.real_logic.artio.FixCounters.lookupCounterIds;
import static uk.co.real_logic.artio.FixMatchers.*;
import static uk.co.real_logic.artio.TestFixtures.*;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.Timing.withTimeout;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.library.FixLibrary.CURRENT_SEQUENCE;
import static uk.co.real_logic.artio.library.FixLibrary.NO_MESSAGE_REPLAY;
import static uk.co.real_logic.artio.messages.MessageStatus.CATCHUP_REPLAY;
import static uk.co.real_logic.artio.messages.SessionReplyStatus.OK;
import static uk.co.real_logic.artio.messages.SessionReplyStatus.SEQUENCE_NUMBER_TOO_HIGH;
import static uk.co.real_logic.artio.messages.SessionState.DISABLED;
import static uk.co.real_logic.artio.system_tests.FixMessage.hasMessageSequenceNumber;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.PASSWORD;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class GatewayToGatewaySystemTest extends AbstractGatewayToGatewaySystemTest
{
    private static final String NEW_PASSWORD = "ABCDEF";

    private CapturingAuthenticationStrategy auth;
    private final MessageTimingHandler messageTimingHandler = mock(MessageTimingHandler.class);

    @Before
    public void launch()
    {
        final MediaDriver.Context context = mediaDriverContext(TERM_BUFFER_LENGTH, true);
        context.publicationLingerTimeoutNs(SECONDS.toNanos(1));
        mediaDriver = launchMediaDriver(context);

//        logger = FixMessageLogger.start();

        final EngineConfiguration acceptingConfig = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock)
            .deleteLogFileDirOnStart(true);
        auth = new CapturingAuthenticationStrategy(acceptingConfig.messageValidationStrategy());
        acceptingConfig.authenticationStrategy(auth);
        acceptingConfig.monitoringAgentFactory(MonitoringAgentFactory.none());
        acceptingConfig.messageTimingHandler(messageTimingHandler);
        acceptingEngine = FixEngine.launch(acceptingConfig);

        initiatingEngine = launchInitiatingEngine(libraryAeronPort, nanoClock);

        final LibraryConfiguration acceptingLibraryConfig = acceptingLibraryConfig(acceptingHandler, nanoClock);
        acceptingLibrary = connect(acceptingLibraryConfig);
        initiatingLibrary = newInitiatingLibrary(libraryAeronPort, initiatingHandler, nanoClock);
        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);

        connectSessions();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void messagesCanBeSentFromInitiatorToAcceptor()
    {
        assertLastLogonEquals(1, 0);
        assertSequenceResetTimeAtLatestLogon(initiatingSession);

        messagesCanBeExchanged();

        assertInitiatingSequenceIndexIs(0);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void messagesCanBeSentFromInitiatorToAcceptingLibrary()
    {
        acquireAcceptingSession();
        assertLastLogonEquals(1, 0);
        assertSequenceResetTimeAtLatestLogon(acceptingSession);

        messagesCanBeExchanged();

        assertSequenceIndicesAre(0);

        final long connectionId = acceptingSession.connectionId();
        final ArgumentCaptor<Long> sequenceNumberCaptor = ArgumentCaptor.forClass(long.class);
        verify(messageTimingHandler, times(2))
            .onMessage(sequenceNumberCaptor.capture(), eq(connectionId));
        assertEquals(asList(1L, 2L), sequenceNumberCaptor.getAllValues());
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldProcessResendRequests()
    {
        final String testReqID = "AAA";

        gatewayProcessesResendRequests(testReqID);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldEnsureThatSequenceNumberAfterResendRequest()
    {
        final String testReqID = "AAA";
        acquireAcceptingSession();

        final FixMessage message1 = exchangeExampleMessageFromInitiatorToAcceptor(testReqID);
        clearMessages();
        final FixMessage message2 = exchangeExampleMessageFromInitiatorToAcceptor(testReqID);
        final int lastSequenceNumber = message2.messageSequenceNumber();

        final int sequenceNumber = acceptorSendsResendRequest(message1.messageSequenceNumber());

        final FixMessage resentMessage = assertMessageResent(sequenceNumber, EXAMPLE_MESSAGE_MESSAGE_AS_STR, false);
        assertEquals(testReqID, resentMessage.testReqId());
        acceptingOtfAcceptor.messages().clear();

        assertSequenceIndicesAre(0);

        final FixMessage nextMessage = exchangeExampleMessageFromInitiatorToAcceptor(testReqID);
        assertEquals(lastSequenceNumber + 1, nextMessage.messageSequenceNumber());
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldProcessDuplicateResendRequests()
    {
        final String testReqID = "AAA";

        acquireAcceptingSession();

        exchangeExampleMessageFromInitiatorToAcceptor(testReqID);
        assertTestRequestSentAndReceived(initiatingSession, testSystem, acceptingOtfAcceptor);

        acceptorSendsResendRequest(1, 3);
        acceptorSendsResendRequest(1, 3);

        assertThat(acceptingOtfAcceptor.messages(), hasSize(0));
        assertEventuallyTrue("Failed to receive the reply",
            () ->
            {
                testSystem.poll();

                assertEquals(2, acceptingOtfAcceptor
                    .receivedMessage(EXAMPLE_MESSAGE_MESSAGE_AS_STR)
                    .filter(msg -> "Y".equals(msg.possDup()))
                    .filter(msg -> 2 == msg.messageSequenceNumber())
                    .filter(msg -> testReqID.equals(msg.testReqId()))
                    .count());

                assertNull("Detected Error", acceptingOtfAcceptor.lastError());
                assertTrue("Failed to complete parsing", acceptingOtfAcceptor.isCompleted());
            });

        assertSequenceIndicesAre(0);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void gatewayProcessesResendRequestsOfFragmentedMessages()
    {
        final String testReqID = largeTestReqId();

        gatewayProcessesResendRequests(testReqID);
    }

    private void gatewayProcessesResendRequests(final String testReqID)
    {
        acquireAcceptingSession();

        final FixMessage message = exchangeExampleMessageFromInitiatorToAcceptor(testReqID);

        final int sequenceNumber = acceptorSendsResendRequest(message.messageSequenceNumber());

        final FixMessage resentMessage = assertMessageResent(sequenceNumber, EXAMPLE_MESSAGE_MESSAGE_AS_STR, false);
        assertEquals(testReqID, resentMessage.testReqId());
        acceptingOtfAcceptor.messages().clear();

        assertSequenceIndicesAre(0);

        final FixMessage nextMessage = exchangeExampleMessageFromInitiatorToAcceptor(testReqID);
        assertEquals(sequenceNumber + 1, nextMessage.messageSequenceNumber());
    }

    private FixMessage exchangeExampleMessageFromInitiatorToAcceptor(final String testReqID)
    {
        return exchangeExampleMessage(testReqID, initiatingSession, acceptingOtfAcceptor);
    }

    private FixMessage exchangeExampleMessageFromAcceptorToInitiator(final String testReqID)
    {
        return exchangeExampleMessage(testReqID, acceptingSession, initiatingOtfAcceptor);
    }

    private FixMessage exchangeExampleMessage(
        final String testReqID, final Session fromSession, final FakeOtfAcceptor toAcceptor)
    {
        sendExampleMessage(testReqID, fromSession);

        return testSystem.awaitMessageOf(
            toAcceptor, EXAMPLE_MESSAGE_MESSAGE_AS_STR, msg -> msg.testReqId().equals(testReqID));
    }

    private void sendExampleMessage(final String testReqID, final Session fromSession)
    {
        final ExampleMessageEncoder exampleMessage = new ExampleMessageEncoder();
        exampleMessage.testReqID(testReqID);
        final long position = fromSession.trySend(exampleMessage);
        assertThat(position, greaterThan(0L));
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void gatewayProcessesResendRequestsOfAdminMessages()
    {
        acquireAcceptingSession();

        messagesCanBeSentFromInitiatorToAcceptor();

        final int sequenceNumber = acceptorSendsResendRequest();

        assertMessageResent(sequenceNumber, SEQUENCE_RESET_MESSAGE_AS_STR, true);

        assertSequenceIndicesAre(0);
    }

    // Test exists to replicate a faily complex bug involving a sequence number issue after a library timeout.
    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldNotSendDuplicateSequenceNumbersAfterTimeout()
    {
        acquireAcceptingSession();

        // Timeout the library from the engine's perspective.
        testSystem.remove(acceptingLibrary);
        awaitLibraryDisconnect(acceptingEngine, testSystem);

        // Test that Trigger the invalid l
        sendExampleMessage("FAIL", acceptingSession);

        // Wait for the library to detect the timeout internally.
        testSystem.add(acceptingLibrary);
        testSystem.await("Library failed to detect timeout", acceptingHandler::hasTimedOut);

        assertThat(acceptingLibrary.sessions(), hasSize(0));
        assertEquals(DISABLED, acceptingSession.state());

        final Reply<SessionReplyStatus> reply = acceptingLibrary.requestSession(
            acceptingSession.id(), NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY, 5_000);

        assertThrows(
            "Failed to block the sending of a message after timing out",
            IllegalStateException.class,
            () -> sendExampleMessage("FAIL", acceptingSession));

        testSystem.awaitReply(reply);
        assertEquals(reply.toString(), Reply.State.COMPLETED, reply.state());
        assertSame(acceptingSession, acceptingHandler.lastSession());

        exchangeExampleMessageFromAcceptorToInitiator("4");

        // Check that we didn't receive any duplicate sequence numbers
        final List<Integer> receivedSequenceNumbers = initiatingOtfAcceptor.messageSequenceNumbers();
        final IntHashSet uniqueReceivedNumbers = new IntHashSet();
        uniqueReceivedNumbers.addAll(receivedSequenceNumbers);
        assertEquals(uniqueReceivedNumbers + " vs " + receivedSequenceNumbers,
            uniqueReceivedNumbers.size(), receivedSequenceNumbers.size());

        // Ensure that the session is still active.
        assertConnected(initiatingSession);
        assertConnected(acceptingSession);

        assertInvalidLibraryAttempts(acceptingSession.connectionId());
    }

    private void assertInvalidLibraryAttempts(final long connectionId)
    {
        final String connectionIdStr = String.valueOf(connectionId);
        try (Aeron aeron = Aeron.connect(acceptingEngine.configuration().aeronContextClone()))
        {
            final CountersReader countersReader = aeron.countersReader();
            final IntHashSet ids = lookupCounterIds(INVALID_LIBRARY_ATTEMPTS_TYPE_ID, countersReader,
                label -> label.contains(connectionIdStr));
            final IntHashSet.IntIterator counterIdIt = ids.iterator();
            assertTrue(counterIdIt.hasNext());
            assertThat(countersReader.getCounterValue(counterIdIt.nextValue()), greaterThan(0L));
        }
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void messagesCanBeSentFromAcceptorToInitiator()
    {
        acquireAcceptingSession();

        messagesCanBeExchanged(acceptingSession, acceptingOtfAcceptor);

        assertSequenceIndicesAre(0);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void initiatorSessionCanBeDisconnected()
    {
        acquireAcceptingSession();

        logoutSession(initiatingSession);

        assertSessionsDisconnected();

        assertSequenceIndicesAre(0);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void acceptorSessionCanBeDisconnected()
    {
        acquireAcceptingSession();

        logoutAcceptingSession();
        assertSessionsDisconnected();

        assertSequenceIndicesAre(0);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void sessionsCanReconnect()
    {
        super.sessionsCanReconnect();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void sessionsListedInAdminApi()
    {
        final List<LibraryInfo> libraries = libraries(initiatingEngine);
        assertThat(libraries, hasSize(2));

        final LibraryInfo library = libraries.get(0);
        assertEquals(initiatingLibrary.libraryId(), library.libraryId());

        final List<ConnectedSessionInfo> sessions = library.sessions();
        assertThat(sessions, hasSize(1));

        final ConnectedSessionInfo connectedSessionInfo = sessions.get(0);
        assertThat(connectedSessionInfo.address(), containsString("localhost"));
        assertThat(connectedSessionInfo.address(), containsString(String.valueOf(port)));
        assertEquals(initiatingSession.connectionId(), connectedSessionInfo.connectionId());

        assertEquals(initiatingSession.connectedPort(), port);
        assertEquals(initiatingSession.connectedHost(), "localhost");

        final LibraryInfo gatewayLibraryInfo = libraries.get(1);
        assertEquals(ENGINE_LIBRARY_ID, gatewayLibraryInfo.libraryId());
        assertThat(gatewayLibraryInfo.sessions(), hasSize(0));

        assertAllSessionsOnlyContains(initiatingEngine, initiatingSession);
    }

    private void assertAllSessionsOnlyContains(final FixEngine engine, final Session session)
    {
        final List<SessionInfo> allSessions = engine.allSessions();
        assertThat(allSessions, hasSize(1));

        final SessionInfo sessionInfo = allSessions.get(0);
        assertThat(sessionInfo.sessionId(), is(session.id()));
        assertThat(sessionInfo.sessionKey(), is(session.compositeKey()));
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void multipleLibrariesCanExchangeMessages()
    {
        final int initiator1MessageCount = initiatingOtfAcceptor.messages().size();

        final FakeOtfAcceptor initiatingOtfAcceptor2 = new FakeOtfAcceptor();
        final FakeHandler initiatingSessionHandler2 = new FakeHandler(initiatingOtfAcceptor2);
        try (FixLibrary library2 = testSystem.add(newInitiatingLibrary(
            libraryAeronPort, initiatingSessionHandler2, nanoClock)))
        {
            acceptingHandler.clearSessionExistsInfos();
            final Reply<Session> reply = testSystem.awaitReply(initiate(library2, port, INITIATOR_ID2, ACCEPTOR_ID));

            final Session session2 = reply.resultIfPresent();

            assertConnected(session2);

            final long sessionId = acceptingHandler.awaitSessionIdFor(
                INITIATOR_ID2,
                ACCEPTOR_ID,
                testSystem::poll,
                1000);

            final Session acceptingSession2 = acquireSession(acceptingHandler, acceptingLibrary, sessionId, testSystem);

            assertTestRequestSentAndReceived(acceptingSession2, testSystem, initiatingOtfAcceptor2);

            assertThat(session2, hasSequenceIndex(0));

            assertOriginalLibraryDoesNotReceiveMessages(initiator1MessageCount);
        }

        assertInitiatingSequenceIndexIs(0);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void sequenceNumbersShouldResetOverDisconnects()
    {
        acquireAcceptingSession();

        messagesCanBeExchanged();
        assertSequenceFromInitToAcceptAt(2, 2);

        logoutSession(initiatingSession);

        assertSequenceIndicesAre(0);
        assertSessionsDisconnected();
        clearMessages();

        wireSessions();
        assertSequenceResetTimeAtLatestLogon(initiatingSession);
        assertSequenceResetTimeAtLatestLogon(acceptingSession);

        assertSequenceFromInitToAcceptAt(1, 1);

        assertTestRequestSentAndReceived(initiatingSession, testSystem, acceptingOtfAcceptor);

        assertSequenceIndicesAre(1);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void acceptorsShouldHandleInitiatorDisconnectsGracefully()
    {
        acquireAcceptingSession();

        assertFalse("Premature Acceptor Disconnect", acceptingHandler.hasDisconnected());

        initiatingEngine.close();

        assertEventuallyTrue("Acceptor Disconnected",
            () ->
            {
                testSystem.poll();
                return acceptingHandler.hasDisconnected();
            });

        assertSequenceIndicesAre(0);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void librariesShouldBeAbleToReleaseInitiatedSessionToEngine()
    {
        acquireAcceptingSession();

        releaseSessionToEngineAndCheckCache(initiatingSession, initiatingLibrary, initiatingEngine, initiatingHandler);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void librariesShouldBeAbleToReleaseAcceptedSessionToEngine()
    {
        acquireAcceptingSession();

        releaseSessionToEngineAndCheckCache(acceptingSession, acceptingLibrary, acceptingEngine, acceptingHandler);
    }

    private void releaseSessionToEngineAndCheckCache(
        final Session session, final FixLibrary library, final FixEngine engine, final FakeHandler handler)
    {
        releaseSessionToEngine(session, library, engine);
        handler.resetSession();

        assertCountersClosed(true, session);

        final long sessionId = session.id();
        assertEquals(OK, requestSession(library, sessionId, testSystem));

        final Session reAcquiredSession = handler.lastSession();
        assertSame(session, reAcquiredSession);
        assertCountersClosed(false, reAcquiredSession);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void librariesShouldBeAbleToAcquireReleasedInitiatedSessions()
    {
        acquireAcceptingSession();

        final long sessionId = initiatingSession.id();

        releaseToEngine(initiatingLibrary, initiatingSession, testSystem);

        libraryNotifiedThatGatewayOwnsSession(initiatingHandler, sessionId);

        reacquireSession(
            initiatingSession, initiatingLibrary, initiatingEngine,
            sessionId, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY, OK);

        assertSequenceIndicesAre(0);
        assertSequenceResetTimeAtLatestLogon(initiatingSession);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void librariesShouldBeAbleToAcquireReleasedAcceptedSessions()
    {
        acquireAcceptingSession();

        final long sessionId = acceptingSession.id();
        acceptingHandler.clearSessionExistsInfos();

        releaseToEngine(acceptingLibrary, acceptingSession, testSystem);

        libraryNotifiedThatGatewayOwnsSession(acceptingHandler, sessionId);

        reacquireSession(
            acceptingSession, acceptingLibrary, acceptingEngine,
            sessionId, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY, OK);

        assertSequenceIndicesAre(0);
        assertSequenceResetTimeAtLatestLogon(acceptingSession);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldReceiveCatchupReplayAfterReconnect()
    {
        shouldReceiveCatchupReplay(() -> acceptingSession.sequenceIndex(), OK);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldReceiveCatchupReplayForSequenceNumberTooHigh()
    {
        shouldReceiveCatchupReplay(() -> 100, SEQUENCE_NUMBER_TOO_HIGH);
    }

    private void shouldReceiveCatchupReplay(
        final IntSupplier sequenceIndexSupplier,
        final SessionReplyStatus expectedStatus)
    {
        acquireAcceptingSession();

        final int lastReceivedMsgSeqNum = acceptingSession.lastReceivedMsgSeqNum();
        final String testReqID = largeTestReqId();
        exchangeExampleMessageFromInitiatorToAcceptor(testReqID);

        disconnectSessions();

        final long sessionId = acceptingSession.id();
        final int sequenceIndex = sequenceIndexSupplier.getAsInt();

        assertSequenceIndicesAre(0);
        clearMessages();

        connectSessions();

        reacquireSession(
            acceptingSession, acceptingLibrary, acceptingEngine,
            sessionId, lastReceivedMsgSeqNum, sequenceIndex,
            expectedStatus);

        assertSequenceResetTimeAtLatestLogon(initiatingSession);

        if (expectedStatus == OK)
        {
            final FixMessage replayedExampleMessage = acceptingOtfAcceptor.messages().get(1);
            assertEquals(Constants.EXAMPLE_MESSAGE_MESSAGE_AS_STR, replayedExampleMessage.msgType());
            assertThat(replayedExampleMessage, hasMessageSequenceNumber(2));
            assertEquals(0, replayedExampleMessage.sequenceIndex());
            assertEquals(testReqID, replayedExampleMessage.testReqId());
            assertEquals(CATCHUP_REPLAY, replayedExampleMessage.status());
        }

        acceptingSession = acceptingHandler.lastSession();
        acceptingHandler.resetSession();
        assertSequenceResetTimeAtLatestLogon(acceptingSession);

        // Send messages both ways to ensure that the session is setup
        messagesCanBeExchanged(acceptingSession, acceptingOtfAcceptor);
        messagesCanBeExchanged(initiatingSession, initiatingOtfAcceptor);

        assertAcceptingSessionHasSequenceIndex(1);
        assertInitiatingSequenceIndexIs(1);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void enginesShouldManageAcceptingSession()
    {
        acquireAcceptingSession();

        engineShouldManageSession(
            acceptingSession, acceptingLibrary, acceptingOtfAcceptor,
            initiatingSession, initiatingOtfAcceptor);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void enginesShouldManageInitiatingSession()
    {
        acquireAcceptingSession();

        engineShouldManageSession(
            initiatingSession, initiatingLibrary, initiatingOtfAcceptor,
            acceptingSession, acceptingOtfAcceptor);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void librariesShouldNotBeAbleToAcquireSessionsThatDontExist()
    {
        final SessionReplyStatus status = requestSession(
            initiatingLibrary, 42, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY, testSystem);

        assertEquals(SessionReplyStatus.UNKNOWN_SESSION, status);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void librariesShouldBeNotifiedOfGatewayManagedSessionsOnConnect()
    {
        try (LibraryDriver library2 = LibraryDriver.accepting(testSystem, nanoClock))
        {
            assertEquals(1, library2.awaitSessionId());
        }
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void engineAndLibraryPairsShouldBeRestartable()
    {
        messagesCanBeExchanged();

        testSystem.close(acceptingLibrary);
        acceptingEngine.close();
        assertSequenceIndicesAre(0);

        testSystem.awaitMessageOf(initiatingOtfAcceptor, LOGOUT_MESSAGE_AS_STR);

        clearMessages();

        launchAcceptingEngine();
        acceptingLibrary = testSystem.add(newAcceptingLibrary(acceptingHandler, nanoClock));

        wireSessions();
        messagesCanBeExchanged();

        assertSequenceIndicesAre(1);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void enginesShouldBeRestartable()
    {
        messagesCanBeExchanged();

        closeAcceptingEngine();

        assertAllMessagesHaveSequenceIndex(0);

        testSystem.close(acceptingLibrary);
        acceptingHandler.clearSessionExistsInfos();

        initiatingEngineHasLibraryConnected();

        clearMessages();

        launchAcceptingEngine();

        acceptingLibrary = testSystem.connect(acceptingLibraryConfig(acceptingHandler, nanoClock));

        initiatingEngineHasLibraryConnected();

        assertTrue("acceptingLibrary has failed to connect", acceptingLibrary.isConnected());
        assertTrue("initiatingLibrary is no longer connected", initiatingLibrary.isConnected());

        wireSessions();

        messagesCanBeExchanged();

        assertSequenceIndicesAre(1);
    }

    private void initiatingEngineHasLibraryConnected()
    {
        final Reply<List<LibraryInfo>> libraries = initiatingEngine.libraries();
        testSystem.awaitCompletedReplies(libraries);
        final List<LibraryInfo> libraryInfos = libraries.resultIfPresent();
        assertThat(libraryInfos, hasSize(2));
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void engineShouldAcquireTimedOutAcceptingSessions()
    {
        acquireAcceptingSession();

        testSystem.remove(acceptingLibrary);

        acceptingEngineHasSessionAndLibraryIsNotified();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void engineShouldAcquireTimedOutInitiatingSessions()
    {
        testSystem.remove(initiatingLibrary);

        initiatingEngineHasSessionAndLibraryIsNotified();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void engineShouldAcquireAcceptingSessionsFromClosedLibrary()
    {
        acquireAcceptingSession();
        acceptingLibrary.close();

        assertEquals(DISABLED, acceptingSession.state());

        acceptingEngineHasSessionAndLibraryIsNotified();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void engineShouldAcquireInitiatingSessionsFromClosedLibrary()
    {
        initiatingLibrary.close();

        assertEquals(DISABLED, initiatingSession.state());

        initiatingEngineHasSessionAndLibraryIsNotified();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void libraryShouldSeeReleasedAcceptingSession()
    {
        acquireAcceptingSession();

        releaseSessionToEngine(acceptingSession, acceptingLibrary, acceptingEngine);

        try (LibraryDriver library2 = LibraryDriver.accepting(testSystem, nanoClock))
        {
            final SessionExistsInfo sessionId = library2.awaitCompleteSessionId();
            assertSameSession(sessionId, acceptingSession);
        }
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void libraryShouldSeeReleasedInitiatingSession()
    {
        releaseSessionToEngine(initiatingSession, initiatingLibrary, initiatingEngine);

        try (LibraryDriver library2 = LibraryDriver.initiating(libraryAeronPort, testSystem, nanoClock))
        {
            final SessionExistsInfo sessionId = library2.awaitCompleteSessionId();
            assertSameSession(sessionId, initiatingSession);

            try (LibraryDriver library3 = LibraryDriver.initiating(libraryAeronPort, testSystem, nanoClock))
            {
                final SessionExistsInfo sessionId3 = library3.awaitCompleteSessionId();
                assertSameSession(sessionId3, initiatingSession);

                try (LibraryDriver library4 = LibraryDriver.initiating(libraryAeronPort, testSystem, nanoClock))
                {
                    final SessionExistsInfo sessionId4 = library4.awaitCompleteSessionId();
                    assertSameSession(sessionId4, initiatingSession);
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldReconnectToBouncedGatewayWithoutTimeout()
    {
        acquireAcceptingSession();

        messagesCanBeExchanged();

        assertTrue("Session not active", acceptingSession.isActive());

        closeAcceptingEngine();

        launchAcceptingEngine();

        // Hasn't initially detected the library that was previously connected.
        assertThat(SystemTestUtil.libraries(acceptingEngine), hasSize(1));

        assertEventuallyTrue(
            "Session never disconnects",
            () ->
            {
                testSystem.poll();
                return !acceptingSession.isActive();
            });

        SystemTestUtil.assertEventuallyHasLibraries(
            testSystem,
            acceptingEngine,
            matchesLibrary(acceptingLibrary.libraryId()),
            matchesLibrary(ENGINE_LIBRARY_ID));

        connectSessions();

        messagesCanBeExchanged();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldExchangeLargeMessages()
    {
        acquireAcceptingSession();

        final String testReqID = largeTestReqId();

        sendTestRequest(acceptingSession, testReqID);

        assertReceivedSingleHeartbeat(testSystem, acceptingOtfAcceptor, testReqID);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldLookupSessionIdsOfSessions()
    {
        final long sessionId = lookupSessionId(INITIATOR_ID, ACCEPTOR_ID, initiatingEngine).resultIfPresent();

        assertEquals(initiatingSession.id(), sessionId);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldNotLookupSessionIdsOfUnknownSessions()
    {
        final Reply<Long> sessionIdReply = lookupSessionId("foo", "bar", initiatingEngine);

        assertNull(sessionIdReply.resultIfPresent());
        assertThat(sessionIdReply.error(), instanceOf(IllegalArgumentException.class));
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldResetSequenceNumbersOfEngineManagedSessions()
    {
        messagesCanBeExchanged();

        resetSequenceNumbersViaEngineApi();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldResetSequenceNumbersOfLibraryManagedSessions()
    {
        messagesCanBeExchanged();

        acquireAcceptingSession();

        testSystem.awaitReceivedSequenceNumber(acceptingSession, 2);

        assertAccSeqNum(2, 2, 0);

        final TimeRange timeRange = new TimeRange(acceptingEngine.configuration().epochNanoClock());
        resetSequenceNumbersViaEngineApi();
        testSystem.awaitReceivedSequenceNumber(acceptingSession, 1);
        timeRange.end();

        assertAccSeqNum(1, 1, 1);
        timeRange.assertWithinRange(acceptingSession.lastSequenceResetTimeInNs());
    }

    private TimeRange resetSequenceNumbersViaEngineApi()
    {
        assertInitSeqNum(2, 2, 0);

        final long sessionId = lookupSessionId(ACCEPTOR_ID, INITIATOR_ID, acceptingEngine).resultIfPresent();

        final TimeRange timeRange = new TimeRange(initiatingEngine.configuration().epochNanoClock());
        final Reply<?> resetSequenceNumber = resetSequenceNumber(sessionId);
        replyCompleted(resetSequenceNumber);
        timeRange.end();

        assertInitSeqNum(1, 1, 1);
        timeRange.assertWithinRange(initiatingSession.lastSequenceResetTimeInNs());

        return timeRange;
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldNotResetSequenceNumbersOfMissingSession()
    {
        messagesCanBeExchanged();

        assertInitSeqNum(2, 2, 0);

        final Reply<?> resetSequenceNumber = resetSequenceNumber(400);
        assertTrue("Should have errored: " + resetSequenceNumber, resetSequenceNumber.hasErrored());
        final String message = resetSequenceNumber.error().getMessage();
        assertTrue(message, message.contains("Unknown sessionId: 400"));

        assertInitSeqNum(2, 2, 0);
    }

    private void replyCompleted(final Reply<?> resetSequenceNumber)
    {
        assertTrue("Should be complete: " + resetSequenceNumber, resetSequenceNumber.hasCompleted());
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldCombineGapFilledReplays()
    {
        messagesCanBeExchanged();

        messagesCanBeExchanged();

        final ResendRequestEncoder resendRequest = new ResendRequestEncoder();
        resendRequest.beginSeqNo(1).endSeqNo(0);

        initiatingOtfAcceptor.messages().clear();

        testSystem.send(initiatingSession, resendRequest);

        final FixMessage message = testSystem.awaitMessageOf(initiatingOtfAcceptor, SEQUENCE_RESET_MESSAGE_AS_STR);

        // Logon + two heartbeats gets to 3, next is 4.
        assertEquals("4", message.get(Constants.NEW_SEQ_NO));

        clearMessages();

        messagesCanBeExchanged();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldCleanupAeronResourcesUponDisconnectDuringResend() throws IOException
    {
        // Test reproduces a race within the cleanup of the ReplayOperation
        messagesCanBeExchanged();

        messagesCanBeExchanged();

        final ResendRequestEncoder resendRequest = new ResendRequestEncoder();
        resendRequest.beginSeqNo(1).endSeqNo(0);

        initiatingOtfAcceptor.messages().clear();

        testSystem.send(initiatingSession, resendRequest);

        sleep(1);

        testSystem.awaitSend("Failed to disconnect", () -> initiatingSession.requestDisconnect());

        sleep(1_000);

        testSystem.await("Failed to cleanup resources", () -> remainingFileCount() == 31);
    }

    private long remainingFileCount()
    {
        try
        {
            return Files.walk(mediaDriver.mediaDriver().context().aeronDirectory().toPath())
                .count();
        }
        catch (final UncheckedIOException e)
        {
            // retry if the file-system changes under us
            return remainingFileCount();
        }
        catch (final IOException e)
        {
            LangUtil.rethrowUnchecked(e);
            return 0;
        }
    }

    private void sleep(final int timeInMs)
    {
        testSystem.awaitBlocking(() ->
        {
            try
            {
                Thread.sleep(timeInMs);
            }
            catch (final InterruptedException e)
            {
                e.printStackTrace();
            }
        });
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldReplayAMixOfEngineAndLibraryMessages()
    {
        // Engine messages
        messagesCanBeExchanged();
        messagesCanBeExchanged();

        // Library messages
        acquireAcceptingSession();
        exchangeExecutionReport();

        final ResendRequestEncoder resendRequest = new ResendRequestEncoder();
        resendRequest.beginSeqNo(1).endSeqNo(0);

        initiatingOtfAcceptor.messages().clear();

        testSystem.send(initiatingSession, resendRequest);

        final FixMessage gapFill = testSystem.awaitMessageOf(initiatingOtfAcceptor, SEQUENCE_RESET_MESSAGE_AS_STR);
        assertEquals(1, gapFill.messageSequenceNumber());
        assertEquals(4, Integer.parseInt(gapFill.get(NEW_SEQ_NO)));
        assertTrue(gapFill.isValid());

        final FixMessage execReport = testSystem.awaitMessageOf(initiatingOtfAcceptor, EXECUTION_REPORT_MESSAGE_AS_STR);
        assertEquals(4, execReport.messageSequenceNumber());
        assertTrue(execReport.isValid());

        clearMessages();

        messagesCanBeExchanged();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldReplayCurrentMessages()
    {
        final long sessionId = acceptingHandler.awaitSessionId(testSystem::poll);

        acceptingSession = acquireSession(
            acceptingHandler,
            acceptingLibrary,
            sessionId,
            testSystem,
            CURRENT_SEQUENCE,
            CURRENT_SEQUENCE);

        assertEquals(INITIATOR_ID, acceptingHandler.lastInitiatorCompId());
        assertEquals(ACCEPTOR_ID, acceptingHandler.lastAcceptorCompId());
        assertNotNull("unable to acquire accepting session", acceptingSession);

        final List<FixMessage> replayedMessages = acceptingOtfAcceptor.messages();
        assertThat(replayedMessages, hasSize(1));
        assertEquals(1, replayedMessages.get(0).messageSequenceNumber());

        messagesCanBeExchanged();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldWipePasswordsFromLogs()
    {
        assertArchiveDoesNotContainPassword();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldHandleUserRequestMessages()
    {
        final String id = "A";
        final UserRequestEncoder userRequestEncoder
            = new UserRequestEncoder()
            .userRequestID(id)
            .userRequestType(UserRequestType.ChangePasswordForUser)
            .username(SystemTestUtil.USERNAME)
            .password(PASSWORD)
            .newPassword(NEW_PASSWORD);

        while (initiatingSession.trySend(userRequestEncoder) < 0)
        {
            testSystem.poll();

            Thread.yield();
        }

        while (!auth.receivedUserRequest())
        {
            testSystem.poll();

            Thread.yield();
        }

        assertEquals(PASSWORD, auth.logonPassword());
        assertEquals(PASSWORD, auth.userRequestPassword());
        assertEquals(NEW_PASSWORD, auth.userRequestNewPassword());
        assertEquals(1, auth.sessionId());

        assertArchiveDoesNotContainPassword();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldReplayReceivedMessagesForSession()
    {
        acquireAcceptingSession();
        messagesCanBeExchanged();

        clearMessages();

        assertReplayReceivedMessages();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldNotifyOfMissingMessagesForReplayReceivedMessages()
    {
        acquireAcceptingSession();

        clearMessages();

        final Reply<ReplayMessagesStatus> reply = acceptingSession.replayReceivedMessages(
            1, 100, 2, 100, 5_000L);
        testSystem.awaitCompletedReplies(reply);
        assertThat(acceptingOtfAcceptor.messages(), hasSize(0));
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldBeAbleToLookupOfflineSession()
    {
        acquireAcceptingSession();
        messagesCanBeExchanged();
        clearMessages();

        logoutAcceptingSession();
        assertSessionsDisconnected();

        final long sessionId = acceptingSession.id();
        final long lastSequenceResetTime = acceptingSession.lastSequenceResetTimeInNs();
        final long lastLogonTime = acceptingSession.lastLogonTimeInNs();
        acceptingSession = null;

        assertNotEquals(Session.UNKNOWN_TIME, lastSequenceResetTime);
        assertNotEquals(Session.UNKNOWN_TIME, lastLogonTime);

        acquireAcceptingSession();

        assertOfflineSession(sessionId, acceptingSession);
        assertEquals(lastSequenceResetTime, acceptingSession.lastSequenceResetTimeInNs());
        assertEquals(lastLogonTime, acceptingSession.lastLogonTimeInNs());

        assertAllSessionsOnlyContains(acceptingEngine, acceptingSession);

        assertReplayReceivedMessages();

        connectSessions();

        assertEventuallyTrue("offline session is reconnected", () ->
        {
            testSystem.poll();

            return acceptingSession.state() == SessionState.ACTIVE;
        }, 3_000L);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void engineShouldNotAcquireTimedOutOfflineSessions()
    {
        logoutInitiatingSession();
        assertSessionDisconnected(initiatingSession);

        acquireAcceptingSession();
        assertEquals(SessionState.DISCONNECTED, acceptingSession.state());

        testSystem.remove(acceptingLibrary);

        final List<LibraryInfo> libraries = withTimeout("Library failed to timeout", () ->
            Optional.of(libraries(acceptingEngine, testSystem)).filter(infos -> infos.size() == 1), 5_000);
        assertThat(libraries.get(0).sessions(), hasSize(0));
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldNotAcquireOrInitiateOfflineSessionOwnedByAnotherLibrary()
    {
        final long sessionId = initiatingSession.id();

        logoutInitiatingSession();
        assertSessionDisconnected(initiatingSession);
        acceptingHandler.clearSessionExistsInfos();

        try (FixLibrary initiatingLibrary2 = testSystem.connect(
            initiatingLibraryConfig(libraryAeronPort, initiatingHandler, nanoClock)))
        {
            assertTrue(initiatingLibrary.isConnected());

            final SessionReplyStatus reply = requestSession(initiatingLibrary2, sessionId, testSystem);
            assertEquals(OK, reply);
            assertFalse(acceptingHandler.hasSeenSession());
            final Session offlineInitiatingSession = initiatingHandler.lastSession();
            assertNotSame(initiatingSession, offlineInitiatingSession);
            assertOfflineSession(sessionId, offlineInitiatingSession);

            final SessionReplyStatus failedStatus = requestSession(initiatingLibrary, sessionId, testSystem);
            assertEquals(SessionReplyStatus.OTHER_SESSION_OWNER, failedStatus);

            final Reply<Session> failedReply = initiate(initiatingLibrary, port, INITIATOR_ID, ACCEPTOR_ID);
            completeFailedSession(failedReply);
            assertThat(failedReply.error().getMessage(), containsString("DUPLICATE"));
        }
    }

    private void assertReplayReceivedMessages()
    {
        final Reply<ReplayMessagesStatus> reply = acceptingSession.replayReceivedMessages(
            1, 0, 2, 0, 5_000L);
        testSystem.awaitCompletedReplies(reply);

        final FixMessage testRequest = acceptingOtfAcceptor
            .receivedMessage(TEST_REQUEST_MESSAGE_AS_STR)
            .findFirst()
            .get();
        assertEquals(CATCHUP_REPLAY, testRequest.status());
    }

    private void assertArchiveDoesNotContainPassword()
    {
        final EngineConfiguration configuration = acceptingEngine.configuration();

        final List<String> messages = getMessagesFromArchive(
            configuration, configuration.inboundLibraryStream());
        assertThat(messages, hasSize(greaterThanOrEqualTo(1)));
        for (final String message : messages)
        {
            assertThat(message + " contains the password",
                message,
                allOf(not(containsString(PASSWORD)), not(containsString(NEW_PASSWORD))));
        }
    }

    private void exchangeExecutionReport()
    {
        final ExecutionReportEncoder executionReport = new ExecutionReportEncoder();
        executionReport
            .orderID("order")
            .execID("exec")
            .execType(ExecType.FILL)
            .ordStatus(OrdStatus.FILLED)
            .side(Side.BUY);
        executionReport.instrument().symbol("IBM");
        assertThat(acceptingSession.trySend(executionReport), greaterThan(0L));

        testSystem.awaitMessageOf(initiatingOtfAcceptor, EXECUTION_REPORT_MESSAGE_AS_STR);
    }

    private void reacquireSession(
        final Session session,
        final FixLibrary library,
        final FixEngine engine,
        final long sessionId,
        final int lastReceivedMsgSeqNum,
        final int sequenceIndex,
        final SessionReplyStatus expectedStatus)
    {
        final SessionReplyStatus status = requestSession(
            library, sessionId, lastReceivedMsgSeqNum, sequenceIndex, testSystem);
        assertEquals(expectedStatus, status);

        assertThat(gatewayLibraryInfo(engine).sessions(), hasSize(0));

        engineIsManagingSession(engine, session.id());

        assertEventuallyTrue(
            "library manages session",
            () ->
            {
                testSystem.poll();
                assertContainsOnlySession(session, library);
            });
    }

    private void assertContainsOnlySession(final Session session, final FixLibrary library)
    {
        final List<Session> sessions = library.sessions();
        assertThat(sessions, hasSize(1));

        final Session newSession = sessions.get(0);
        assertTrue(newSession.isConnected());
        assertEquals(session.id(), newSession.id());
    }

    private void engineIsManagingSession(final FixEngine engine, final long sessionId)
    {
        final List<LibraryInfo> libraries = libraries(engine);
        assertThat(libraries.get(0).sessions(), contains(hasSessionId(sessionId)));
    }

    private void libraryNotifiedThatGatewayOwnsSession(final FakeHandler handler, final long expectedSessionId)
    {
        final long sessionId = handler.awaitSessionId(() -> testSystem.poll());

        assertEquals(sessionId, expectedSessionId);
    }

    private Reply<Long> lookupSessionId(final String localCompId, final String remoteCompId, final FixEngine engine)
    {
        return testSystem.awaitReply(engine.lookupSessionId(
            localCompId, remoteCompId, null, null, null, null));
    }
}
