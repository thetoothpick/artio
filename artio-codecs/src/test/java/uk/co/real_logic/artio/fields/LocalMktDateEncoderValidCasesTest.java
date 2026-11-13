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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.fields.LocalMktDateDecoderValidCasesTest.toLocalDay;
import static uk.co.real_logic.artio.util.CustomMatchers.sequenceEqualsAscii;

public class LocalMktDateEncoderValidCasesTest
{
    private final MutableAsciiBuffer timestampBytes = new MutableAsciiBuffer(new byte[LocalMktDateEncoder.LENGTH]);

    private int localDays;

    public static Stream<Arguments> data()
    {
        return LocalMktDateDecoderValidCasesTest.data();
    }

    private void prepare(final String timestamp)
    {
        localDays = toLocalDay(timestamp);
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void canParseTimestamp(final String timestamp)
    {
        prepare(timestamp);
        LocalMktDateEncoder.encode(localDays, timestampBytes, 0);

        assertThat(timestampBytes, sequenceEqualsAscii(timestamp, 0, LocalMktDateEncoder.LENGTH));
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void canParseTimestampFromByteArray(final String timestamp)
    {
        prepare(timestamp);
        final LocalMktDateEncoder encoder = new LocalMktDateEncoder();
        final int length = encoder.encode(localDays, timestampBytes.byteArray());
        assertEquals(LocalMktDateEncoder.LENGTH, length);

        assertThat(timestampBytes, sequenceEqualsAscii(timestamp, 0, LocalMktDateEncoder.LENGTH));
    }
}
