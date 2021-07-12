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

import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.FixCounters;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.decoder.AbstractLogonDecoder;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.ByteBufferUtil;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.HeaderSetup;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.fields.EpochFractionFormat;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.messages.CancelOnDisconnectOption;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.SessionState;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.*;
import uk.co.real_logic.artio.util.EpochFractionClock;
import uk.co.real_logic.artio.util.EpochFractionClocks;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import uk.co.real_logic.artio.validation.*;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static uk.co.real_logic.artio.LogTag.FIX_CONNECTION;
import static uk.co.real_logic.artio.engine.SessionInfo.UNK_SESSION;
import static uk.co.real_logic.artio.engine.framer.FixContexts.DUPLICATE_SESSION;
import static uk.co.real_logic.artio.engine.framer.FixContexts.UNKNOWN_SESSION;
import static uk.co.real_logic.artio.validation.SessionPersistenceStrategy.resetSequenceNumbersUponLogon;

public class FixGatewaySessions extends GatewaySessions
{
    private final Map<FixDictionary, UserRequestExtractor> dictionaryToUserRequestExtractor = new HashMap<>();
    private final Function<FixDictionary, UserRequestExtractor> newUserRequestExtractor =
        dictionary -> new UserRequestExtractor(dictionary, errorHandler);

    private final EpochFractionClock epochFractionClock;
    private final SessionIdStrategy sessionIdStrategy;
    private final SessionCustomisationStrategy customisationStrategy;
    private final FixCounters fixCounters;
    private final AuthenticationStrategy authenticationStrategy;
    private final MessageValidationStrategy validationStrategy;
    private final int sessionBufferSize;
    private final long sendingTimeWindowInMs;
    private final long reasonableTransmissionTimeInMs;
    private final boolean logAllMessages;
    private final boolean validateCompIdsOnEveryMessage;
    private final boolean validateTimeStrictly;
    private final FixContexts fixContexts;
    private final SessionPersistenceStrategy sessionPersistenceStrategy;
    private final EpochNanoClock clock;
    private final EpochFractionFormat epochFractionPrecision;
    private final UtcTimestampEncoder sendingTimeEncoder;

    // Initialised after logon processed.
    private SessionContext sessionContext;

    FixGatewaySessions(
        final EpochClock epochClock,
        final GatewayPublication inboundPublication,
        final GatewayPublication outboundPublication,
        final SessionIdStrategy sessionIdStrategy,
        final SessionCustomisationStrategy customisationStrategy,
        final FixCounters fixCounters,
        final EngineConfiguration configuration,
        final ErrorHandler errorHandler,
        final FixContexts fixContexts,
        final SessionPersistenceStrategy sessionPersistenceStrategy,
        final SequenceNumberIndexReader sentSequenceNumberIndex,
        final SequenceNumberIndexReader receivedSequenceNumberIndex,
        final EpochFractionFormat epochFractionPrecision)
    {
        super(
            epochClock,
            inboundPublication,
            outboundPublication,
            errorHandler,
            sentSequenceNumberIndex,
            receivedSequenceNumberIndex);

        this.sessionIdStrategy = sessionIdStrategy;
        this.customisationStrategy = customisationStrategy;
        this.fixCounters = fixCounters;
        this.authenticationStrategy = configuration.authenticationStrategy();
        this.validationStrategy = configuration.messageValidationStrategy();
        this.sessionBufferSize = configuration.sessionBufferSize();
        this.sendingTimeWindowInMs = configuration.sendingTimeWindowInMs();
        this.reasonableTransmissionTimeInMs = configuration.reasonableTransmissionTimeInMs();
        this.logAllMessages = configuration.logAllMessages();
        this.validateCompIdsOnEveryMessage = configuration.validateCompIdsOnEveryMessage();
        this.validateTimeStrictly = configuration.validateTimeStrictly();
        this.clock = configuration.epochNanoClock();
        this.fixContexts = fixContexts;
        this.sessionPersistenceStrategy = sessionPersistenceStrategy;
        this.epochFractionPrecision = epochFractionPrecision;
        this.epochFractionClock = EpochFractionClocks.create(epochClock, configuration.epochNanoClock(),
            epochFractionPrecision);

        sendingTimeEncoder = new UtcTimestampEncoder(epochFractionPrecision);
    }


