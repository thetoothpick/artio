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
package uk.co.real_logic.artio.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MutableAsciiBufferTest
{

    public static int[][] valuesAndLengths()
    {
        return new int[][]
        {
            {1, 1},
            {10, 2},
            {100, 3},
            {1000, 4},
            {12, 2},
            {123, 3},
            {2345, 4},
            {9, 1},
            {99, 2},
            {999, 3},
            {9999, 4},
        };
    }

    @ParameterizedTest
    @MethodSource(value = "valuesAndLengths")
    public void shouldCalculateCorrectAsciiLength(final int[] valueAndLength)
    {
        final int value = valueAndLength[0];
        final int length = valueAndLength[1];
        assertEquals(length, MutableAsciiBuffer.lengthInAscii(value), "Wrong length for " + value);
    }

}
