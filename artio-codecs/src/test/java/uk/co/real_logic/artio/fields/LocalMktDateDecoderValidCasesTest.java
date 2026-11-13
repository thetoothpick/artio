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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.of;

public class LocalMktDateDecoderValidCasesTest
{
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    static int toLocalDay(final String timestamp)
    {
        final LocalDate parsedDate = LocalDate.parse(timestamp, FORMATTER);
        return (int)parsedDate.getLong(ChronoField.EPOCH_DAY);
    }

    public static Stream<Arguments> data()
    {
        return Stream.of(
            of("00010101"),
            of("20150225"),
            of("00010101"),
            of("20150225"),
            of("99991231")
        );
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void canParseTimestamp(final String timestamp)
    {
        final int expected = toLocalDay(timestamp);

        final LocalMktDateDecoder decoder = new LocalMktDateDecoder();
        final int epochDay = decoder.decode(timestamp.getBytes(US_ASCII));
        assertEquals(expected, epochDay, "Failed testcase for: " + timestamp);
    }
}
