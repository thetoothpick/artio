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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.of;

public class DecimalFloatDecodingTest
{


    public static Stream<Arguments> decimalFloatCodecData()
    {
        return Stream.of(

            of("9.99999999999999999", ReadOnlyDecimalFloat.VALUE_MAX_VAL, 17),
            of("99999999999999999.9", ReadOnlyDecimalFloat.VALUE_MAX_VAL, 1),
            of("55.36", 5536L, 2),
            of("5.536e0", 5536L, 3),
            of("5.536e1", 5536L, 2),
            of("5.536e2", 5536L, 1),
            of("5.536e+2", 5536L, 1),
            of("5.536e+02", 5536L, 1),
            of("5.536e-1", 5536L, 4),
            of("5.536e-01", 5536L, 4),
            of("55.3600", 5536L, 2),
            of("1e+016", 10000000000000000L, 0),
            of("1e-016", 1L, 16),
            of("5.536e-2", 5536L, 5),
            of("5.536e-02", 5536L, 5),
            of("0055.36", 5536L, 2),
            of("0055.3600", 5536L, 2),
            of(".995", 995L, 3),
            of("0.9950", 995L, 3),
            of("25", 25L, 0),
            of("-55.36", -5536L, 2),
            of("-553.6e-1", -5536L, 2),
            of("-0055.3600", -5536L, 2),
            of("-0055.3600e0", -5536L, 2),
            of("-0055.3600e5", -5536000L, 0),
            of("-55.3600", -5536L, 2),
            of("-.995", -995L, 3),
            of("-0.9950", -995L, 3),
            of("-25", -25L, 0),
            of(".6", 6L, 1),
            of(".600", 6L, 1),
            of(".6e0", 6L, 1),
            of(".6e2", 60L, 0),
            of(".06", 6L, 2),
            of(".06e-2", 6L, 4),
            of("-.6", -6L, 1),
            of("-.6e0", -6L, 1),
            of("-.6e2", -60L, 0),
            of("-.6e-2", -6L, 3),
            of("-.6E-2", -6L, 3),
            of("-.06", -6L, 2),
            of("10", 10L, 0),
            of("-10", -10L, 0),
            of("10.", 10L, 0),
            of("-10.", -10L, 0),

            of("1.00000000", 1L, 0),
            of("-1.00000000", -1L, 0),
            of("0.92117125", 92117125L, 8),
            of("-0.92117125", -92117125L, 8),
            of("0.92125000", 92125L, 5),
            of("-0.92125000", -92125L, 5),
            of("0.00007875", 7875, 8),
            of("-0.00007875", -7875, 8),
            of("1.00109125", 100109125, 8),
            of("-1.00109125", -100109125, 8),
            of("6456.00000001", 645600000001L, 8),

            of("0.00000001", 1, 8),
            of("6456.123456789", 6456123456789L, 9),
            of("6456.000000001", 6456000000001L, 9),

            of("0", 0L, 0),
            of("00", 0L, 0),
            of("0.", 0L, 0),
            of(".0", 0L, 0),
            of("0.0", 0L, 0),
            of("00.00", 0L, 0),
            of("0e0", 0L, 0),
            of("00e00", 0L, 0),
            of("00.00e00", 0L, 0),

            of("1.0e0", 1L, 0),
            of("1.0e10", 10_000_000_000L, 0),
            of("1.0e+00010", 10_000_000_000L, 0),
            of("1.0e-10", 1L, 10),
            of("1.0e-100", 1L, 100)
        );
    }

    @ParameterizedTest
    @MethodSource(value = "decimalFloatCodecData")
    public void shouldDecodeFromString(final String input, final long value, final int scale)
    {
        final DecimalFloat price = new DecimalFloat();

        price.fromString(input);

        assertEquals(value, price.value(), "Incorrect Value");
        assertEquals(scale, price.scale(), "Incorrect Scale");
    }

    @ParameterizedTest
    @MethodSource(value = "decimalFloatCodecData")
    public void shouldDecodeFromStringWithOffset(final String input, final long value, final int scale)
    {
        final DecimalFloat price = new DecimalFloat();

        final String extendedInput = ' ' + input + ' ';
        price.fromString(extendedInput, 1, input.length());

        assertEquals(value, price.value(), "Incorrect Value");
        assertEquals(scale, price.scale(), "Incorrect Scale");
    }

    @ParameterizedTest
    @MethodSource(value = "decimalFloatCodecData")
    public void canDecodeDecimalFloat(final String input, final long value, final int scale)
    {
        final byte[] bytes = input.getBytes(US_ASCII);
        canDecodeDecimalFloatFromBytes(bytes, value, scale);
    }

    @ParameterizedTest
    @MethodSource(value = "decimalFloatCodecData")
    public void canDecodeDecimalFloatWithSpacePrefixOrSuffix(final String input, final long value, final int scale)
    {
        final String paddedInput = "  " + input + "  ";
        final byte[] bytes = paddedInput.getBytes(US_ASCII);
        canDecodeDecimalFloatFromBytes(bytes, value, scale);
    }

    private void canDecodeDecimalFloatFromBytes(final byte[] bytes, final long value, final int scale)
    {
        final MutableAsciiBuffer string = new MutableAsciiBuffer(new byte[bytes.length + 2]);
        string.putBytes(1, bytes);
        final DecimalFloat price = new DecimalFloat();

        string.getFloat(price, 1, bytes.length);

        assertEquals(value, price.value(), "Incorrect Value");
        assertEquals(scale, price.scale(), "Incorrect Scale");
    }

}
