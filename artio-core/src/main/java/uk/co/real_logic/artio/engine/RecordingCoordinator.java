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
package uk.co.real_logic.artio.engine;

import io.aeron.Aeron;
import io.aeron.ChannelUri;
import io.aeron.ExclusivePublication;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveException;
import io.aeron.archive.client.RecordingDescriptorConsumer;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.engine.logger.RecordingIdLookup;
import uk.co.real_logic.artio.storage.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.storage.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.storage.messages.PreviousRecordingDecoder;
import uk.co.real_logic.artio.storage.messages.PreviousRecordingDecoder.InboundRecordingsDecoder;
import uk.co.real_logic.artio.storage.messages.PreviousRecordingDecoder.OutboundRecordingsDecoder;
import uk.co.real_logic.artio.storage.messages.PreviousRecordingEncoder;
import uk.co.real_logic.artio.storage.messages.PreviousRecordingEncoder.InboundRecordingsEncoder;
import uk.co.real_logic.artio.storage.messages.PreviousRecordingEncoder.OutboundRecordingsEncoder;
import uk.co.real_logic.artio.util.CharFormatter;

import java.io.File;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.CommonContext.MTU_LENGTH_PARAM_NAME;
import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static io.aeron.archive.codecs.SourceLocation.LOCAL;
import static io.aeron.archive.codecs.SourceLocation.REMOTE;
import static io.aeron.driver.Configuration.publicationReservedSessionIdHigh;
import static io.aeron.driver.Configuration.publicationReservedSessionIdLow;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.agrona.concurrent.status.CountersReader.NULL_COUNTER_ID;
import static uk.co.real_logic.artio.LogTag.STATE_CLEANUP;
import static uk.co.real_logic.artio.storage.messages.MessageHeaderDecoder.ENCODED_LENGTH;

/**
 * Not thread safe.
 */
public class RecordingCoordinator implements AutoCloseable, RecordingDescriptorConsumer
{
    private static volatile boolean saveOnShutdown = true;

    // Used for testing the the resilience of this file loading / saving mechanism to the process being terminated.
    public static void saveOnShutdownTesting(final boolean saveOnShutdown)
    {
        RecordingCoordinator.saveOnShutdown = saveOnShutdown;
    }

    private static final String FILE_NAME = "recording_coordinator";

    public static File recordingIdsFile(final EngineConfiguration configuration)
    {
        return new File(configuration.logFileDir(), FILE_NAME);
    }

    private final CharFormatter loadRecordings = new CharFormatter(
        "RecordingCoordinator.loadRecordingIds: inbound=%s,outbound=%s");
    private final CharFormatter recordingStarted = new CharFormatter(
        "RecordingCoordinator.recordingStarted: recordingId=%s,direction=%s");

    // Only used on startup and shutdown
    private final IdleStrategy idleStrategy = CommonConfiguration.backoffIdleStrategy();

    private final SourceLocation outboundLocation;
    private final LongHashSet trackedRegistrationIds = new LongHashSet();
    private final Aeron aeron;
    private final AeronArchive archive;
    private final String channel;
    private final CountersReader counters;
    private final EngineConfiguration configuration;
    private final RecordingIdLookup framerInboundLookup;
    private final RecordingIdLookup framerOutboundLookup;
    private final RecordingIdLookup indexerInboundLookup;
    private final RecordingIdLookup indexerOutboundLookup;
    private final File recordingIdsFile;
    private final ErrorHandler errorHandler;

    private final RecordingIds inboundRecordingIds = new RecordingIds();
    private final RecordingIds outboundRecordingIds = new RecordingIds();
    private final Long2ObjectHashMap<LibraryExtendPosition> libraryIdToExtendPosition = new Long2ObjectHashMap<>();

    private Long2LongHashMap inboundAeronSessionIdToCompletionPosition;
    private Long2LongHashMap outboundAeronSessionIdToCompletionPosition;

    private boolean closed = false;

    private LibraryExtendPosition libraryExtendPosition;

