/*
 * Copyright 2015-2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.sbe_util;

import uk.MessageSchemaLocation;
import uk.co.real_logic.sbe.ir.Ir;
import uk.co.real_logic.sbe.ir.IrEncoder;
import uk.co.real_logic.sbe.xml.IrGenerator;
import uk.co.real_logic.sbe.xml.MessageSchema;
import uk.co.real_logic.sbe.xml.ParserOptions;
import uk.co.real_logic.sbe.xml.XmlSchemaParser;

import java.io.InputStream;
import java.nio.ByteBuffer;

public class MessageSchemaIr
{
    private static final String PATH = "../message-schema.xml";
    public static final ByteBuffer SCHEMA_BUFFER = encodeSchema();

    private static ByteBuffer encodeSchema()
    {
        final ByteBuffer byteBuffer = ByteBuffer.allocate(64 * 1024);
        try (final InputStream in = MessageSchemaLocation.class.getResourceAsStream(PATH))
        {
            final MessageSchema schema = XmlSchemaParser.parse(in, ParserOptions.DEFAULT);
            final Ir ir = new IrGenerator().generate(schema);
            try (final IrEncoder irEncoder = new IrEncoder(byteBuffer, ir))
            {
                irEncoder.encode();
            }
            byteBuffer.flip();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
        return byteBuffer;
    }
}