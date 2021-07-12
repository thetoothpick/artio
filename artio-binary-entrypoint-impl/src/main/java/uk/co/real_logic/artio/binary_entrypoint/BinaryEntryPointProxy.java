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

import b3.entrypoint.fixp.sbe.*;
import io.aeron.ExclusivePublication;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.sbe.MessageEncoderFlyweight;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.fixp.AbstractFixPProxy;
import uk.co.real_logic.artio.fixp.FixPFirstMessageResponse;
import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static uk.co.real_logic.artio.LogTag.FIXP_SESSION;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.BINARY_ENTRYPOINT_TYPE;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.SOFH_LENGTH;

public class BinaryEntryPointProxy extends AbstractFixPProxy
{
    public static final int BINARY_ENTRYPOINT_HEADER_LENGTH = SOFH_LENGTH + MessageHeaderEncoder.ENCODED_LENGTH;
    private static final int BINARY_ENTRYPOINT_MESSAGE_HEADER = ARTIO_HEADER_LENGTH + BINARY_ENTRYPOINT_HEADER_LENGTH;
    private static final int NEGOTIATE_REJECT_LENGTH = BINARY_ENTRYPOINT_HEADER_LENGTH +
        NegotiateRejectEncoder.BLOCK_LENGTH;
    private static final int ESTABLISH_REJECT_LENGTH = BINARY_ENTRYPOINT_HEADER_LENGTH +
        EstablishRejectEncoder.BLOCK_LENGTH;
    private static final int NEGOTIATE_LENGTH = BINARY_ENTRYPOINT_HEADER_LENGTH +
        NegotiateEncoder.BLOCK_LENGTH;
    private static final int ESTABLISH_LENGTH = BINARY_ENTRYPOINT_HEADER_LENGTH +
        EstablishEncoder.BLOCK_LENGTH;

    private final MessageHeaderEncoder beMessageHeader = new MessageHeaderEncoder();
    private final NegotiateEncoder negotiate = new NegotiateEncoder();
    private final NegotiateResponseEncoder negotiateResponse = new NegotiateResponseEncoder();
    private final NegotiateRejectEncoder negotiateReject = new NegotiateRejectEncoder();
    private final EstablishEncoder establish = new EstablishEncoder();
    private final EstablishAckEncoder establishAck = new EstablishAckEncoder();
    private final EstablishRejectEncoder establishReject = new EstablishRejectEncoder();
    private final SequenceEncoder sequence = new SequenceEncoder();
    private final TerminateEncoder terminate = new TerminateEncoder();
    private final FinishedReceivingEncoder finishedReceiving = new FinishedReceivingEncoder();
    private final FinishedSendingEncoder finishedSending = new FinishedSendingEncoder();
    private final NotAppliedEncoder notApplied = new NotAppliedEncoder();
    private final RetransmissionEncoder retransmission = new RetransmissionEncoder();
    private final RetransmitRejectEncoder retransmitReject = new RetransmitRejectEncoder();

    private final Consumer<StringBuilder> negotiateResponseAppendTo = negotiateResponse::appendTo;
    private final Consumer<StringBuilder> negotiateRejectAppendTo = negotiateReject::appendTo;
    private final Consumer<StringBuilder> establishAckAppendTo = establishAck::appendTo;
    private final Consumer<StringBuilder> establishRejectAppendTo = establishReject::appendTo;
    private final Consumer<StringBuilder> terminateAppendTo = terminate::appendTo;
    private final Consumer<StringBuilder> sequenceAppendTo = sequence::appendTo;
    private final Consumer<StringBuilder> finishedReceivingAppendTo = finishedReceiving::appendTo;
    private final Consumer<StringBuilder> finishedSendingAppendTo = finishedSending::appendTo;
    private final Consumer<StringBuilder> notAppliedAppendTo = notApplied::appendTo;
    private final Consumer<StringBuilder> retransmissionAppendTo = retransmission::appendTo;
    private final Consumer<StringBuilder> retransmitRejectAppendTo = retransmitReject::appendTo;

