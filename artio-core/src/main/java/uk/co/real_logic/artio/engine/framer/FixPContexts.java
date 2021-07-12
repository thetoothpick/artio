/*
 * Copyright 2020 Monotonic Ltd.
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
import org.agrona.Verify;
import org.agrona.collections.CollectionUtil;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.engine.FixPSessionInfo;
import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.engine.logger.LoggerUtil;
import uk.co.real_logic.artio.fixp.*;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.storage.messages.FixPContextWrapperDecoder;
import uk.co.real_logic.artio.storage.messages.FixPContextWrapperEncoder;

import java.util.*;
import java.util.function.Function;

import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_LONG;


public class FixPContexts implements SessionContexts
{
    public static final int WRAPPER_LENGTH = FixPContextWrapperEncoder.BLOCK_LENGTH;
    private final EnumMap<FixPProtocolType, AbstractFixPStorage> typeToStorage = new EnumMap<>(FixPProtocolType.class);
    private final Function<FixPProtocolType, AbstractFixPStorage> makeStorageFunc = this::makeStorage;

    private final MappedFile mappedFile;
    private final AtomicBuffer buffer;
    private final ErrorHandler errorHandler;
    private final EpochNanoClock epochNanoClock;
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final FixPContextWrapperEncoder contextWrapperEncoder = new FixPContextWrapperEncoder();
    private final FixPContextWrapperDecoder contextWrapperDecoder = new FixPContextWrapperDecoder();
    private final int actingBlockLength = contextWrapperEncoder.sbeBlockLength();
    private final int actingVersion = contextWrapperEncoder.sbeSchemaVersion();

    private final Long2LongHashMap authenticatedSessionIdToConnectionId = new Long2LongHashMap(MISSING_LONG);
    private final Map<FixPKey, FixPContext> keyToContext = new HashMap<>();
    private final List<FixPSessionInfo> sessionInfos = new ArrayList<>();

    private int offset;

    FixPContexts(final MappedFile mappedFile, final ErrorHandler errorHandler, final EpochNanoClock epochNanoClock)
    {
        this.mappedFile = mappedFile;
        this.buffer = mappedFile.buffer();
        this.errorHandler = errorHandler;
        this.epochNanoClock = epochNanoClock;
        loadBuffer();
    }

    private void loadBuffer()
    {
        if (LoggerUtil.initialiseBuffer(
            buffer,
            headerEncoder,
            headerDecoder,
            contextWrapperEncoder.sbeSchemaId(),
            contextWrapperEncoder.sbeTemplateId(),
            actingVersion,
            actingBlockLength,
            errorHandler))
        {
            mappedFile.force();
        }

        offset = MessageHeaderEncoder.ENCODED_LENGTH;

        final int capacity = buffer.capacity();
        while (offset < capacity)
        {
            contextWrapperDecoder.wrap(buffer, offset, actingBlockLength, actingVersion);
            final int protocolTypeValue = contextWrapperDecoder.protocolType();
            final int contextLength = contextWrapperDecoder.contextLength();
            if (protocolTypeValue == 0)
            {
                break;
            }

            offset += WRAPPER_LENGTH;

            final FixPProtocolType type = FixPProtocolType.get(protocolTypeValue);
            final AbstractFixPStorage storage = lookupStorage(type);
            final FixPContext context = storage.loadContext(buffer, offset, actingVersion);
            addContext(context);

            offset += contextLength;
        }
    }

    private void addContext(final FixPContext context)
    {
        final FixPContext oldContext = keyToContext.put(context.key(), context);
        sessionInfos.add(new InfoWrapper(context));
        if (oldContext != null)
        {
            CollectionUtil.removeIf(sessionInfos, info -> ((InfoWrapper)info).context == oldContext);
        }
    }

    private AbstractFixPStorage lookupStorage(final FixPProtocolType type)
    {
        return typeToStorage.computeIfAbsent(type, makeStorageFunc);
    }

    private AbstractFixPStorage makeStorage(final FixPProtocolType type)
    {
        return FixPProtocolFactory.make(type, errorHandler).makeStorage(epochNanoClock);
    }

    FixPContext calculateInitiatorContext(
        final FixPKey key, final boolean reestablishConnection)
    {
        Verify.notNull(key, "key");

        final FixPContext context = keyToContext.get(key);

        if (context != null)
        {
            context.initiatorReconnect(reestablishConnection);

            return context;
        }

        return allocateInitiatorContext(key);
    }

    private FixPContext allocateInitiatorContext(final FixPKey key)
    {
        final FixPContext context = newInitiatorContext(key);
        addContext(context);
        return context;
    }

    private FixPContext newInitiatorContext(final FixPKey key)
    {
        return lookupStorage(key.protocolType()).newInitiatorContext(key, offset + WRAPPER_LENGTH);
    }

    public void updateContext(final FixPContext context)
    {
        lookupStorage(context.protocolType()).updateContext(context, buffer);
    }

    public void saveNewContext(final FixPContext context)
    {
        final FixPProtocolType type = context.protocolType();
        final AbstractFixPStorage storage = lookupStorage(type);

        contextWrapperEncoder
            .wrap(buffer, offset)
            .protocolType(type.value());

        offset += WRAPPER_LENGTH;
        final int length = storage.saveContext(context, buffer, offset, actingVersion);
        contextWrapperEncoder.contextLength(length);

        offset += length;
    }

    int offset()
    {
        return offset;
    }

    public void close()
    {
        mappedFile.close();
    }

    public FixPFirstMessageResponse onAcceptorLogon(
        final long sessionId, final FixPContext context, final long connectionId)
    {
        final long duplicateConnection = authenticatedSessionIdToConnectionId.get(sessionId);
        if (duplicateConnection == MISSING_LONG)
        {
            authenticatedSessionIdToConnectionId.put(sessionId, connectionId);

            final FixPKey key = context.key();
            final FixPContext oldContext = keyToContext.get(key);
            final FixPFirstMessageResponse rejectReason = context.checkAccept(oldContext);
            if (rejectReason == FixPFirstMessageResponse.OK)
            {
                if (oldContext == null)
                {
                    saveNewContext(context);
                }
                else
                {
                    updateContext(context);
                }
                addContext(context);
            }
            return rejectReason;
        }
        else
        {
            return FixPFirstMessageResponse.NEGOTIATE_DUPLICATE_ID;
        }
    }

    public void onDisconnect(final long connectionId)
    {
        final Long2LongHashMap.EntryIterator it = authenticatedSessionIdToConnectionId.entrySet().iterator();
        while (it.hasNext())
        {
            it.next();

            if (it.getLongValue() == connectionId)
            {
                it.remove();
            }
        }
    }

    public List<FixPSessionInfo> allSessions()
    {
        return sessionInfos;
    }

    public void sequenceReset(final long sessionId, final long resetTimeInNs)
    {
        final FixPContext context = lookupContext(sessionId);
        if (context != null)
        {
            context.onEndSequence();
            updateContext(context);
        }
        else
        {
            errorHandler.onError(new IllegalArgumentException(
                "Unable to reset sequence number for " + sessionId + " unknown session"));
        }
    }

    public boolean isKnownSessionId(final long sessionId)
    {
        return lookupContext(sessionId) != null;
    }

    FixPContext lookupContext(final long sessionId)
    {
        final Iterator<Map.Entry<FixPKey, FixPContext>> it = keyToContext.entrySet().iterator();
        while (it.hasNext())
        {
            final Map.Entry<FixPKey, FixPContext> entry = it.next();
            if (entry.getKey().sessionIdIfExists() == sessionId)
            {
                return entry.getValue();
            }
        }
        return null;
    }

    public boolean isAuthenticated(final long sessionId)
    {
        return authenticatedSessionIdToConnectionId.containsKey(sessionId);
    }

    static class InfoWrapper implements FixPSessionInfo
    {
        private final FixPContext context;

        InfoWrapper(final FixPContext context)
        {
            this.context = context;
        }

        public FixPKey key()
        {
            return context.key();
        }

        public String toString()
        {
            return "InfoWrapper{" +
                "context=" + context +
                '}';
        }
    }
}
