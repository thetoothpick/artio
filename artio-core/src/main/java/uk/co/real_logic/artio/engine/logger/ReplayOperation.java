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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.Aeron;
import io.aeron.ControlledFragmentAssembler;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveException;
import io.aeron.archive.status.RecordingPos;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.status.CountersReader;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.util.CharFormatter;

import java.util.List;

import static io.aeron.CommonContext.IPC_CHANNEL;

/**
 * A continuable replay operation that can retried.
 *
 * Each object is single threaded, but different objects used on different threads.
 */
public class ReplayOperation
{
    private static final FragmentHandler EMPTY_FRAGMENT_HANDLER = (buffer, offset, length, header) -> {};

    private static final ThreadLocal<CharFormatter> RECORDING_RANGE_FORMATTER =
        ThreadLocal.withInitial(() -> new CharFormatter("ReplayOperation : Attempting Recording Range:" +
        " RecordingRange{" +
        "recordingId=%s" +
        ", sessionId=%s" +
        ", position=%s" +
        ", length=%s" +
        ", count=%s" +
        "}"));
    private static final ThreadLocal<CharFormatter> POLLING_REPLAY_FORMATTER =
        ThreadLocal.withInitial(() -> new CharFormatter("Polling Replay Image pos=%s"));
    private static final ThreadLocal<CharFormatter> FINISHED_FORMATTER =
        ThreadLocal.withInitial(() -> new CharFormatter("Finished with Image @ pos=%s, closed=%s, eos=%s"));
    private static final ThreadLocal<CharFormatter> MESSAGE_REPLAY_COUNT_FORMATTER =
        ThreadLocal.withInitial(() -> new CharFormatter(
        "Finished with messageTrackerCount=%s, recordingRangeCount=%s"));

    // Closing state formatters:
    private static final ThreadLocal<CharFormatter> INIT_CLOSING_FORMATTER = ThreadLocal.withInitial(() ->
        new CharFormatter("ReplayOperation:INIT_CLOSING - stopReplay id=%s"));
    private static final ThreadLocal<CharFormatter> FIND_IMAGE_CLOSING_FORMATTER = ThreadLocal.withInitial(() ->
        new CharFormatter("ReplayOperation:FIND_IMAGE_CLOSING: - id=%s,image=%s"));
    private static final ThreadLocal<CharFormatter> POLL_IMAGE_CLOSING_FORMATTER = ThreadLocal.withInitial(() ->
        new CharFormatter("ReplayOperation:POLL_IMAGE_CLOSING: - id=%s"));
    private static final ThreadLocal<CharFormatter> CLOSED_FORMATTER = ThreadLocal.withInitial(() ->
        new CharFormatter("ReplayOperation:CLOSED - id=%s"));

    private final MessageTracker messageTracker;
    private final ControlledFragmentAssembler assembler;

    private final List<RecordingRange> ranges;
    private final AeronArchive aeronArchive;
    private final ErrorHandler errorHandler;
    private final int archiveReplayStream;
    private final LogTag logTag;
    private final CountersReader countersReader;
    private final Subscription subscription;

    // fields reset for each recordingRange
    private int replayedMessages = 0;
    private RecordingRange recordingRange;
    private long replaySessionId;
    private int aeronSessionId;
    private Image image;

    private enum State
    {
        REPLAYING,
        INIT_CLOSING,
        FIND_IMAGE_CLOSING,
        POLL_IMAGE_CLOSING,
        CLOSED
    }

    private State state = State.REPLAYING;

    ReplayOperation(
        final List<RecordingRange> ranges,
        final AeronArchive aeronArchive,
        final ErrorHandler errorHandler,
        final Subscription subscription,
        final int archiveReplayStream,
        final LogTag logTag,
        final MessageTracker messageTracker)
    {
        this.messageTracker = messageTracker;
        assembler = new ControlledFragmentAssembler(this.messageTracker);

        this.ranges = ranges;
        this.aeronArchive = aeronArchive;
        this.errorHandler = errorHandler;
        this.archiveReplayStream = archiveReplayStream;
        this.logTag = logTag;

        final Aeron aeron = aeronArchive.context().aeron();
        countersReader = aeron.countersReader();
        this.subscription = subscription;
    }

    /**
     * Attempt a replay step
     *
     * @return true if complete
     */
    public boolean pollReplay()
    {
        if (state == State.REPLAYING)
        {
            return attemptReplay();
        }
        else
        {
            return attemptClose();
        }
    }


    private boolean attemptClose()
    {
        switch (state)
        {
            case INIT_CLOSING:
            {
                if (replaySessionId != 0)
                {
                    DebugLogger.log(logTag, INIT_CLOSING_FORMATTER.get(), replaySessionId);
                    try
                    {
                        aeronArchive.stopReplay(replaySessionId);
                    }
                    catch (final ArchiveException e)
                    {
                        // The replay session may have already ended before this close was called.
                        if (e.errorCode() != ArchiveException.UNKNOWN_REPLAY)
                        {
                            errorHandler.onError(e);
                        }
                    }
                    state = image == null ? State.FIND_IMAGE_CLOSING : State.POLL_IMAGE_CLOSING;

                    return false;
                }
                else
                {
                    // There isn't a current replay in progress.
                    logClosed();
                    state = State.CLOSED;
                    return true;
                }
            }

            case FIND_IMAGE_CLOSING:
            {
                // If the session disconnected rapidly after starting a short replay then it is possible for us to
                // be in a position where image == null but Aeron owns an image object that needs to be drained in
                // order for it's underlying buffers to be released.
                image = subscription.imageBySessionId(aeronSessionId);
                final int logId = image == null ? 0 : aeronSessionId;
                DebugLogger.log(logTag, FIND_IMAGE_CLOSING_FORMATTER.get(), replaySessionId, logId);
                if (image == null)
                {
                    return false;
                }

                state = State.POLL_IMAGE_CLOSING;
                return attemptClose();
            }

            case POLL_IMAGE_CLOSING:
            {
                DebugLogger.log(logTag, POLL_IMAGE_CLOSING_FORMATTER.get(), replaySessionId);
                while (!(null == image || image.isClosed() || image.isEndOfStream()))
                {
                    image.poll(EMPTY_FRAGMENT_HANDLER, Integer.MAX_VALUE);
                }
                logClosed();
                state = State.CLOSED;
                return true;
            }

            default:
                logClosed();
                return true;
        }
    }

