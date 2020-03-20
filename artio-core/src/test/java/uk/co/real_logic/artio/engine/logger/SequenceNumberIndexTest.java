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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.Aeron;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import uk.co.real_logic.artio.CloseChecker;
import uk.co.real_logic.artio.FileSystemCorruptionException;
import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.engine.framer.FakeEpochClock;

import java.io.File;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static org.agrona.IoUtil.deleteIfExists;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static uk.co.real_logic.artio.TestFixtures.cleanupMediaDriver;
import static uk.co.real_logic.artio.TestFixtures.largeTestReqId;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_INDEX_FILE_STATE_FLUSH_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.engine.SectorFramer.SECTOR_SIZE;
import static uk.co.real_logic.artio.engine.SessionInfo.UNK_SESSION;
import static uk.co.real_logic.artio.engine.logger.ErrorHandlerVerifier.verify;
import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexDescriptor.*;
import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexWriter.SEQUENCE_NUMBER_OFFSET;

public class SequenceNumberIndexTest extends AbstractLogTest
{
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final String INDEX_FILE_PATH = IoUtil.tmpDirName() + "/SequenceNumberIndex";

    private AtomicBuffer inMemoryBuffer = newBuffer();

    private ErrorHandler errorHandler = mock(ErrorHandler.class);
    private SequenceNumberIndexWriter writer;
    private SequenceNumberIndexReader reader;
    private FakeEpochClock clock = new FakeEpochClock();

    private ArchivingMediaDriver mediaDriver;
    private AeronArchive aeronArchive;
    private Publication publication;
    private Subscription subscription;
    private RecordingIdLookup recordingIdLookup;

    @Before
    public void setUp()
    {
        mediaDriver = launchMediaDriver();
        aeronArchive = AeronArchive.connect();
        final Aeron aeron = aeronArchive.context().aeron();

        aeronArchive.startRecording(IPC_CHANNEL, STREAM_ID, SourceLocation.LOCAL);

        publication = aeron.addPublication(IPC_CHANNEL, STREAM_ID);
        subscription = aeron.addSubscription(IPC_CHANNEL, STREAM_ID);

        buffer = new UnsafeBuffer(new byte[512]);

        deleteFiles();

        recordingIdLookup = new RecordingIdLookup(new YieldingIdleStrategy(), aeron.countersReader());
        writer = newWriter(inMemoryBuffer);
        reader = new SequenceNumberIndexReader(inMemoryBuffer, errorHandler, recordingIdLookup, null);
    }

    @After
    public void tearDown()
    {
        CloseHelper.close(writer);
        deleteFiles();

        verify(errorHandler, never()).onError(any());

        CloseHelper.close(aeronArchive);
        cleanupMediaDriver(mediaDriver);

        Mockito.framework().clearInlineMocks();
    }

    @Test
    public void shouldNotInitiallyKnowASequenceNumber()
    {
        assertUnknownSession();
    }

