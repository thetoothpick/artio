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

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;
import uk.co.real_logic.artio.dictionary.generation.CodecUtil;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.of;
import static uk.co.real_logic.artio.util.MutableAsciiBuffer.LONGEST_FLOAT_LENGTH;

public class DecimalFloatEncodingTest
{
    public static Stream<Arguments> decimalFloatCodecData()
    {
        return Stream.of(
          of("55.36", 5536L, 2),
            of("0.995", 995L, 3),
            of("25", 25L, 0),
            of("-55.36", -5536L, 2),
            of("-0.995", -995L, 3),
            of("-25", -25L, 0),
            of("0.6", 6L, 1),
            of("-0.6", -6L, 1),
            of("10", 10L, 0),
            of("-10", -10L, 0),

            of("0.92117125", 92117125L, 8),
            of("-0.92117125", -92117125L, 8),
            of("0.92125", 92125L, 5),
            of("-0.92125", -92125L, 5),
            of("0.00007875", 7875, 8),
            of("-0.00007875", -7875, 8),
            of("1.00109125", 100109125, 8),
            of("-1.00109125", -100109125, 8),

            // negative scale
            of("26000", 26L, -3),
            of("-26000", -26L, -3),

            // edge values
            of("0.000000000000000003", 3, 18),
            of("123456789012345678", 123456789012345678L, 0),
            of("-123456789012345678", -123456789012345678L, 0),
            of("0.123456789012345678", 123456789012345678L, 18),
            of("-0.123456789012345678", -123456789012345678L, 18),
            of("1.23456789012345678", 123456789012345678L, 17),
            of("-1.23456789012345678", -123456789012345678L, 17),

            // zero values
            of("0", 0, 0),
            of("0.000", 0, 3),
            of("0", 0, 4),
            of("0", 0, -5),

            // trailing zeros
            of("12.7460", 127460, 4),
            of("-12.7460", -127460, 4),
            of("0.03400", 3400, 5),
            of("-0.03400", -3400, 5),
            of("400", 40L, -1),
            of("-400", -40L, -1),

            // same positive value, range scale -2 to 19
            of("7400", 74, -2),
            of("740", 74, -1),
            of("74", 74, 0),
            of("7.4", 74, 1),
            of("0.74", 74, 2),
            of("0.074", 74, 3),
            of("0.0074", 74, 4),
            of("0.00074", 74, 5),
            of("0.000074", 74, 6),
            of("0.0000074", 74, 7),
            of("0.00000074", 74, 8),
            of("0.000000074", 74, 9),
            of("0.0000000074", 74, 10),
            of("0.00000000074", 74, 11),
            of("0.000000000074", 74, 12),
            of("0.0000000000074", 74, 13),
            of("0.00000000000074", 74, 14),
            of("0.000000000000074", 74, 15),
            of("0.0000000000000074", 74, 16),
            of("0.00000000000000074", 74, 17),
            of("0.000000000000000074", 74, 18),
            of("0.0000000000000000074", 74, 19),

            // same negative value, range scale -2 to 19
            of("-53700", -537, -2),
            of("-5370", -537, -1),
            of("-537", -537, 0),
            of("-53.7", -537, 1),
            of("-5.37", -537, 2),
            of("-0.537", -537, 3),
            of("-0.0537", -537, 4),
            of("-0.00537", -537, 5),
            of("-0.000537", -537, 6),
            of("-0.0000537", -537, 7),
            of("-0.00000537", -537, 8),
            of("-0.000000537", -537, 9),
            of("-0.0000000537", -537, 10),
            of("-0.00000000537", -537, 11),
            of("-0.000000000537", -537, 12),
            of("-0.0000000000537", -537, 13),
            of("-0.00000000000537", -537, 14),
            of("-0.000000000000537", -537, 15),
            of("-0.0000000000000537", -537, 16),
            of("-0.00000000000000537", -537, 17),
            of("-0.000000000000000537", -537, 18),
            of("-0.0000000000000000537", -537, 19)
        );
    }

    private boolean isExpectedOutputContainDecimalPoint(final String input)
    {
        return input.indexOf('.') >= 0;
    }

    private boolean isExpectedOutputContainTrailingZeros(final String input)
    {
        if (isExpectedOutputContainDecimalPoint(input))
        {
            final String trimmed = input.trim();
            return (trimmed.charAt(trimmed.length() - 1) == '0');
        }
        return false;
    }

    @ParameterizedTest
    @MethodSource(value = "decimalFloatCodecData")
    public void canEncodeDecimalFloat(final String input, final long value, final int scale)
    {
        // ignoring test since expected output has Trailing Zeros
        if (isExpectedOutputContainTrailingZeros(input))
        {
            return;
        }

        final int length = input.length();
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[LONGEST_FLOAT_LENGTH]);
        final MutableAsciiBuffer string = new MutableAsciiBuffer(buffer);
        final DecimalFloat price = new DecimalFloat(value, scale);

        final int encodedLength = string.putFloatAscii(1, price);

        assertEquals(input, string.getAscii(1, length));
        assertEquals(length, encodedLength);
    }

    @ParameterizedTest
    @MethodSource(value = "decimalFloatCodecData")
    public void canEncodeValueAndScale(final String input, final long value, final int scale)
    {
        // ignoring test since expected output has no Trailing Zeros for input value 0 (with positive scale)
        if (value == 0 && scale > 0 && !isExpectedOutputContainTrailingZeros(input))
        {
            return;
        }

        final int length = input.length();
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[LONGEST_FLOAT_LENGTH]);
        final MutableAsciiBuffer string = new MutableAsciiBuffer(buffer);

        final int encodedLength = string.putFloatAscii(1, value, scale);

        assertEquals(input, string.getAscii(1, length));
        assertEquals(length, encodedLength);
    }

    @ParameterizedTest
    @MethodSource(value = "decimalFloatCodecData")
    public void canFormatToString(final String input, final long value, final int scale)
    {
        // ignoring test since expected output has Trailing Zeros
        if (isExpectedOutputContainTrailingZeros(input))
        {
            return;
        }

        final DecimalFloat price = new DecimalFloat(value, scale);
        final StringBuilder builder = new StringBuilder();
        CodecUtil.appendFloat(builder, price);

        assertEquals(input, builder.toString());
    }
}
