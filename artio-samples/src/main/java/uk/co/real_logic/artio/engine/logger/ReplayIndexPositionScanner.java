package uk.co.real_logic.artio.engine.logger;

import io.aeron.Aeron;
import org.agrona.collections.Long2LongHashMap;
import uk.co.real_logic.artio.CommonConfiguration;

import java.io.File;

import static uk.co.real_logic.artio.engine.logger.ReplayQuery.aggregateLowerPosition;

public final class ReplayIndexPositionScanner
{
    public static void main(final String[] args)
    {
        final Long2LongHashMap recordingIdToNewStartPosition = new Long2LongHashMap(Aeron.NULL_VALUE);

        printFiles(args[0], CommonConfiguration.DEFAULT_INBOUND_LIBRARY_STREAM, "inbound",
            recordingIdToNewStartPosition);
        printFiles(args[0], CommonConfiguration.DEFAULT_OUTBOUND_LIBRARY_STREAM, "outbound",
            recordingIdToNewStartPosition);

        System.out.println("Aggregated recordingIdToNewStartPosition = " + recordingIdToNewStartPosition);
    }

    private static void printFiles(
        final String logFilePath,
        final int streamId,
        final String direction,
        final Long2LongHashMap aggregateRecordingIdToNewStartPosition)
    {
        final File logFileDir = new File(logFilePath);
        System.out.println("Scanning: " + direction);

        final long[] maxPosition = new long[]{0};

        ReplayIndexDescriptor.listReplayIndexSessionIds(logFileDir, streamId)
            .stream()
            .sorted()
            .forEach(sessionId ->
            {
                final File file = ReplayIndexDescriptor.replayIndexFile(logFilePath, sessionId, streamId);
                final ReplayIndexExtractor.StartPositionExtractor positionExtractor =
                    new ReplayIndexExtractor.StartPositionExtractor();
                ReplayIndexExtractor.extract(file, positionExtractor);

                System.out.println("file = " + file);
                System.out.println("positionExtractor.highestSequenceIndex() = " +
                    positionExtractor.highestSequenceIndex());
                final Long2LongHashMap recordingIdToStartPosition = positionExtractor.recordingIdToStartPosition();
                System.out.println("positionExtractor.recordingIdToStartPosition() = " +
                    recordingIdToStartPosition);

                aggregateLowerPosition(recordingIdToStartPosition, aggregateRecordingIdToNewStartPosition);

                final ReplayIndexExtractor.BoundaryPositionExtractor boundaryPositionExtractor =
                    new ReplayIndexExtractor.BoundaryPositionExtractor(false);
                ReplayIndexExtractor.extract(file, boundaryPositionExtractor);
                final Long2LongHashMap recordingIdToMaxPosition = boundaryPositionExtractor.recordingIdToPosition();
                System.out.println("boundaryPositionExtractor = " + recordingIdToMaxPosition);

                final Long2LongHashMap.ValueIterator it = recordingIdToMaxPosition.values().iterator();
                if (it.hasNext())
                {
                    final long position = it.nextValue();
                    maxPosition[0] = Math.max(maxPosition[0], position);
                }

                boundaryPositionExtractor.findInconsistentSequenceIndexPositions();
            });

        System.out.println("maxPosition = " + maxPosition[0]);
        System.out.println("\n\n");
    }
}