    RecordingCoordinator(
        final Aeron aeron,
        final AeronArchive archive,
        final EngineConfiguration configuration,
        final IdleStrategy archiverIdleStrategy,
        final ErrorHandler errorHandler)
    {
        this.aeron = aeron;
        this.archive = archive;
        this.configuration = configuration;
        this.channel = configuration.libraryAeronChannel();

        recordingIdsFile = recordingIdsFile(configuration);
        this.errorHandler = errorHandler;
        outboundLocation = channel.equals(IPC_CHANNEL) ? LOCAL : REMOTE;
        loadRecordingIdsFile();

        if (configuration.logAnyMessages())
        {
            counters = this.aeron.countersReader();
            framerInboundLookup = new RecordingIdLookup(archiverIdleStrategy, counters);
            framerOutboundLookup = new RecordingIdLookup(archiverIdleStrategy, counters);
            indexerInboundLookup = new RecordingIdLookup(archiverIdleStrategy, counters);
            indexerOutboundLookup = new RecordingIdLookup(archiverIdleStrategy, counters);
        }
        else
        {
            counters = null;
            framerInboundLookup = null;
            framerOutboundLookup = null;
            indexerInboundLookup = null;
            indexerOutboundLookup = null;
        }
    }

    // constructor
    private void loadRecordingIdsFile()
    {
        if (recordingIdsFile.exists())
        {
            final MappedByteBuffer mappedBuffer = IoUtil.mapExistingFile(recordingIdsFile, FILE_NAME);
            final UnsafeBuffer buffer = new UnsafeBuffer(mappedBuffer);
            try
            {
                final MessageHeaderDecoder header = new MessageHeaderDecoder();
                final PreviousRecordingDecoder previousRecording = new PreviousRecordingDecoder();

                header.wrap(buffer, 0);
                previousRecording.wrap(buffer, ENCODED_LENGTH, header.blockLength(), header.version());

                for (final InboundRecordingsDecoder inboundRecording : previousRecording.inboundRecordings())
                {
                    inboundRecordingIds.free.add(inboundRecording.recordingId());
                }

                for (final OutboundRecordingsDecoder outboundRecording : previousRecording.outboundRecordings())
                {
                    outboundRecordingIds.free.add(outboundRecording.recordingId());
                }

                if (DebugLogger.isEnabled(STATE_CLEANUP))
                {
                    DebugLogger.log(STATE_CLEANUP, loadRecordings
                        .clear()
                        .with(inboundRecordingIds.toString())
                        .with(outboundRecordingIds.toString()));
                }
            }
            finally
            {
                IoUtil.unmap(mappedBuffer);
            }
        }
    }

    private void saveRecordingIdsFile()
    {
        try
        {
            final int inboundSize = inboundRecordingIds.size();
            final int outboundSize = outboundRecordingIds.size() + libraryIdToExtendPosition.size();

            final File saveFile = File.createTempFile(FILE_NAME, "tmp", new File(configuration.logFileDir()));
            final int requiredLength = MessageHeaderEncoder.ENCODED_LENGTH + PreviousRecordingEncoder.BLOCK_LENGTH +
                InboundRecordingsEncoder.HEADER_SIZE + OutboundRecordingsEncoder.HEADER_SIZE +
                InboundRecordingsEncoder.recordingIdEncodingLength() * inboundSize +
                OutboundRecordingsEncoder.recordingIdEncodingLength() * outboundSize;
            final MappedByteBuffer mappedBuffer = IoUtil.mapExistingFile(saveFile, FILE_NAME, 0, requiredLength);
            final UnsafeBuffer buffer = new UnsafeBuffer(mappedBuffer);
            try
            {
                final MessageHeaderEncoder header = new MessageHeaderEncoder();
                final PreviousRecordingEncoder previousRecording = new PreviousRecordingEncoder();

                previousRecording.wrapAndApplyHeader(buffer, 0, header);

                final InboundRecordingsEncoder inbound = previousRecording.inboundRecordingsCount(inboundSize);
                inboundRecordingIds.forEach(id -> inbound.next().recordingId(id));

                final OutboundRecordingsEncoder outbound = previousRecording.outboundRecordingsCount(outboundSize);
                outboundRecordingIds.forEach(id -> outbound.next().recordingId(id));

                for (final LibraryExtendPosition pos : libraryIdToExtendPosition.values())
                {
                    outbound.next().recordingId(pos.recordingId);
                }

                mappedBuffer.force();
            }
            finally
            {
                IoUtil.unmap(mappedBuffer);
            }

            Files.move(saveFile.toPath(), recordingIdsFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING);
        }
        catch (final Throwable e)
        {
            errorHandler.onError(e);
        }
    }

