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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.params.provider.Arguments.of;

public class LocalMktDateDecoderInvalidCasesTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            of("-0010101"),
            of("00000001"),
            of("00000100"),
            of("00001301"),
            of("00000132")
        );
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void cannotParseTimestamp(final String timestamp)
    {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
        {
            final LocalMktDateDecoder decoder = new LocalMktDateDecoder();
            decoder.decode(timestamp.getBytes(US_ASCII));
        });
    }
}
