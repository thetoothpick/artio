/*
 * Copyright 2015-2021 Real Logic Limited, Adaptive Financial Consulting Ltd.
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

import io.aeron.archive.ArchivingMediaDriver;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.OffsetEpochNanoClock;
import org.junit.After;
import org.junit.Test;
import uk.co.real_logic.artio.Timing;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.LogonEncoder;
import uk.co.real_logic.artio.builder.TestRequestEncoder;
import uk.co.real_logic.artio.engine.ConnectedSessionInfo;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.LockStepFramerEngineScheduler;
import uk.co.real_logic.artio.engine.framer.LibraryInfo;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.messages.SessionReplyStatus;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;

import static org.agrona.CloseHelper.close;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.*;
import static uk.co.real_logic.artio.TestFixtures.*;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_SENDER_MAX_BYTES_IN_BUFFER;
import static uk.co.real_logic.artio.messages.SessionState.ACTIVE;
import static uk.co.real_logic.artio.system_tests.AbstractGatewayToGatewaySystemTest.TEST_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class SlowConsumerTest
{
    private static final int BUFFER_CAPACITY = 16 * 1024;

    private final EpochNanoClock nanoClock = new OffsetEpochNanoClock();
    private final int port = unusedPort();
    private ArchivingMediaDriver mediaDriver;
    private FixEngine engine;
    private FixLibrary library;
    private final FakeOtfAcceptor acceptingOtfAcceptor = new FakeOtfAcceptor();
    private final FakeHandler handler = new FakeHandler(acceptingOtfAcceptor);
    private TestSystem testSystem;

    private final TestRequestEncoder testRequest = newTestRequest();
    private final LogonEncoder logon = new LogonEncoder();
    private final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
    private final MutableAsciiBuffer buffer = new MutableAsciiBuffer(byteBuffer);
    private final LockStepFramerEngineScheduler scheduler = new LockStepFramerEngineScheduler();
    private SocketChannel socket;
    private Session session;

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldQuarantineThenDisconnectASlowConsumer() throws IOException
    {
        final int senderMaxBytesInBuffer = 8 * 1024;
        setup(senderMaxBytesInBuffer, null);

        initiateConnection();

        final long sessionId = handler.awaitSessionId(testSystem::poll);
        session = acquireSession(handler, library, sessionId, testSystem);
        final ConnectedSessionInfo sessionInfo = getSessionInfo();

        while (!socketIsConnected())
        {
            testSystem.poll();
        }

        assertNotSlow();

        boolean hasBecomeSlow = false;

        while (socketIsConnected())
        {
            if (session.isActive())
            {
                if (handler.isSlow(session))
                {
                    assertTrue(session.isSlowConsumer());
                    hasBecomeSlow = true;
                }

                session.trySend(testRequest);
            }

            testSystem.poll();
        }

        bytesInBufferAtLeast(sessionInfo, senderMaxBytesInBuffer);

        assertTrue(hasBecomeSlow);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldRestoreConnectionFromSlowGroupWhenItCatchesUp() throws IOException
    {
        final MessageTimingCaptor messageTimingCaptor = new MessageTimingCaptor();
        final ConnectedSessionInfo sessionInfo = sessionBecomesSlow(messageTimingCaptor);
        socket.configureBlocking(false);

        testSystem.poll();

        // Get out of slow state
        while (sessionInfo.bytesInBuffer() > 0 || handler.isSlow(session))
        {
            int bytesRead;
            do
            {
                byteBuffer.position(1).limit(BUFFER_CAPACITY);
                bytesRead = socket.read(byteBuffer);
            }
            while (bytesRead > 0);

            session.trySend(testRequest);

            testSystem.poll();
        }

        assertNotSlow();

        messageTimingCaptor.verifyConsecutiveSequenceNumbers(session.lastSentMsgSeqNum());

        assertEquals(ACTIVE, session.state());
        assertTrue(socketIsConnected());
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldNotifyLibraryOfSlowConnectionWhenAcquired() throws IOException
    {
        sessionBecomesSlow(null);

        assertEquals(SessionReplyStatus.OK, releaseToEngine(library, session, testSystem));

        session = acquireSession(handler, library, session.id(), testSystem);

        assertTrue("Session not slow", handler.lastSessionWasSlow());
    }

    private ConnectedSessionInfo sessionBecomesSlow(final MessageTimingCaptor messageTimingCaptor) throws IOException
    {
        setup(DEFAULT_SENDER_MAX_BYTES_IN_BUFFER, messageTimingCaptor);

        initiateConnection();

        final long sessionId = handler.awaitSessionId(testSystem::poll);
        session = acquireSession(handler, library, sessionId, testSystem);
        final ConnectedSessionInfo sessionInfo = getSessionInfo();

        assertNotSlow();

        // Get into a slow state
        while (sessionInfo.bytesInBuffer() == 0 || !handler.isSlow(session))
        {
            for (int i = 0; i < 10; i++)
            {
                session.trySend(testRequest);
            }

            testSystem.poll();
        }

        assertIsSlow();
        return sessionInfo;
    }

    private void assertIsSlow()
    {
        assertTrue(handler.isSlow(session));
        assertTrue(session.isSlowConsumer());
    }

    private void assertNotSlow()
    {
        assertFalse(handler.isSlow(session));
        assertFalse(session.isSlowConsumer());
    }

    private void bytesInBufferAtLeast(final ConnectedSessionInfo sessionInfo, final long bytesInBuffer)
    {
        Timing.assertEventuallyTrue("Buffer doesn't have enough bytes in", () ->
        {
            assertThat(sessionInfo.bytesInBuffer(), greaterThanOrEqualTo(bytesInBuffer));
            testSystem.poll();
        });
    }

    private TestRequestEncoder newTestRequest()
    {
        final TestRequestEncoder testRequest = new TestRequestEncoder();
        testRequest.testReqID("some relatively long test req id");
        return testRequest;
    }

    private ConnectedSessionInfo getSessionInfo()
    {
        final List<LibraryInfo> libraries = libraries(engine, testSystem);
        assertThat(libraries, hasSize(2));
        final LibraryInfo libraryInfo = libraries.get(0);
        final List<ConnectedSessionInfo> sessions = libraryInfo.sessions();
        assertThat(sessions, hasSize(1));
        return sessions.get(0);
    }

    private void initiateConnection() throws IOException
    {
        socket = SocketChannel.open(new InetSocketAddress("localhost", port));

        final UtcTimestampEncoder timestamp = new UtcTimestampEncoder();
        timestamp.encode(System.currentTimeMillis());
        logon
            .heartBtInt(10)
            .encryptMethod(0)
            .header()
            .sendingTime(timestamp.buffer())
            .msgSeqNum(1)
            .senderCompID(INITIATOR_ID)
            .targetCompID(ACCEPTOR_ID);

        final long result = logon.encode(buffer, 0);
        final int offset = Encoder.offset(result);
        final int length = Encoder.length(result);
        byteBuffer.position(offset);
        byteBuffer.limit(offset + length);
        assertEquals(length, socket.write(byteBuffer));
    }

    private boolean socketIsConnected()
    {
        // Need to poke to TCP connection to detect disconnection
        try
        {
            byteBuffer.position(0).limit(1);
            return socket.write(byteBuffer) >= 0;
        }
        catch (final IOException e)
        {
            return false;
        }
    }

    @After
    public void cleanup()
    {
        testSystem.awaitBlocking(() -> close(engine));
        close(library);
        cleanupMediaDriver(mediaDriver);
        close(socket);
    }

    private void setup(final int senderMaxBytesInBuffer, final MessageTimingCaptor messageTimingCaptor)
    {
        mediaDriver = launchMediaDriver(8 * 1024 * 1024);
        final EngineConfiguration config = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock)
            .scheduler(scheduler);
        config.deleteLogFileDirOnStart(true);
        config.senderMaxBytesInBuffer(senderMaxBytesInBuffer);
        config.messageTimingHandler(messageTimingCaptor);
        engine = FixEngine.launch(config);
        testSystem = new TestSystem(scheduler);
        final LibraryConfiguration libraryConfiguration = acceptingLibraryConfig(handler, nanoClock);
        libraryConfiguration.outboundMaxClaimAttempts(1);
        library = testSystem.connect(libraryConfiguration);
    }
}