    // Only called on single threaded engine startup
    public ExclusivePublication track(final String aeronChannel, final int streamId)
    {
        if (configuration.isRelevantStreamId(streamId))
        {
            final boolean isInbound = streamId == configuration.inboundLibraryStream();
            final RecordingIds recordingIds = isInbound ? inboundRecordingIds : outboundRecordingIds;
            final RecordingIdLookup lookup = isInbound ? framerOutboundLookup : framerInboundLookup;
            final LibraryExtendPosition libraryExtendPosition = acquireRecording(streamId, recordingIds);
            final ExclusivePublication publication;
            if (libraryExtendPosition != null)
            {
                final ChannelUri channelUri = ChannelUri.parse(aeronChannel);
                channelUri.initialPosition(
                    libraryExtendPosition.stopPosition,
                    libraryExtendPosition.initialTermId,
                    libraryExtendPosition.termBufferLength);
                setMtuLength(libraryExtendPosition.mtuLength, channelUri);
                final String channel = channelUri.toString();
                publication = aeron.addExclusivePublication(channel, streamId);
                extendRecording(streamId, libraryExtendPosition, publication.sessionId());
            }
            else
            {
                publication = aeron.addExclusivePublication(aeronChannel, streamId);
                startRecording(streamId, publication.sessionId(), LOCAL);
            }

            checkRecordingStart(publication.sessionId(), lookup, recordingIds.used, isInbound);

            return publication;
        }
        else
        {
            return aeron.addExclusivePublication(aeronChannel, streamId);
        }
    }

    public static void setMtuLength(final int mtuLength, final ChannelUri channelUri)
    {
        channelUri.put(MTU_LENGTH_PARAM_NAME, Integer.toString(mtuLength));
    }

    private void extendRecording(
        final int streamId, final LibraryExtendPosition libraryExtendPosition, final int sessionId)
    {
        try
        {
            final String recordingChannel = ChannelUri.addSessionId(channel, sessionId);
            final long registrationId = archive.extendRecording(
                libraryExtendPosition.recordingId, recordingChannel, streamId, LOCAL);
            trackedRegistrationIds.add(registrationId);
        }
        catch (final ArchiveException e)
        {
            errorHandler.onError(e);
        }
    }

    private LibraryExtendPosition acquireRecording(final int streamId, final RecordingIds recordingIds)
    {
        libraryExtendPosition = null;

        final LongHashSet.LongIterator it = recordingIds.free.iterator();
        if (it.hasNext())
        {
            final long recordingId = it.nextValue();
            it.remove();

            final int count = archive.listRecording(recordingId, this);
            if (count != 1 || null == libraryExtendPosition)
            {
                errorHandler.onError(new IllegalStateException("Unable to reuse recordingId: " + recordingId +
                    " (Perhaps you have deleted this recording id or some aeron archiver state?)"));
                if (libraryExtendPosition == null)
                {
                    return null;
                }
            }
            else if (libraryExtendPosition.streamId != streamId)
            {
                errorHandler.onError(new IllegalStateException(String.format(
                    "Unable to reuse recordingId: %d. Stream id is mismatch: actual: %d, expected: %d",
                    recordingId, libraryExtendPosition.streamId, streamId)));
                libraryExtendPosition = null;
                return null;
            }
            // A NULL stopPosition means the recording wasn't stopped. This can potentially happen if we restart the
            // Engine process with no clean shutdown and a running media driver. In order to hit this scenario you
            // to restart the process rapidly as the media driver will eventually timeout the old process and stop the
            // associated recording.
            while (libraryExtendPosition.stopPosition == NULL_POSITION)
            {
                // We don't check the return value here because it returns false if the recording has stopped.
                // This might happened if a timeout based stop occurs since the call to archive.listRecording.
                archive.tryStopRecordingByIdentity(recordingId);
                libraryExtendPosition.stopPosition = archive.getStopPosition(recordingId);
            }
        }

        return libraryExtendPosition;
    }

