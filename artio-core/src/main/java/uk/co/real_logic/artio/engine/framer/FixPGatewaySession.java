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

import uk.co.real_logic.artio.engine.FixPConnectedSessionInfo;
import uk.co.real_logic.artio.fixp.AbstractFixPParser;
import uk.co.real_logic.artio.fixp.AbstractFixPProxy;
import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.fixp.FixPKey;
import uk.co.real_logic.artio.messages.ConnectionType;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

public class FixPGatewaySession extends GatewaySession implements FixPConnectedSessionInfo
{
    private final FixPProtocolType protocolType;
    private final AbstractFixPParser parser;
    private final AbstractFixPProxy fixPProxy;
    private final AcceptorFixPReceiverEndPoint receiverEndPoint;
    private final FixPSenderEndPoint senderEndPoint;
    private final FixPGatewaySessions gatewaySessions;

    private byte[] firstMessage;
    private FixPContext context;

    FixPGatewaySession(
        final long connectionId,
        final long sessionId,
        final String address,
        final ConnectionType connectionType,
        final long authenticationTimeoutInMs,
        final FixPProtocolType protocolType,
        final AbstractFixPParser parser,
        final AbstractFixPProxy fixPProxy,
        final AcceptorFixPReceiverEndPoint receiverEndPoint,
        final FixPSenderEndPoint senderEndPoint,
        final FixPGatewaySessions gatewaySessions)
    {
        super(connectionId, sessionId, address, connectionType, authenticationTimeoutInMs, receiverEndPoint);
        this.protocolType = protocolType;
        this.parser = parser;
        this.fixPProxy = fixPProxy;
        this.receiverEndPoint = receiverEndPoint;
        this.senderEndPoint = senderEndPoint;
        this.gatewaySessions = gatewaySessions;
    }

    public String address()
    {
        return address;
    }

    int poll(final long timeInMs, final long timeInNs)
    {
        return checkNoLogonDisconnect(timeInMs);
    }

    long lastLogonTime()
    {
        return 0;
    }

    void acceptorSequenceNumbers(final int lastSentSequenceNumber, final int lastReceivedSequenceNumber)
    {
    }

    // Called with the first message sent in the FIXP stream
    public AcceptorLogonResult onLogon(
        final MutableAsciiBuffer buffer,
        final int offset,
        final int messageSize,
        final TcpChannel channel,
        final Framer framer)
    {
        firstMessage = new byte[messageSize];
        buffer.getBytes(offset, firstMessage, 0, messageSize);

        sessionId = parser.sessionId(buffer, offset);
        receiverEndPoint.sessionId(sessionId);
        context = parser.lookupContext(buffer, offset, messageSize);

        startAuthentication(System.currentTimeMillis()); // TODO: time

        return gatewaySessions.authenticate(
            sessionId, buffer, offset, messageSize, this, connectionId, channel, framer, protocolType,
            context, fixPProxy);
    }

    void setupOfflineSession(
        final FixPContext context,
        final byte[] firstMessage,
        final int libraryId)
    {
        this.context = context;
        this.firstMessage = firstMessage;
        libraryId(libraryId);
    }

    public FixPProtocolType protocolType()
    {
        return protocolType;
    }

    public byte[] firstMessage()
    {
        return firstMessage;
    }

    public void setManagementTo(final int libraryId)
    {
        libraryId(libraryId);
        receiverEndPoint.libraryId(libraryId);
        senderEndPoint.libraryId(libraryId);
    }

    public void authenticated()
    {
        receiverEndPoint.authenticated();
    }

    public void onDisconnectReleasedByOwner()
    {
    }

    public void onEndSequence()
    {
        context.onEndSequence();
        gatewaySessions.fixPContexts().updateContext(context);
    }

    public void close()
    {
    }

    public String toString()
    {
        return "FixPGatewaySession{" +
            "protocolType=" + protocolType +
            ", connectionType=" + connectionType +
            ", sessionId=" + sessionId +
            ", connectionId=" + connectionId +
            ", address='" + address + '\'' +
            ", libraryId=" + libraryId +
            '}';
    }

    public FixPKey key()
    {
        return context.key();
    }
}