    private void logClosed()
    {
        DebugLogger.log(logTag, CLOSED_FORMATTER.get(), replaySessionId);
    }

    private boolean attemptReplay()
    {
        if (recordingRange == null)
        {
            DebugLogger.log(logTag, "Acquiring Recording Range");
            if (ranges.isEmpty())
            {
                return true;
            }

            recordingRange = ranges.get(0);
            logRange();
            final long beginPosition = recordingRange.position;
            final long length = recordingRange.length;
            final long endPosition = beginPosition + length;
            final long recordingId = recordingRange.recordingId;

            if (archivingNotComplete(endPosition, recordingId))
            {
                DebugLogger.log(logTag, "Archiving not complete");

                // Retry on the next iteration
                recordingRange = null;
                return false;
            }
            else
            {
                ranges.remove(0);
            }

            try
            {
                replaySessionId = aeronArchive.startReplay(
                    recordingId,
                    beginPosition,
                    length,
                    IPC_CHANNEL,
                    archiveReplayStream);
                aeronSessionId = (int)replaySessionId;

                messageTracker.reset();

                // reset the image if the new recordingRange requires it
                if (image != null && aeronSessionId != image.sessionId())
                {
                    image = null;
                }
            }
            catch (final Throwable exception)
            {
                errorHandler.onError(exception);

                return true;
            }
        }

        if (image == null)
        {
            DebugLogger.log(logTag, "Acquiring Replay Image");

            image = subscription.imageBySessionId(aeronSessionId);

            return false;
        }
        else
        {
            if (DebugLogger.isEnabled(logTag))
            {
                DebugLogger.log(logTag, POLLING_REPLAY_FORMATTER.get().clear().with(image.position()));
            }

            image.controlledPoll(assembler, Integer.MAX_VALUE);

            final int messageTrackerCount = messageTracker.count;
            final int recordingRangeCount = recordingRange.count;

            final boolean closed = image.isClosed();
            final boolean endOfStream = image.isEndOfStream();
            if (closed || endOfStream)
            {
                return onEndOfImage(recordingRangeCount, closed, endOfStream);
            }

            // Have we finished this range?
            if (messageTrackerCount < recordingRangeCount)
            {
                return false;
            }
            else
            {
                return onReachedMessageReplayCount(messageTrackerCount, recordingRangeCount);
            }
        }
    }

    private void logRange()
    {
        final LogTag logTag = this.logTag;

        if (DebugLogger.isEnabled(logTag))
        {
            final RecordingRange recordingRange = this.recordingRange;
            DebugLogger.log(logTag, ReplayOperation.RECORDING_RANGE_FORMATTER.get()
                .clear()
                .with(recordingRange.recordingId)
                .with(recordingRange.sessionId)
                .with(recordingRange.position)
                .with(recordingRange.length)
                .with(recordingRange.count));
        }
    }

    private boolean onReachedMessageReplayCount(final int messageTrackerCount, final int recordingRangeCount)
    {
        DebugLogger.log(
            logTag,
            MESSAGE_REPLAY_COUNT_FORMATTER.get(),
            messageTrackerCount,
            recordingRangeCount);

        replayedMessages += recordingRangeCount;
        recordingRange = null;

        return ranges.isEmpty();
    }

    private boolean onEndOfImage(final int recordingRangeCount, final boolean closed, final boolean endOfStream)
    {
        if (DebugLogger.isEnabled(logTag))
        {
            DebugLogger.log(logTag, FINISHED_FORMATTER.get().clear()
                .with(image.position()).with(closed).with(endOfStream));
        }

        aeronSessionId = 0;
        replaySessionId = 0;
        replayedMessages += recordingRangeCount;
        recordingRange = null;
        image = null;

        return ranges.isEmpty();
    }

    int replayedMessages()
    {
        return replayedMessages;
    }

    private boolean archivingNotComplete(final long endPosition, final long recordingId)
    {
        final int counterId = RecordingPos.findCounterIdByRecording(countersReader, recordingId);

        // wait if the recording is active - otherwise assume that the recording has complete.
        if (counterId != CountersReader.NULL_COUNTER_ID)
        {
            final long counterPosition = countersReader.getCounterValue(counterId);
            return counterPosition < endPosition;
        }

        return false;
    }

    public void startClose()
    {
        state = State.INIT_CLOSING;
    }

    // Close the session immediately. Can leave open images that will be cleaned up by it's parent.
    public void closeNow()
    {
        startClose();
        attemptClose();
    }
}