    @Test
    public void shouldStashNewSequenceNumber()
    {
        indexFixMessage();

        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER);
    }

    @Test
    public void shouldStashNewSequenceNumberForLargeMessage()
    {
        final long position = indexLargeFixMessage();

        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER);

        assertEquals(position, reader.indexedPosition(publication.sessionId()));
    }

    @Test
    public void shouldStashSequenceNumbersAgainstASessionId()
    {
        indexFixMessage();

        assertLastKnownSequenceNumberIs(SESSION_ID_2, UNK_SESSION);
    }

    @Test
    public void shouldUpdateSequenceNumber()
    {
        final int updatedSequenceNumber = 8;

        indexFixMessage();

        bufferContainsExampleMessage(true, SESSION_ID, updatedSequenceNumber, SEQUENCE_INDEX);

        indexRecord();

        assertLastKnownSequenceNumberIs(SESSION_ID, updatedSequenceNumber);
    }

    @Test
    public void shouldValidateBufferItReadsFrom()
    {
        final AtomicBuffer tableBuffer = newBuffer();

        new SequenceNumberIndexReader(tableBuffer, errorHandler,
            recordingIdLookup,
            null);

        verify(errorHandler, times(1), IllegalStateException.class);
    }

    @Test
    public void shouldSaveIndexUponClose()
    {
        indexFixMessage();

        writer.close();

        final SequenceNumberIndexReader newReader = newInstanceAfterRestart();
        assertEquals(alignedEndPosition(), newReader.indexedPosition(publication.sessionId()));
    }

    @Test
    public void shouldRecordIndexedPosition()
    {
        indexFixMessage();

        writer.close();

        final SequenceNumberIndexReader newReader = newInstanceAfterRestart();
        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER, newReader);
    }

    @Test
    public void shouldFlushIndexFileOnTimeout()
    {
        try
        {
            indexFixMessage();

            assertEquals(0, writer.doWork());

            clock.advanceMilliSeconds(DEFAULT_INDEX_FILE_STATE_FLUSH_TIMEOUT_IN_MS + 1);

            assertEquals(1, writer.doWork());

            final SequenceNumberIndexReader newReader = newInstanceAfterRestart();
            assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER, newReader);
        }
        finally
        {
            writer.close();
        }
    }

    /**
     * Simulate scenario that you've crashed halfway through file flip.
     */
    @Test
    public void shouldAccountForPassingPlaceFile()
    {
        indexFixMessage();

        writer.close();

        // TODO: check that the passing place is used

        //assertTrue("Failed to recreate crash scenario", new File(INDEX_FILE_PATH).renameTo(writer.passingPlace()));

        final SequenceNumberIndexReader newReader = newInstanceAfterRestart();
        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER, newReader);
    }

    @Test
    public void shouldChecksumFileToDetectCorruption()
    {
        indexFixMessage();

        writer.close();

        corruptIndexFile(SEQUENCE_NUMBER_OFFSET, SECTOR_SIZE / 2);

        newInstanceAfterRestart();

        final ArgumentCaptor<FileSystemCorruptionException> exception =
            ArgumentCaptor.forClass(FileSystemCorruptionException.class);
        verify(errorHandler).onError(exception.capture());
        assertThat(
            exception.getValue().getMessage(),
            Matchers.containsString("The SequenceNumberIndex file is corrupted"));
        reset(errorHandler);
    }

    @Test
    public void shouldValidateHeader()
    {
        indexFixMessage();

        writer.close();

        corruptIndexFile(0, SequenceNumberIndexDescriptor.HEADER_SIZE);

        newInstanceAfterRestart();

        verify(errorHandler, times(2), IllegalStateException.class);
    }

    private void corruptIndexFile(final int from, final int length)
    {
        try (MappedFile mappedFile = newIndexFile())
        {
            mappedFile.buffer().putBytes(from, new byte[length]);
        }
    }

    @Test
    public void shouldSaveIndexUponRotate()
    {
        final int requiredMessagesToRoll = 18724;
        for (int i = 0; i <= requiredMessagesToRoll; i++)
        {
            bufferContainsExampleMessage(true, SESSION_ID, SEQUENCE_NUMBER + i, SEQUENCE_INDEX);
            indexRecord();
        }

        try (MappedFile mappedFile = newIndexFile())
        {
            final SequenceNumberIndexReader newReader = new SequenceNumberIndexReader(
                mappedFile.buffer(), errorHandler, recordingIdLookup, null);

            assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER + requiredMessagesToRoll, newReader);
        }
    }

    @Test
    public void shouldAlignMessagesAndNotOverlapCheckSums()
    {
        final int initialSequenceNumber = 1;
        final int sequenceNumberDiff = 3;
        final int recordsOverlappingABlock = SECTOR_SIZE / RECORD_SIZE + 1;
        for (int i = initialSequenceNumber; i <= recordsOverlappingABlock; i++)
        {
            bufferContainsExampleMessage(true, i, i + sequenceNumberDiff, SEQUENCE_INDEX);
            indexRecord();
        }

        writer.close();

        final SequenceNumberIndexReader newReader = newInstanceAfterRestart();
        for (int i = initialSequenceNumber; i <= recordsOverlappingABlock; i++)
        {
            assertLastKnownSequenceNumberIs(i, i + sequenceNumberDiff, newReader);
        }
    }

    @Test
    public void shouldResetSequenceNumbers()
    {
        indexFixMessage();

        writer.resetSequenceNumbers();

        assertUnknownSession();
    }

    private SequenceNumberIndexReader newInstanceAfterRestart()
    {
        final AtomicBuffer inMemoryBuffer = newBuffer();
        newWriter(inMemoryBuffer).close();
        return new SequenceNumberIndexReader(inMemoryBuffer, errorHandler, recordingIdLookup, null);
    }

    private SequenceNumberIndexWriter newWriter(final AtomicBuffer inMemoryBuffer)
    {
        final MappedFile indexFile = newIndexFile();
        return new SequenceNumberIndexWriter(inMemoryBuffer, indexFile, errorHandler, STREAM_ID, recordingIdLookup,
            DEFAULT_INDEX_FILE_STATE_FLUSH_TIMEOUT_IN_MS, clock, null,
            new Long2LongHashMap(UNK_SESSION));
    }

    private MappedFile newIndexFile()
    {
        return MappedFile.map(INDEX_FILE_PATH, BUFFER_SIZE);
    }

    private UnsafeBuffer newBuffer()
    {
        return new UnsafeBuffer(new byte[BUFFER_SIZE]);
    }

    private void assertUnknownSession()
    {
        assertLastKnownSequenceNumberIs(SESSION_ID, UNK_SESSION);
    }

    private void indexFixMessage()
    {
        bufferContainsExampleMessage(true);
        indexRecord();
    }

    private long indexLargeFixMessage()
    {
        buffer = new UnsafeBuffer(new byte[BIG_BUFFER_LENGTH]);

        final String testReqId = largeTestReqId();
        bufferContainsExampleMessage(true, SESSION_ID, SEQUENCE_NUMBER, SEQUENCE_INDEX, testReqId);

        return indexRecord();
    }

    private long indexRecord()
    {
        long position = 0;
        while (position < 1)
        {
            position = publication.offer(buffer, START, fragmentLength());

            Thread.yield();
        }

        /*System.out.println("position = " + position);
        System.out.println("p = " + p);*/


        Image image = null;
        while (image == null || image.position() < position)
        {
            if (image == null)
            {
                image = subscription.imageBySessionId(publication.sessionId());
            }

            if (image != null)
            {
                image.poll(writer, 1);
            }
        }

        return position;
    }

    private void assertLastKnownSequenceNumberIs(final long sessionId, final int expectedSequenceNumber)
    {
        assertLastKnownSequenceNumberIs(sessionId, expectedSequenceNumber, reader);
    }

    private void assertLastKnownSequenceNumberIs(
        final long sessionId,
        final long expectedSequenceNumber,
        final SequenceNumberIndexReader reader)
    {
        final int number = reader.lastKnownSequenceNumber(sessionId);
        assertEquals(expectedSequenceNumber, number);
    }

    private void deleteFiles()
    {
        deleteIfExists(new File(INDEX_FILE_PATH));
        deleteIfExists(writableFile(INDEX_FILE_PATH));
        deleteIfExists(passingFile(INDEX_FILE_PATH));
    }
}