    private final UnsafeBuffer buffer = new UnsafeBuffer();
    private final EpochNanoClock clock;

    public BinaryEntryPointProxy(
        final long connectionId,
        final ExclusivePublication publication,
        final EpochNanoClock clock)
    {
        super(connectionId, publication);
        this.clock = clock;
    }

    public long sendSequence(final long sessionId, final long nextSentSeqNo)
    {
        final SequenceEncoder sequence = this.sequence;

        final long position = claimMessage(SequenceEncoder.BLOCK_LENGTH, sequence, clock.nanoTime());
        if (position < 0)
        {
            return position;
        }

        sequence
            .nextSeqNo(nextSentSeqNo);

        DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", sequenceAppendTo);

        commit();

        return position;
    }

    public long sendNegotiateResponse(
        final long sessionID, final long sessionVerID, final long requestTimestamp, final long enteringFirm)
    {
        final NegotiateResponseEncoder negotiateResponse = this.negotiateResponse;

        final long position = claimMessage(NegotiateResponseEncoder.BLOCK_LENGTH, negotiateResponse, requestTimestamp);
        if (position < 0)
        {
            return position;
        }

        negotiateResponse
            .sessionID(sessionID)
            .sessionVerID(sessionVerID)
            .requestTimestamp().time(requestTimestamp);
        negotiateResponse
            .enteringFirm(enteringFirm);

        DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", negotiateResponseAppendTo);

        commit();

        return position;
    }

    public long sendEstablishAck(
        final long sessionID,
        final long sessionVerID,
        final long requestTimestamp,
        final long keepAliveInterval,
        final long nextSeqNo,
        final long lastIncomingSeqNo)
    {
        final EstablishAckEncoder establishAck = this.establishAck;

        final long position = claimMessage(EstablishAckEncoder.BLOCK_LENGTH, establishAck, requestTimestamp);
        if (position < 0)
        {
            return position;
        }

        establishAck
            .sessionID(sessionID)
            .sessionVerID(sessionVerID)
            .requestTimestamp().time(requestTimestamp);
        establishAck.keepAliveInterval().time(keepAliveInterval);
        establishAck
            .nextSeqNo(nextSeqNo)
            .lastIncomingSeqNo(lastIncomingSeqNo);

        DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", establishAckAppendTo);

        commit();

        return position;
    }

    public long sendEstablishReject(
        final long sessionID,
        final long sessionVerID,
        final long requestTimestamp,
        final EstablishRejectCode establishmentRejectCode)
    {
        final EstablishRejectEncoder establishReject = this.establishReject;

        final long position = claimMessage(EstablishRejectEncoder.BLOCK_LENGTH, establishReject, requestTimestamp);
        if (position < 0)
        {
            return position;
        }

        establishReject
            .sessionID(sessionID)
            .sessionVerID(sessionVerID)
            .requestTimestamp().time(requestTimestamp);
        establishReject.establishmentRejectCode(establishmentRejectCode);

        DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", establishRejectAppendTo);

        commit();

        return position;
    }

    public long sendTerminate(
        final long sessionId,
        final long sessionVerId,
        final TerminationCode terminationCode,
        final long timestampInNs)
    {
        final TerminateEncoder terminate = this.terminate;

        final long position = claimMessage(TerminateEncoder.BLOCK_LENGTH, terminate, timestampInNs);
        if (position < 0)
        {
            return position;
        }

        terminate
            .sessionID(sessionId)
            .sessionVerID(sessionVerId)
            .terminationCode(terminationCode);

        DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", terminateAppendTo);

        commit();

        return position;
    }

    public long sendFinishedReceiving(final long sessionID, final long sessionVerId, final long timestampInNs)
    {
        final FinishedReceivingEncoder finishedReceiving = this.finishedReceiving;

        final long position = claimMessage(FinishedReceivingEncoder.BLOCK_LENGTH, finishedReceiving, timestampInNs);
        if (position < 0)
        {
            return position;
        }

        finishedReceiving
            .sessionID(sessionID)
            .sessionVerID(sessionVerId);

        DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", finishedReceivingAppendTo);

        commit();

        return position;
    }