    // Called from Framer thread.
    public LibraryExtendPosition trackLibrary(final int sessionId, final int libraryId)
    {
        if (configuration.logOutboundMessages())
        {
            final int streamId = configuration.outboundLibraryStream();

            LibraryExtendPosition extendPosition = libraryIdToExtendPosition.get(libraryId);
            if (extendPosition != null)
            {
                // Library has moved to a previously configured extend position
                if (sessionId != extendPosition.newSessionId)
                {
                    // Received another Library Connect message before the library has switched to an extend
                    // Position, we should tell the library to extend position and await the position extension
                    // response.
                    return extendPosition;
                }
                else
                {
                    libraryIdToExtendPosition.remove(libraryId);
                    checkRecordingStart(sessionId, framerOutboundLookup, outboundRecordingIds.used, false);
                    return null;
                }
            }

            extendPosition = acquireRecording(streamId, outboundRecordingIds);
            if (extendPosition != null)
            {
                extendRecording(streamId, extendPosition, extendPosition.newSessionId);
                saveRecordingIdsFile();
                // Library needs to be informed of its extend position.
                libraryIdToExtendPosition.put(libraryId, extendPosition);
                return extendPosition;
            }
            else
            {
                if (startRecording(streamId, sessionId, outboundLocation))
                {
                    checkRecordingStart(sessionId, framerOutboundLookup, outboundRecordingIds.used, false);
                }
            }
        }

        return null;
    }

    public void onRecordingDescriptor(
        final long controlSessionId, final long correlationId, final long recordingId,
        final long startTimestamp, final long stopTimestamp, final long startPosition,
        final long stopPosition, final int initialTermId, final int segmentFileLength,
        final int termBufferLength, final int mtuLength, final int sessionId,
        final int streamId, final String strippedChannel, final String originalChannel,
        final String sourceIdentity)
    {
        final int newSessionId = ThreadLocalRandom.current().nextInt(
            publicationReservedSessionIdLow(), publicationReservedSessionIdHigh());
        this.libraryExtendPosition = new LibraryExtendPosition(
            newSessionId, recordingId, streamId, stopPosition, initialTermId, termBufferLength, mtuLength);
    }

    private boolean startRecording(
        final int streamId,
        final int sessionId,
        final SourceLocation local)
    {
        if (recordingAlreadyStarted(sessionId))
        {
            return true;
        }

        try
        {
            final String channel = ChannelUri.addSessionId(this.channel, sessionId);
            final long registrationId = archive.startRecording(channel, streamId, local);
            trackedRegistrationIds.add(registrationId);

            return true;
        }
        catch (final ArchiveException e)
        {
            errorHandler.onError(e);

            return false;
        }
    }

    private boolean recordingAlreadyStarted(final int sessionId)
    {
        return RecordingPos.findCounterIdBySession(counters, sessionId) != Aeron.NULL_VALUE;
    }

    // awaits the recording start and saves the file
    private void checkRecordingStart(
        final int sessionId, final RecordingIdLookup lookup, final LongHashSet recordingIds, final boolean isInbound)
    {
        final long recordingId = lookup.getRecordingId(sessionId);
        recordingIds.add(recordingId);

        saveRecordingIdsFile();

        if (DebugLogger.isEnabled(STATE_CLEANUP))
        {
            DebugLogger.log(STATE_CLEANUP, recordingStarted
                .clear()
                .with(recordingId)
                .with(isInbound ? "inbound" : "outbound"));
        }
    }

    // Called only on Framer.quiesce(), uses shutdown order
    public void completionPositions(
        final Long2LongHashMap inboundAeronSessionIdToCompletionPosition,
        final Long2LongHashMap outboundAeronSessionIdToCompletionPosition)
    {
        this.inboundAeronSessionIdToCompletionPosition = inboundAeronSessionIdToCompletionPosition;
        this.outboundAeronSessionIdToCompletionPosition = outboundAeronSessionIdToCompletionPosition;
    }

    // Must be called after the framer has shutdown, uses shutdown order
    public void close()
    {
        if (configuration.gracefulShutdown())
        {
            if (!closed)
            {
                awaitRecordingsCompletion();
                shutdownArchiver();
                closed = true;
            }
        }
    }

    private void awaitRecordingsCompletion()
    {
        if (configuration.logInboundMessages())
        {
            awaitRecordingsCompletion(inboundAeronSessionIdToCompletionPosition);
        }

        if (configuration.logOutboundMessages())
        {
            awaitRecordingsCompletion(outboundAeronSessionIdToCompletionPosition);
        }

        if (saveOnShutdown)
        {
            saveRecordingIdsFile();
        }
    }

