/*
 * Copyright 2015-2021 Real Logic Limited.
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
package uk.co.real_logic.artio.session;

import org.junit.Test;
import uk.co.real_logic.artio.messages.CancelOnDisconnectOption;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_SESSION_BUFFER_SIZE;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_MS;
import static uk.co.real_logic.artio.library.SessionConfiguration.DEFAULT_ENABLE_LAST_MSG_SEQ_NUM_PROCESSED;
import static uk.co.real_logic.artio.messages.SessionState.*;
import static uk.co.real_logic.artio.session.DirectSessionProxy.NO_LAST_MSG_SEQ_NUM_PROCESSED;
import static uk.co.real_logic.artio.session.Session.ACTIVE_VALUE;

public class AcceptorSessionTest extends AbstractSessionTest
{
    private final AcceptorSession session = newAcceptorSession();

    private AcceptorSession newAcceptorSession()
    {
        final AcceptorSession acceptorSession = new AcceptorSession(
            HEARTBEAT_INTERVAL_IN_S,
            CONNECTION_ID,
            nanoClock,
            sessionProxy,
            mock(GatewayPublication.class),
            mockPublication,
            idStrategy,
            SENDING_TIME_WINDOW,
            mockReceivedMsgSeqNo,
            mockSentMsgSeqNo,
            LIBRARY_ID,
            1,
            SEQUENCE_INDEX,
            CONNECTED,
            DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_MS,
            new MutableAsciiBuffer(new byte[DEFAULT_SESSION_BUFFER_SIZE]),
            DEFAULT_ENABLE_LAST_MSG_SEQ_NUM_PROCESSED,
            SessionCustomisationStrategy.none(),
            messageInfo,
            fakeEpochFractionClock);
        acceptorSession.fixDictionary(makeDictionary());
        acceptorSession.sessionProcessHandler(mockLogonListener);
        return acceptorSession;
    }

    @Test
    public void shouldHaveConstantsInSyncWithMessageSchema()
    {
        assertEquals(ACTIVE.value(), ACTIVE_VALUE);
        assertEquals(LOGGING_OUT.value(), Session.LOGGING_OUT_VALUE);
        assertEquals(LOGGING_OUT_AND_DISCONNECTING.value(), Session.LOGGING_OUT_AND_DISCONNECTING_VALUE);
        assertEquals(AWAITING_LOGOUT.value(), Session.AWAITING_LOGOUT_VALUE);
        assertEquals(DISCONNECTING.value(), Session.DISCONNECTING_VALUE);
    }

    @Test
    public void shouldInitiallyBeConnected()
    {
        assertEquals(CONNECTED, session.state());
    }

    @Test
    public void shouldBeActivatedBySuccessfulLogin()
    {
        onLogon(1);

        verifyLogon();
        verify(sessionProxy).seqNumResetRequested();
        verifyNoFurtherMessages();
        assertState(ACTIVE);
    }

    @Test
    public void shouldRequestResendIfHighSeqNoLogon()
    {
        onLogon(3);

        verifyLogon();
        verify(sessionProxy).sendResendRequest(2, 1, 0, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        verify(sessionProxy).seqNumResetRequested();
        verifyNoFurtherMessages();
    }

    @Test
    public void shouldNotRequestResendIfHighSeqNoLogonAndResetRequested()
    {
        when(sessionProxy.seqNumResetRequested()).thenReturn(true);

        onLogon(3);

        verifyLogon();
        verify(sessionProxy).seqNumResetRequested();
        verifyNoFurtherMessages();
        assertState(ACTIVE); // nothing to await as we requested seq no reset
    }

    @Test
    public void shouldLogoutIfFirstMessageNotALogon()
    {
        onMessage(1);

        verifyDisconnect(times(1));
        verifyNoFurtherMessages();
    }

    @Test
    public void shouldStartAcceptLogonBasedSequenceNumberResetWhenSequenceNumberIsOne()
    {
        shouldStartAcceptLogonBasedSequenceNumberResetWhenSequenceNumberIsOne(SEQUENCE_INDEX);
    }

    protected void readyForLogon()
    {
        // Deliberately blank
    }

    protected Session session()
    {
        return session;
    }

    private void verifyLogon()
    {
        verify(sessionProxy).sendLogon(
            1, HEARTBEAT_INTERVAL_IN_S, null, null, false, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED,
            CancelOnDisconnectOption.DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT, MISSING_INT);
    }

}