    public long sendFinishedSending(
        final long sessionId, final long sessionVerId, final long lastSeqNo, final long timestampInNs)
    {
        final FinishedSendingEncoder finishedSending = this.finishedSending;

        final long position = claimMessage(FinishedSendingEncoder.BLOCK_LENGTH, finishedSending, timestampInNs);
        if (position < 0)
        {
            return position;
        }

        finishedSending
            .sessionID(sessionId)
            .sessionVerID(sessionVerId)
            .lastSeqNo(lastSeqNo);

        DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", finishedSendingAppendTo);

        commit();

        return position;
    }

    public long sendNotApplied(final long fromSeqNo, final long count, final long timestampInNs)
    {
        final NotAppliedEncoder notApplied = this.notApplied;

        final long position = claimMessage(NotAppliedEncoder.BLOCK_LENGTH, notApplied, timestampInNs);
        if (position < 0)
        {
            return position;
        }

        notApplied
            .fromSeqNo(fromSeqNo)
            .count(count);

        DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", notAppliedAppendTo);

        commit();

        return position;
    }

    public long sendRetransmission(
        final long nextSeqNo, final long count, final long internalTimestampInNs, final long requestTimestampInNs)
    {
        final RetransmissionEncoder retransmission = this.retransmission;

        final long position = claimMessage(RetransmissionEncoder.BLOCK_LENGTH, retransmission, internalTimestampInNs);
        if (position < 0)
        {
            return position;
        }

        retransmission
            .sessionID(sessionId)
            .requestTimestamp().time(requestTimestampInNs);
        retransmission
            .nextSeqNo(nextSeqNo)
            .count(count);

        DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", retransmissionAppendTo);

        commit();

        return position;
    }

    public long sendRetransmitReject(
        final RetransmitRejectCode retransmitRejectCode, final long timestampInNs, final long requestTimestampInNs)
    {
        final RetransmitRejectEncoder retransmitReject = this.retransmitReject;

        final long position = claimMessage(RetransmitRejectEncoder.BLOCK_LENGTH, retransmitReject, timestampInNs);
        if (position < 0)
        {
            return position;
        }

        retransmitReject
            .sessionID(sessionId)
            .requestTimestamp().time(requestTimestampInNs);
        retransmitReject.retransmitRejectCode(retransmitRejectCode);

        DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", retransmitRejectAppendTo);

        commit();

        return position;
    }

    public long claimMessage(
        final int messageLength,
        final MessageEncoderFlyweight message,
        final long timestampInNs)
    {
        return claimMessage(
            messageLength,
            message,
            timestampInNs,
            BINARY_ENTRYPOINT_MESSAGE_HEADER,
            BINARY_ENTRYPOINT_HEADER_LENGTH,
            BINARY_ENTRYPOINT_TYPE);
    }

    protected int applyHeader(
        final MessageEncoderFlyweight message, final MutableDirectBuffer buffer, final int offset)
    {
        beMessageHeader
            .wrap(buffer, offset)
            .blockLength(message.sbeBlockLength())
            .templateId(message.sbeTemplateId())
            .schemaId(message.sbeSchemaId())
            .version(message.sbeSchemaVersion());

        return offset + beMessageHeader.encodedLength();
    }