    void acquire(
        final FixGatewaySession gatewaySession,
        final SessionState state,
        final boolean awaitingResend,
        final int heartbeatIntervalInS,
        final int lastSentSequenceNumber,
        final int lastReceivedSequenceNumber,
        final String username,
        final String password,
        final BlockablePosition engineBlockablePosition)
    {
        final long connectionId = gatewaySession.connectionId();
        final AtomicCounter receivedMsgSeqNo = fixCounters.receivedMsgSeqNo(connectionId);
        final AtomicCounter sentMsgSeqNo = fixCounters.sentMsgSeqNo(connectionId);
        final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer(new byte[sessionBufferSize]);
        final OnMessageInfo messageInfo = new OnMessageInfo();

        final DirectSessionProxy proxy = new DirectSessionProxy(
            sessionBufferSize,
            outboundPublication,
            sessionIdStrategy,
            customisationStrategy,
            clock,
            connectionId,
            FixEngine.ENGINE_LIBRARY_ID,
            errorHandler,
            epochFractionPrecision);

        final InternalSession session = new InternalSession(
            heartbeatIntervalInS,
            connectionId,
            clock,
            state,
            proxy,
            inboundPublication,
            outboundPublication,
            sessionIdStrategy,
            sendingTimeWindowInMs,
            receivedMsgSeqNo,
            sentMsgSeqNo,
            FixEngine.ENGINE_LIBRARY_ID,
            lastSentSequenceNumber + 1,
            // This gets set by the receiver end point once the logon message has been received.
            0,
            reasonableTransmissionTimeInMs,
            asciiBuffer,
            gatewaySession.enableLastMsgSeqNumProcessed(),
            customisationStrategy,
            messageInfo,
            epochFractionClock,
            gatewaySession.connectionType());

        session.awaitingResend(awaitingResend);
        session.closedResendInterval(gatewaySession.closedResendInterval());
        session.resendRequestChunkSize(gatewaySession.resendRequestChunkSize());
        session.sendRedundantResendRequests(gatewaySession.sendRedundantResendRequests());

        final SessionParser sessionParser = new SessionParser(
            session,
            validationStrategy,
            errorHandler,
            validateCompIdsOnEveryMessage,
            validateTimeStrictly,
            messageInfo,
            sessionIdStrategy);

        if (!sessions.contains(gatewaySession))
        {
            sessions.add(gatewaySession);
        }
        gatewaySession.manage(sessionParser, session, engineBlockablePosition, proxy);

        if (DebugLogger.isEnabled(FIX_CONNECTION))
        {
            DebugLogger.log(FIX_CONNECTION, acquiredConnection.clear().with(connectionId));
        }
        final CompositeKey sessionKey = gatewaySession.sessionKey();
        if (sessionKey != null)
        {
            gatewaySession.updateSessionDictionary();
            gatewaySession.onLogon(username, password, heartbeatIntervalInS);
            session.initialLastReceivedMsgSeqNum(lastReceivedSequenceNumber);
        }
    }

    AcceptorLogonResult authenticate(
        final AbstractLogonDecoder logon,
        final long connectionId,
        final FixGatewaySession gatewaySession,
        final TcpChannel channel,
        final FixDictionary fixDictionary,
        final Framer framer)
    {
        gatewaySession.startAuthentication(epochClock.time());

        return new FixPendingAcceptorLogon(
            sessionIdStrategy, gatewaySession, logon, connectionId, fixContexts, channel, fixDictionary, framer);
    }

