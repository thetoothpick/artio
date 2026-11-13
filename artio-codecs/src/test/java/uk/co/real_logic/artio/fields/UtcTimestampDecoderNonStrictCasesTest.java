/*
 * Copyright 2015-2025 Real Logic Limited.
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
package uk.co.real_logic.artio.fields;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.time.temporal.ChronoField.MICRO_OF_SECOND;
import static java.time.temporal.ChronoField.MILLI_OF_SECOND;
import static java.time.temporal.ChronoField.NANO_OF_SECOND;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.of;
import static uk.co.real_logic.artio.fields.UtcTimestampDecoder.LENGTH_WITH_NANOSECONDS;

public class UtcTimestampDecoderNonStrictCasesTest
{
    private static final DateTimeFormatter FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd-HH:mm:ss")
        .appendFraction(NANO_OF_SECOND, 0, 9, true)
        .toFormatter();

    public static long toEpochMillis(final String timestamp)
    {
        final LocalDateTime parsedDate = LocalDateTime.parse(timestamp, FORMATTER);
        final ZonedDateTime utc = ZonedDateTime.of(parsedDate, ZoneId.of("UTC"));
        return SECONDS.toMillis(utc.toEpochSecond()) + utc.getLong(MILLI_OF_SECOND);
    }

    public static long toEpochMicros(final String timestamp)
    {
        final LocalDateTime parsedDate = LocalDateTime.parse(timestamp, FORMATTER);
        final ZonedDateTime utc = ZonedDateTime.of(parsedDate, ZoneId.of("UTC"));
        return SECONDS.toMicros(utc.toEpochSecond()) + utc.getLong(MICRO_OF_SECOND);
    }

    public static long toEpochNanos(final String timestamp)
    {
        final LocalDateTime parsedDate = LocalDateTime.parse(timestamp, FORMATTER);
        final ZonedDateTime utc = ZonedDateTime.of(parsedDate, ZoneId.of("UTC"));
        return SECONDS.toNanos(utc.toEpochSecond()) + utc.getLong(NANO_OF_SECOND);
    }

    private int length;
    private long expectedEpochMillis;
    private long expectedEpochMicros;
    private long expectedEpochNanos;
    private MutableAsciiBuffer buffer;

    public static Stream<Arguments> data()
    {
        return Stream.of(
            of("20150225-17:51:32"),
            of("20150225-17:51:32.1"),
            of("20600225-17:51:32.123"),
            of("20600225-17:51:32.1234"),
            of("20600225-17:51:32.12345"),
            of("20600225-17:51:32.123456"),
            of("20600225-17:51:32.1234567"),
            of("20600225-17:51:32.12345678"),
            of("20600225-17:51:32.123456789")
        );
    }

    private void prepareTimestamp(final String timestamp)
    {
        this.length = timestamp.length();

        expectedEpochMillis = toEpochMillis(timestamp);
        expectedEpochMicros = toEpochMicros(timestamp);
        expectedEpochNanos = toEpochNanos(timestamp);

        final byte[] bytes = timestamp.getBytes(US_ASCII);
        buffer = new MutableAsciiBuffer(new byte[LENGTH_WITH_NANOSECONDS + 2]);
        buffer.putBytes(1, bytes);
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void shouldParseTimestampMillis(final String timestamp)
    {
        prepareTimestamp(timestamp);
        final long epochMillis = UtcTimestampDecoder.decode(buffer, 1, length, false);
        assertEquals(expectedEpochMillis, epochMillis, "Failed Millis testcase for: " + timestamp);
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void shouldParseTimestampMicros(final String timestamp)
    {
        prepareTimestamp(timestamp);
        final long epochMicros = UtcTimestampDecoder.decodeMicros(buffer, 1, length, false);
        assertEquals(expectedEpochMicros, epochMicros,
            "Failed Micros testcase for: " + buffer.getAscii(1, length));
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void shouldParseTimestampNanos(final String timestamp)
    {
        prepareTimestamp(timestamp);
        final long epochNanos = UtcTimestampDecoder.decodeNanos(buffer, 1, length, false);
        assertEquals(expectedEpochNanos, epochNanos,
            "Failed Nanos testcase for: " + timestamp);
    }
}
