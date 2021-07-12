/*
 * Copyright 2015-2020 Real Logic Limited, Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.library;

import io.aeron.exceptions.TimeoutException;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.sbe.MessageEncoderFlyweight;
import uk.co.real_logic.artio.fixp.AbstractFixPProxy;
import uk.co.real_logic.artio.fixp.FixPConnection;
import uk.co.real_logic.artio.fixp.FixPConnectionHandler;
import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import static uk.co.real_logic.artio.fixp.FixPConnection.State.*;
import static uk.co.real_logic.artio.messages.DisconnectReason.APPLICATION_DISCONNECT;
import static uk.co.real_logic.artio.messages.DisconnectReason.LOGOUT;

public abstract class InternalFixPConnection implements FixPConnection
{
    protected final GatewayPublication outboundPublication;
    protected final GatewayPublication inboundPublication;
    protected final int libraryId;
    protected final EpochNanoClock clock;
    protected final FixPSessionOwner owner;
    protected final AbstractFixPProxy proxy;

    protected State state;
    protected FixPConnectionHandler handler;
    protected LibraryReply<InternalFixPConnection> initiateReply;
    protected long connectionId;

    protected long requestedKeepAliveIntervalInMs;
    protected long nextSentSeqNo;
    protected long nextRecvSeqNo;

    protected long retransmitFillTimeoutInMs = NOT_AWAITING_RETRANSMIT;
    protected long nextReceiveMessageTimeInMs;
    protected long nextSendMessageTimeInMs;

    protected InternalFixPConnection(
        final long connectionId,
        final GatewayPublication outboundPublication,
        final GatewayPublication inboundPublication,
        final int libraryId,
        final EpochNanoClock clock,
        final FixPSessionOwner owner,
        final AbstractFixPProxy proxy)
    {
        this.connectionId = connectionId;
        this.outboundPublication = outboundPublication;
        this.inboundPublication = inboundPublication;
        this.libraryId = libraryId;
        this.clock = clock;
        this.owner = owner;
        this.proxy = proxy;
    }

    // -----------------------------------------------
    // Public API
    // -----------------------------------------------

    public long connectionId()
    {
        return connectionId;
    }

    public long nextSentSeqNo()
    {
        return nextSentSeqNo;
    }

    public void nextSentSeqNo(final long nextSentSeqNo)
    {
        this.nextSentSeqNo = nextSentSeqNo;
    }

    public long nextRecvSeqNo()
    {
        return nextRecvSeqNo;
    }

    public void nextRecvSeqNo(final long nextRecvSeqNo)
    {
        this.nextRecvSeqNo = nextRecvSeqNo;
    }

    public long requestDisconnect(final DisconnectReason reason)
    {
        return outboundPublication.saveRequestDisconnect(libraryId, connectionId, reason);
    }

    public boolean canSendMessage()
    {
        final State state = this.state;
        return state == ESTABLISHED || state == AWAITING_KEEPALIVE || state == RECV_FINISHED_SENDING ||
            state == UNBOUND;
    }

    public State state()
    {
        return state;
    }

    public long tryClaim(
        final MessageEncoderFlyweight message)
    {
        return tryClaim(message, 0);
    }

    public long tryClaim(
        final MessageEncoderFlyweight message, final int variableLength)
    {
        validateCanSend();

        final long timestamp = requestTimestampInNs();

        final long position = proxy.claimMessage(
            message.sbeBlockLength() + variableLength, message, timestamp);

        if (position > 0)
        {
            nextSentSeqNo++;
        }

        return position;
    }

    public void commit()
    {
        proxy.commit();

        onAttemptedToSendMessage();
    }

    public void abort()
    {
        proxy.abort();

        nextSentSeqNo--;
    }

    // -----------------------------------------------
    // Internal Methods below, not part of the public API
    // -----------------------------------------------

    protected void onReceivedMessage()
    {
        if (state == AWAITING_KEEPALIVE)
        {
            state(ESTABLISHED);
        }

        nextReceiveMessageTimeInMs = nextTimeoutInMs();
    }

    protected long nextTimeoutInMs()
    {
        return System.currentTimeMillis() + requestedKeepAliveIntervalInMs;
    }

    // This just suppresses sending sequence heartbeating messages
    protected void onAttemptedToSendMessage()
    {
        nextSendMessageTimeInMs = nextTimeoutInMs();
    }

    protected long requestTimestampInNs()
    {
        return clock.nanoTime();
    }

    protected void validateCanSend()
    {
        if (!canSendMessage())
        {
            throw new IllegalStateException(
                "State should be ESTABLISHED or AWAITING_KEEPALIVE or RECV_FINISHED_SENDING in order to send but is " +
                state);
        }
    }

    @SuppressWarnings("unchecked")
    protected void initiateReply(final LibraryReply<?> initiateReply)
    {
        this.initiateReply = (LibraryReply<InternalFixPConnection>)initiateReply;
    }

    protected void onNegotiateFailure()
    {
        onReplyError(new TimeoutException("Timed out: no reply for Negotiate"));
    }

    protected void onEstablishFailure()
    {
        onReplyError(new TimeoutException("Timed out: no reply for Establish"));
    }

    protected void onReplyError(final Exception error)
    {
        initiateReply.onError(error);
        initiateReply = null;
    }

    protected int commonPoll(final State state, final long timeInMs)
    {
        switch (state)
        {
            case ESTABLISHED:
                return pollEstablished(timeInMs);

            case AWAITING_KEEPALIVE:
                return pollAwaitingKeepAlive(timeInMs);

            case UNBINDING:
                return pollUnbinding(timeInMs);

            default:
                return 0;
        }
    }

    protected int pollAwaitingKeepAlive(final long timeInMs)
    {
        if (timeInMs > nextReceiveMessageTimeInMs)
        {
            keepAliveExpiredTerminate();
            return 1;
        }

        return 0;
    }

    // Should switch into unbinding or resend terminate state after this.
    protected abstract void keepAliveExpiredTerminate();

    protected int pollUnbinding(final long timeInMs)
    {
        if (timeInMs > nextSendMessageTimeInMs)
        {
            fullyUnbind();
        }
        return 0;
    }

    protected int pollEstablished(final long timeInMs)
    {
        int events = pollExtraEstablished(timeInMs);

        if (timeInMs > nextReceiveMessageTimeInMs)
        {
            sendSequence(true);

            onReceivedMessage();

            state(AWAITING_KEEPALIVE);
            events++;
        }
        else if (timeInMs > nextSendMessageTimeInMs)
        {
            sendSequence(false);
            events++;
        }

        return events;
    }

    protected abstract int pollExtraEstablished(long timeInMs);

    protected abstract long sendSequence(boolean lapsed);

    protected void fullyUnbind()
    {
        fullyUnbind(LOGOUT);
    }

    protected void fullyUnbind(final DisconnectReason reason)
    {
        requestDisconnect(reason);
        owner.remove(this);
        unbindState(APPLICATION_DISCONNECT);
    }

    protected void unbindState(final DisconnectReason reason)
    {
        state(State.UNBOUND);
        handler.onDisconnect(this, reason);

        // Complete the reply if we're in the process of trying to establish a connection and we haven't provided
        // a more specific reason for a disconnect to happen.
        if (initiateReply != null)
        {
            onReplyError(new Exception("Unbound due to: " + reason));
        }
    }

    protected void state(final State state)
    {
        this.state = state;
    }

    public void handler(final FixPConnectionHandler handler)
    {
        this.handler = handler;
    }

    protected abstract int poll(long timeInMs);

    protected abstract void onReplayComplete();

    protected abstract void onOfflineReconnect(long connectionId, FixPContext context);

}