    void onUserRequest(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final FixDictionary dictionary,
        final long connectionId,
        final long sessionId)
    {
        final UserRequestExtractor extractor = dictionaryToUserRequestExtractor
            .computeIfAbsent(dictionary, newUserRequestExtractor);

        extractor.onUserRequest(buffer, offset, length, authenticationStrategy, connectionId, sessionId);
    }

    void onDisconnect(final long sessionId, final long connectionId, final DisconnectReason reason)
    {
        authenticationStrategy.onDisconnect(
            sessionId,
            connectionId,
            reason);
    }

    protected void setLastSequenceResetTime(final GatewaySession session)
    {
        ((FixGatewaySession)session).lastSequenceResetTime(sessionContext.lastSequenceResetTime());
    }

    final class FixPendingAcceptorLogon extends GatewaySessions.PendingAcceptorLogon implements AuthenticationProxy
    {
        private static final int ENCODE_BUFFER_SIZE = 1024;

        private final SessionIdStrategy sessionIdStrategy;
        private final FixGatewaySession session;
        private final AbstractLogonDecoder logon;
        private final FixContexts fixContexts;
        private FixDictionary fixDictionary;
        private final boolean resetSeqNum;

        private Encoder encoder;
        private Class<? extends FixDictionary> fixDictionaryClass;

        FixPendingAcceptorLogon(
            final SessionIdStrategy sessionIdStrategy,
            final FixGatewaySession gatewaySession,
            final AbstractLogonDecoder logon,
            final long connectionId,
            final FixContexts fixContexts,
            final TcpChannel channel,
            final FixDictionary fixDictionary,
            final Framer framer)
        {
            super(gatewaySession, connectionId, channel, framer);

            this.sessionIdStrategy = sessionIdStrategy;
            this.session = gatewaySession;
            this.logon = logon;
            this.fixContexts = fixContexts;
            this.fixDictionary = fixDictionary;

            final PersistenceLevel persistenceLevel = getPersistenceLevel(logon, connectionId);
            final boolean resetSeqNumFlag = logon.hasResetSeqNumFlag() && logon.resetSeqNumFlag();

            final boolean resetSequenceNumbersUponLogon = resetSequenceNumbersUponLogon(persistenceLevel);
            resetSeqNum = resetSequenceNumbersUponLogon || resetSeqNumFlag;

            if (!resetSequenceNumbersUponLogon && !logAllMessages)
            {
                onError(new IllegalStateException(
                    "Persistence Strategy specified INDEXED but " +
                    "EngineConfiguration has disabled required logging of messsages"));

                reject(DisconnectReason.INVALID_CONFIGURATION_NOT_LOGGING_MESSAGES);
                return;
            }

            authenticate(logon, connectionId);
        }

        private PersistenceLevel getPersistenceLevel(final AbstractLogonDecoder logon, final long connectionId)
        {
            try
            {
                return sessionPersistenceStrategy.getPersistenceLevel(logon);
            }
            catch (final Throwable throwable)
            {
                onStrategyError(
                    "persistence", throwable, connectionId, "TRANSIENT_SEQUENCE_NUMBERS", logon.toString());
                return PersistenceLevel.TRANSIENT_SEQUENCE_NUMBERS;
            }
        }

        private void authenticate(final AbstractLogonDecoder logon, final long connectionId)
        {
            try
            {
                authenticationStrategy.authenticateAsync(logon, this);
            }
            catch (final Throwable throwable)
            {
                onStrategyError("authentication", throwable, connectionId, "false", logon.toString());

                if (state != AuthenticationState.REJECTED)
                {
                    reject();
                }
            }
        }

        public void accept(final Class<? extends FixDictionary> fixDictionaryClass)
        {
            validateState();

            this.fixDictionaryClass = fixDictionaryClass;
            state = AuthenticationState.AUTHENTICATED;
        }

