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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.of;

public class UtcTimestampDecoderInvalidCasesTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            of("-0010101-00:00:00"),
            of("00000001-00:00:00"),
            of("00000100-00:00:00"),
            of("00001301-00:00:00"),
            of("00000132-00:00:00"),
            of("00000101-24:00:00"),
            of("00000101-00:60:00"),
            of("00000101-00:00:61")
        );
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void cannotParseTimestamp(final String timestamp)
    {
        assertThrows(IllegalArgumentException.class, () ->
            new UtcTimestampDecoder(true).decode(timestamp.getBytes(US_ASCII)));
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void cannotParseTimestampMicros(final String timestamp)
    {
        assertThrows(IllegalArgumentException.class, () ->
            new UtcTimestampDecoder(true).decodeMicros(timestamp.getBytes(US_ASCII)));
    }
}
