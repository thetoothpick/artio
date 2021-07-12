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
package uk.co.real_logic.artio.binary_entrypoint;

import b3.entrypoint.fixp.sbe.FinishedReceivingDecoder;
import b3.entrypoint.fixp.sbe.FinishedSendingDecoder;
import b3.entrypoint.fixp.sbe.NegotiateResponseDecoder;
import io.aeron.ExclusivePublication;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.engine.logger.FixPSequenceNumberHandler;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.fixp.*;
import uk.co.real_logic.artio.library.FixPSessionOwner;
import uk.co.real_logic.artio.library.InternalFixPConnection;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.BINARY_ENTRYPOINT_TYPE;

public class BinaryEntryPointProtocol extends FixPProtocol
{
    public static <T> T unsupported()
    {
        throw new UnsupportedOperationException("Binary Entrypoint is only implemented as an acceptor");
    }

    public BinaryEntryPointProtocol()
    {
        super(
            FixPProtocolType.BINARY_ENTRYPOINT,
            BINARY_ENTRYPOINT_TYPE,
            FinishedSendingDecoder.TEMPLATE_ID,
            FinishedReceivingDecoder.TEMPLATE_ID,
            NegotiateResponseDecoder.TEMPLATE_ID);
    }

    public BinaryEntryPointParser makeParser(final FixPConnection connection)
    {
        return new BinaryEntryPointParser((InternalBinaryEntrypointConnection)connection);
    }

    public BinaryEntryPointProxy makeProxy(
        final ExclusivePublication publication, final EpochNanoClock epochNanoClock)
    {
        return new BinaryEntryPointProxy(0, publication, epochNanoClock);
    }

    public BinaryEntryPointOffsets makeOffsets()
    {
        return new BinaryEntryPointOffsets();
    }

    public InternalFixPConnection makeAcceptorConnection(
        final long connectionId,
        final GatewayPublication outboundPublication,
        final GatewayPublication inboundPublication,
        final int libraryId,
        final FixPSessionOwner owner,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final long lastConnectPayload,
        final FixPContext context,
        final CommonConfiguration configuration)
    {
        return new InternalBinaryEntrypointConnection(
            connectionId,
            outboundPublication,
            inboundPublication,
            libraryId,
            owner,
            lastReceivedSequenceNumber,
            lastSentSequenceNumber,
            lastConnectPayload,
            configuration,
            (BinaryEntryPointContext)context);
    }

    public AbstractFixPStorage makeStorage(final EpochNanoClock clock)
    {
        return new BinaryEntryPointStorage();
    }

    public AbstractFixPSequenceExtractor makeSequenceExtractor(
        final FixPSequenceNumberHandler handler,
        final SequenceNumberIndexReader sequenceNumberReader)
    {
        return new BinaryEntryPointSequenceExtractor(
            handler,
            sequenceNumberReader);
    }
}
