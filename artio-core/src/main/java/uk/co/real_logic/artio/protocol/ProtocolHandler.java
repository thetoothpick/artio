/*
 * Copyright 2015-2021 Real Logic Limited.
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
package uk.co.real_logic.artio.protocol;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.MessageStatus;

public interface ProtocolHandler
{
    Action onMessage(
        DirectBuffer buffer,
        int offset,
        int length,
        int libraryId,
        long connectionId,
        long sessionId,
        int sequenceIndex,
        long messageType,
        long timestamp,
        MessageStatus status,
        int sequenceNumber,
        long position,
        int metaDataLength);

    Action onDisconnect(int libraryId, long connectionId, DisconnectReason reason);

    Action onFixPMessage(long connectionId, DirectBuffer buffer, int offset);
}