        protected void onAuthenticated()
        {
            if (fixDictionaryClass != null && fixDictionary.getClass() != fixDictionaryClass)
            {
                fixDictionary = FixDictionary.of(fixDictionaryClass);
                session.fixDictionary(fixDictionary);
            }

            final String username = SessionParser.username(logon);
            final String password = SessionParser.password(logon);
            final CancelOnDisconnectOption cancelOnDisconnectOption = SessionParser.cancelOnDisconnectType(logon);
            final long cancelOnDisconnectTimeoutWindowInNs =
                MILLISECONDS.toNanos(SessionParser.cancelOnDisconnectTimeoutWindow(logon));

            final SessionHeaderDecoder header = logon.header();
            final CompositeKey compositeKey;
            try
            {
                compositeKey = sessionIdStrategy.onAcceptLogon(header);
            }
            catch (final IllegalArgumentException e)
            {
                reject(DisconnectReason.MISSING_LOGON_COMP_ID);
                return;
            }

            sessionContext = fixContexts.onLogon(compositeKey, fixDictionary);

            if (sessionContext == DUPLICATE_SESSION)
            {
                reject(DisconnectReason.DUPLICATE_SESSION);
                return;
            }

            final boolean isOfflineReconnect = framer.onFixLogonMessageReceived(session, sessionContext.sessionId());

            final long logonTimeInNs = clock.nanoTime();
            sessionContext.onLogon(resetSeqNum, logonTimeInNs, fixDictionary);
            session.initialResetSeqNum(resetSeqNum);
            session.fixDictionary(fixDictionary);
            session.updateSessionDictionary();
            session.onLogon(
                sessionContext.sessionId(),
                sessionContext,
                compositeKey,
                username,
                password,
                logon.heartBtInt(),
                header.msgSeqNum(),
                cancelOnDisconnectOption,
                cancelOnDisconnectTimeoutWindowInNs);
            session.lastLogonTime(logonTimeInNs);

            // See Framer.handoverNewConnectionToLibrary for sole library mode equivalent
            if (resetSeqNum)
            {
                session.acceptorSequenceNumbers(UNK_SESSION, UNK_SESSION);
                session.lastLogonWasSequenceReset();
                state = AuthenticationState.ACCEPTED;
            }
            else
            {
                requiredPosition = outboundPublication.position();
                state = AuthenticationState.INDEXER_CATCHUP;
            }

            framer.onGatewaySessionSetup(session, isOfflineReconnect);
        }

        public void reject(final Encoder encoder, final long lingerTimeoutInMs)
        {
            Objects.requireNonNull(encoder, "encoder should be provided");

            if (lingerTimeoutInMs < 0)
            {
                throw new IllegalArgumentException(String.format(
                    "lingerTimeoutInMs should not be negative, (%d)", lingerTimeoutInMs));
            }

            this.encoder = encoder;
            this.reason = DisconnectReason.FAILED_AUTHENTICATION;
            this.lingerTimeoutInMs = lingerTimeoutInMs;
            this.state = AuthenticationState.SENDING_REJECT_MESSAGE;
        }

        protected void encodeRejectMessage()
        {
            rejectEncodeBuffer = ByteBuffer.allocateDirect(ENCODE_BUFFER_SIZE);

            final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer(rejectEncodeBuffer);

            final SessionHeaderEncoder header = encoder.header();
            header.msgSeqNum(1);
            header.sendingTime(
                sendingTimeEncoder.buffer(), sendingTimeEncoder.encodeFrom(clock.nanoTime(), TimeUnit.NANOSECONDS));
            HeaderSetup.setup(logon.header(), header);
            customisationStrategy.configureHeader(header, UNKNOWN_SESSION.sessionId());

            final long result = encoder.encode(asciiBuffer, 0);
            final int offset = Encoder.offset(result);
            final int length = Encoder.length(result);

            ByteBufferUtil.position(rejectEncodeBuffer, offset);
            ByteBufferUtil.limit(rejectEncodeBuffer, offset + length);
        }

        public void reject()
        {
            validateState();

            reject(DisconnectReason.FAILED_AUTHENTICATION);
        }
    }
}
