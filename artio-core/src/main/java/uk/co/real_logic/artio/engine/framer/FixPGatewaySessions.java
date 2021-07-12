/*
 * Copyright 2021 Monotonic Ltd.
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
package uk.co.real_logic.artio.engine.framer;

import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.fixp.AbstractFixPProxy;
import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.fixp.FixPFirstMessageResponse;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.messages.InboundFixPConnectEncoder;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import uk.co.real_logic.artio.validation.FixPAuthenticationProxy;

public class FixPGatewaySessions extends GatewaySessions
{
    static final int ACCEPTED_HEADER_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH +
        InboundFixPConnectEncoder.BLOCK_LENGTH;

    private final EngineConfiguration engineConfiguration;
    private final FixPContexts fixPContexts;

    FixPGatewaySessions(
        final EpochClock epochClock,
        final GatewayPublication inboundPublication,
        final GatewayPublication outboundPublication,
        final ErrorHandler errorHandler,
        final SequenceNumberIndexReader sentSequenceNumberIndex,
        final SequenceNumberIndexReader receivedSequenceNumberIndex,
        final EngineConfiguration engineConfiguration,
        final FixPContexts fixPContexts)
    {
        super(
            epochClock,
            inboundPublication,
            outboundPublication,
            errorHandler,
            sentSequenceNumberIndex,
            receivedSequenceNumberIndex);
        this.engineConfiguration = engineConfiguration;
        this.fixPContexts = fixPContexts;
    }

    protected void setLastSequenceResetTime(final GatewaySession gatewaySession)
    {
    }

    public AcceptorLogonResult authenticate(
        final long sessionId,
        final MutableAsciiBuffer buffer,
        final int offset,
        final int messageSize,
        final FixPGatewaySession gatewaySession,
        final long connectionId,
        final TcpChannel channel,
        final Framer framer,
        final FixPProtocolType protocolType,
        final FixPContext identification,
        final AbstractFixPProxy fixPProxy)
    {
        return new FixPPendingAcceptorLogon(
            sessionId,
            buffer,
            offset,
            messageSize,
            gatewaySession,
            connectionId,
            channel,
            framer,
            protocolType,
            identification,
            fixPProxy);
    }

    public FixPContexts fixPContexts()
    {
        return fixPContexts;
    }

    class FixPPendingAcceptorLogon extends PendingAcceptorLogon implements FixPAuthenticationProxy
    {
        public static final int LINGER_TIMEOUT_IN_MS = 500;

        private final long sessionId;
        private final MutableAsciiBuffer buffer;
        private final int offset;
        private final int messageSize;
        private final FixPProtocolType protocolType;
        private final FixPContext identification;
        private final AbstractFixPProxy fixPProxy;

        private FixPFirstMessageResponse fixPFirstMessageResponse;

        FixPPendingAcceptorLogon(
            final long sessionId,
            final MutableAsciiBuffer buffer,
            final int offset,
            final int messageSize,
            final FixPGatewaySession gatewaySession,
            final long connectionId,
            final TcpChannel channel,
            final Framer framer,
            final FixPProtocolType protocolType,
            final FixPContext identification,
            final AbstractFixPProxy fixPProxy)
        {
            super(gatewaySession, connectionId, channel, framer);
            this.sessionId = sessionId;
            this.buffer = buffer;
            this.offset = offset;
            this.messageSize = messageSize;
            this.protocolType = protocolType;
            this.identification = identification;
            this.fixPProxy = fixPProxy;

            final FixPFirstMessageResponse rejectReason = fixPContexts.onAcceptorLogon(
                sessionId, identification, connectionId);
            if (rejectReason == FixPFirstMessageResponse.OK)
            {
                authenticate(connectionId);
            }
            else
            {
                reject(rejectReason);
            }
        }

        protected void authenticate(final long connectionId)
        {
            try
            {
                engineConfiguration.fixPAuthenticationStrategy().authenticate(identification, this);
            }
            catch (final Throwable throwable)
            {
                onStrategyError("authentication", throwable, connectionId, "false",
                    identification.toString());

                if (state != AuthenticationState.REJECTED)
                {
                    reject();
                }
            }
        }

        protected void onAuthenticated()
        {
            final FixPGatewaySession session = (FixPGatewaySession)this.session;
            session.authenticated();

            if (framer.onFixPLogonMessageReceived(session, sessionId))
            {
                state = AuthenticationState.ACCEPTED;
            }
            else
            {
                final MessageHeaderEncoder header = new MessageHeaderEncoder();
                final InboundFixPConnectEncoder inboundFixPConnect = new InboundFixPConnectEncoder();
                final UnsafeBuffer logonBuffer = new UnsafeBuffer(new byte[ACCEPTED_HEADER_LENGTH]);
                inboundFixPConnect
                    .wrapAndApplyHeader(logonBuffer, 0, header)
                    .connection(connectionId)
                    .sessionId(sessionId)
                    .protocolType(protocolType)
                    .messageLength(messageSize);

                final long position = inboundPublication.dataPublication().offer(
                    logonBuffer, 0, ACCEPTED_HEADER_LENGTH,
                    buffer, offset, messageSize);

                if (position < 0)
                {
                    System.out.println("position = " + position); // TODO
                }
                else
                {
                    state = AuthenticationState.ACCEPTED;
                }
            }
        }

        protected void encodeRejectMessage()
        {
            rejectEncodeBuffer = fixPProxy.encodeReject(identification, fixPFirstMessageResponse);
        }

        public void reject()
        {
            reject(FixPFirstMessageResponse.CREDENTIALS);
        }

        private void reject(final FixPFirstMessageResponse response)
        {
            this.reason = DisconnectReason.FAILED_AUTHENTICATION;
            this.fixPFirstMessageResponse = response;
            this.lingerTimeoutInMs = LINGER_TIMEOUT_IN_MS;
            this.state = AuthenticationState.SENDING_REJECT_MESSAGE;
        }
    }
}