    public ByteBuffer encodeReject(
        final FixPContext fixPContext, final FixPFirstMessageResponse rejectReason)
    {
        final BinaryEntryPointContext identification = (BinaryEntryPointContext)fixPContext;

        final boolean isNegotiate;
        final NegotiationRejectCode negotiationRejectCode;
        final EstablishRejectCode establishRejectCode;
        switch (rejectReason)
        {
            case CREDENTIALS:
                isNegotiate = identification.fromNegotiate();
                negotiationRejectCode = NegotiationRejectCode.CREDENTIALS;
                establishRejectCode = EstablishRejectCode.CREDENTIALS;
                break;

            case NEGOTIATE_DUPLICATE_ID:
                isNegotiate = true;
                negotiationRejectCode = NegotiationRejectCode.DUPLICATE_ID;
                establishRejectCode = null;
                break;

            case NEGOTIATE_UNSPECIFIED:
                isNegotiate = true;
                negotiationRejectCode = NegotiationRejectCode.UNSPECIFIED;
                establishRejectCode = null;
                break;

            case ESTABLISH_UNNEGOTIATED:
                isNegotiate = false;
                negotiationRejectCode = null;
                establishRejectCode = EstablishRejectCode.UNNEGOTIATED;
                break;

            default:
                throw new IllegalArgumentException("Invalid reject reason: " + rejectReason);
        }

        final ByteBuffer byteBuffer;
        if (isNegotiate)
        {
            byteBuffer = ByteBuffer.allocate(NEGOTIATE_REJECT_LENGTH);
            buffer.wrap(byteBuffer);

            SimpleOpenFramingHeader.writeSofh(buffer, 0, NEGOTIATE_REJECT_LENGTH, BINARY_ENTRYPOINT_TYPE);
            negotiateReject
                .wrapAndApplyHeader(buffer, SOFH_LENGTH, beMessageHeader)
                .sessionID(identification.sessionID())
                .sessionVerID(identification.sessionVerID())
                .requestTimestamp().time(identification.requestTimestampInNs());
            negotiateReject
                .enteringFirm(identification.enteringFirm())
                .negotiationRejectCode(negotiationRejectCode);

            DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", negotiateRejectAppendTo);
        }
        else
        {
            byteBuffer = ByteBuffer.allocate(ESTABLISH_REJECT_LENGTH);
            buffer.wrap(byteBuffer);

            SimpleOpenFramingHeader.writeSofh(buffer, 0, ESTABLISH_REJECT_LENGTH, BINARY_ENTRYPOINT_TYPE);
            establishReject
                .wrapAndApplyHeader(buffer, SOFH_LENGTH, beMessageHeader)
                .sessionID(identification.sessionID())
                .sessionVerID(identification.sessionVerID())
                .requestTimestamp().time(identification.requestTimestampInNs());
            establishReject
                .establishmentRejectCode(establishRejectCode);

            DebugLogger.logSbeDecoder(FIXP_SESSION, "< ", establishRejectAppendTo);
        }

        return byteBuffer;
    }

    public byte[] encodeFirstMessage(final FixPContext fixPContext)
    {
        final BinaryEntryPointContext context = (BinaryEntryPointContext)fixPContext;
        final byte[] bytes;
        if (context.fromNegotiate())
        {
            bytes = initBytes(NEGOTIATE_LENGTH);

            negotiate
                .wrapAndApplyHeader(buffer, SOFH_LENGTH, beMessageHeader)
                .sessionID(context.sessionID())
                .sessionVerID(context.sessionVerID())
                .timestamp().time(context.requestTimestampInNs());
            negotiate.enteringFirm(context.enteringFirm());
            negotiate.onbehalfFirm(NegotiateEncoder.onbehalfFirmNullValue());
            negotiate.senderLocation("");
        }
        else
        {
            bytes = initBytes(ESTABLISH_LENGTH);

            establish
                .wrapAndApplyHeader(buffer, SOFH_LENGTH, beMessageHeader)
                .sessionID(context.sessionID())
                .sessionVerID(context.sessionVerID())
                .timestamp().time(context.requestTimestampInNs());
            establish.keepAliveInterval().time(0);
            establish
                .nextSeqNo(EstablishEncoder.nextSeqNoNullValue())
                .cancelOnDisconnectType(CancelOnDisconnectType.DO_NOT_CANCEL_ON_DISCONNECT_OR_TERMINATE)
                .codTimeoutWindow().time(0);
        }

        return bytes;
    }

    private byte[] initBytes(final int length)
    {
        final byte[] bytes;
        bytes = new byte[length];
        buffer.wrap(bytes);
        SimpleOpenFramingHeader.writeSofh(buffer, 0, NEGOTIATE_LENGTH, BINARY_ENTRYPOINT_TYPE);
        return bytes;
    }
}