    private void awaitRecordingsCompletion(
        final Long2LongHashMap aeronSessionIdToCompletionPosition)
    {
        if (aeronSessionIdToCompletionPosition == null)
        {
            throw new IllegalStateException(
                "Unknown completionPositions when shutting down the RecordingCoordinator");
        }

        final List<CompletingRecording> completingRecordings = new ArrayList<>();
        aeronSessionIdToCompletionPosition.longForEach((sessionId, completionPosition) ->
        {
            final int counterId = RecordingPos.findCounterIdBySession(counters, (int)sessionId);
            // Recording has completed
            if (counterId != NULL_COUNTER_ID)
            {
                final CompletingRecording recording = new CompletingRecording(completionPosition, counterId);
                completingRecordings.add(recording);
            }
        });

        while (!completingRecordings.isEmpty())
        {
            completingRecordings.removeIf(CompletingRecording::hasRecordingCompleted);

            idleStrategy.idle();
        }
        idleStrategy.reset();
    }

    private void shutdownArchiver()
    {
        final LongHashSet.LongIterator it = trackedRegistrationIds.iterator();
        while (it.hasNext())
        {
            final long registrationId = it.nextValue();
            archive.stopRecording(registrationId);
        }

        if (configuration.logAnyMessages())
        {
            archive.close();
        }
    }

    // Called after shutdown or on the Framer Thread
    public void forEachRecording(final LongConsumer recordingIdConsumer)
    {
        inboundRecordingIds.forEach(recordingIdConsumer);
        outboundRecordingIds.forEach(recordingIdConsumer);
    }

    private class CompletingRecording
    {
        private final long completedPosition;
        private final long recordingId;
        private final int counterId;

        CompletingRecording(final long completedPosition, final int counterId)
        {
            this.completedPosition = completedPosition;

            this.counterId = counterId;
            recordingId = RecordingPos.getRecordingId(counters, this.counterId);
        }

        boolean hasRecordingCompleted()
        {
            final long recordedPosition = counters.getCounterValue(counterId);
            if (recordedPosition >= completedPosition)
            {
                return true;
            }

            if (!RecordingPos.isActive(counters, counterId, recordingId))
            {
                throw new IllegalStateException("recording has stopped unexpectedly: " + recordingId);
            }

            return false;
        }
    }

    RecordingIdLookup indexerInboundRecordingIdLookup()
    {
        return indexerInboundLookup;
    }

    RecordingIdLookup indexerOutboundRecordingIdLookup()
    {
        return indexerOutboundLookup;
    }

    public RecordingIdLookup framerInboundLookup()
    {
        return framerInboundLookup;
    }

    public RecordingIdLookup framerOutboundLookup()
    {
        return framerOutboundLookup;
    }

    static final class RecordingIds
    {
        private final LongHashSet free = new LongHashSet();
        private final LongHashSet used = new LongHashSet();

        int size()
        {
            return free.size() + used.size();
        }

        void forEach(final LongConsumer recordingIdConsumer)
        {
            forEach(free, recordingIdConsumer);
            forEach(used, recordingIdConsumer);
        }

        private void forEach(final LongHashSet set, final LongConsumer recordingIdConsumer)
        {
            final LongHashSet.LongIterator it = set.iterator();
            while (it.hasNext())
            {
                recordingIdConsumer.accept(it.nextValue());
            }
        }

        public String toString()
        {
            return "RecordingIds{" +
                "free=" + free +
                ", used=" + used +
                '}';
        }
    }

    public static class LibraryExtendPosition
    {
        public final int newSessionId;
        public final long recordingId;
        public final int streamId;
        public final int initialTermId;
        public final int termBufferLength;
        public final int mtuLength;

        public long stopPosition;

        LibraryExtendPosition(
            final int newSessionId,
            final long recordingId,
            final int streamId,
            final long stopPosition,
            final int initialTermId,
            final int termBufferLength,
            final int mtuLength)
        {
            this.newSessionId = newSessionId;
            this.recordingId = recordingId;
            this.streamId = streamId;
            this.stopPosition = stopPosition;
            this.initialTermId = initialTermId;
            this.termBufferLength = termBufferLength;
            this.mtuLength = mtuLength;
        }

        public String toString()
        {
            return "LibraryExtendPosition{" +
                "newSessionId=" + newSessionId +
                ", recordingId=" + recordingId +
                ", streamId=" + streamId +
                ", stopPosition=" + stopPosition +
                ", initialTermId=" + initialTermId +
                ", termBufferLength=" + termBufferLength +
                ", mtuLength=" + mtuLength +
                '}';
        }
    }

}
