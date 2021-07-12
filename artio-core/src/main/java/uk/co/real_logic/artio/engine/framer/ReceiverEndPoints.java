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
package uk.co.real_logic.artio.engine.framer;

import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.collections.ArrayUtil;
import org.agrona.nio.TransportPoller;
import uk.co.real_logic.artio.messages.DisconnectReason;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Arrays;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

import static org.agrona.collections.ArrayUtil.UNKNOWN_INDEX;
import static uk.co.real_logic.artio.messages.DisconnectReason.ENGINE_SHUTDOWN;

class ReceiverEndPoints extends TransportPoller
{
    private final ErrorHandler errorHandler;

    // Authentication flow requires periodic polling of the receiver end points until the authentication is
    // complete, so these endpoints are always polled, rather than using the selector.
    private ReceiverEndPoint[] requiredPollingEndPoints = new ReceiverEndPoint[0];
    private ReceiverEndPoint[] endPoints = new ReceiverEndPoint[0];
    // An endpoint that has read data out of the TCP layer but has been back-pressured when attempting to write
    // the data into the Aeron stream.
    private ReceiverEndPoint backpressuredEndPoint = null;

    ReceiverEndPoints(final ErrorHandler errorHandler)
    {
        this.errorHandler = errorHandler;
    }

    void add(final ReceiverEndPoint endPoint)
    {
        if (endPoint.requiresAuthentication())
        {
            requiredPollingEndPoints = ArrayUtil.add(requiredPollingEndPoints, endPoint);
        }
        else
        {
            addToNormalEndpoints(endPoint);
        }
    }

    private void addToNormalEndpoints(final ReceiverEndPoint endPoint)
    {
        try
        {
            endPoints = ArrayUtil.add(endPoints, endPoint);
            endPoint.register(selector);
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    void removeConnection(final long connectionId, final DisconnectReason reason)
    {
        final ReceiverEndPoint[] endPoints = this.endPoints;
        int index = findAndCloseEndPoint(connectionId, reason, endPoints);

        if (index != UNKNOWN_INDEX)
        {
            this.endPoints = ArrayUtil.remove(endPoints, index);
        }
        else
        {
            index = findAndCloseEndPoint(connectionId, reason, requiredPollingEndPoints);
            this.requiredPollingEndPoints = ArrayUtil.remove(requiredPollingEndPoints, index);
        }

        selectNowToForceProcessing();
    }

    void receiverEndPointPollingOptional(final long connectionId)
    {
        final ReceiverEndPoint[] requiredPollingEndPoints = this.requiredPollingEndPoints;
        final int index = findEndPoint(connectionId, requiredPollingEndPoints);
        if (index != UNKNOWN_INDEX)
        {
            final ReceiverEndPoint endPoint = requiredPollingEndPoints[index];
            this.requiredPollingEndPoints = ArrayUtil.remove(requiredPollingEndPoints, index);
            addToNormalEndpoints(endPoint);
        }
        else
        {
            errorHandler.onError(new Exception(String.format(
                "Unable to make endpoint no longer required for polling due to it not being found, connectionId=%d",
                connectionId)));
        }
    }

    private int findAndCloseEndPoint(
        final long connectionId,
        final DisconnectReason reason,
        final ReceiverEndPoint[] endPoints)
    {
        final int index = findEndPoint(connectionId, endPoints);
        if (index != UNKNOWN_INDEX)
        {
            endPoints[index].close(reason);
        }
        return index;
    }

    private int findEndPoint(final long connectionId, final ReceiverEndPoint[] endPoints)
    {
        int index = UNKNOWN_INDEX;
        final int length = endPoints.length;
        for (int i = 0; i < length; i++)
        {
            final ReceiverEndPoint endPoint = endPoints[i];
            if (endPoint.connectionId() == connectionId)
            {
                index = i;
                break;
            }
        }
        return index;
    }

    private void selectNowToForceProcessing()
    {
        try
        {
            selector.selectNow();
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    int pollEndPoints()
    {
        int bytesReceived = 0;
        try
        {
            final ReceiverEndPoint[] requiredPollingEndPoints = this.requiredPollingEndPoints;
            final ReceiverEndPoint backpressuredEndPoint = this.backpressuredEndPoint;
            final int numRequiredPollingEndPoints = requiredPollingEndPoints.length;

            if (backpressuredEndPoint != null)
            {
                if (backpressuredEndPoint.retryFrameMessages())
                {
                    this.backpressuredEndPoint = null;

                    bytesReceived += pollNormalEndPoints(numRequiredPollingEndPoints);
                }
            }
            else
            {
                bytesReceived += pollNormalEndPoints(numRequiredPollingEndPoints);
            }

            bytesReceived = pollArray(bytesReceived, requiredPollingEndPoints, numRequiredPollingEndPoints);
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return bytesReceived;
    }

    private int pollNormalEndPoints(final int numRequiredPollingEndPoints) throws IOException
    {
        int bytesReceived = 0;
        final ReceiverEndPoint[] endPoints = this.endPoints;
        final int numEndPoints = endPoints.length;
        final int threshold = ITERATION_THRESHOLD - numRequiredPollingEndPoints;
        if (numEndPoints <= threshold)
        {
            bytesReceived = pollArray(bytesReceived, endPoints, numEndPoints);
        }
        else
        {
            selector.selectNow();

            final SelectionKey[] keys = selectedKeySet.keys();
            final int size = selectedKeySet.size();
            int i;
            for (i = 0; i < size; i++)
            {
                final ReceiverEndPoint endPoint = (ReceiverEndPoint)keys[i].attachment();
                final int polledBytes = endPoint.poll();
                if (polledBytes < 0)
                {
                    backpressuredEndPoint = endPoint;
                    bytesReceived -= polledBytes;
                    break;
                }

                bytesReceived += polledBytes;
            }

            // check we need to reset
            if (i != 0)
            {
                selectedKeySet.reset(i);
            }
        }
        return bytesReceived;
    }

    private int pollArray(
        final int bytesAlreadyReceived, final ReceiverEndPoint[] endPoints, final int numRequiredPollingEndPoints)
    {
        int bytesReceived = bytesAlreadyReceived;
        for (int i = numRequiredPollingEndPoints - 1; i >= 0; i--)
        {
            bytesReceived += endPoints[i].poll();
        }
        return bytesReceived;
    }

    int size()
    {
        return requiredPollingEndPoints.length + endPoints.length;
    }

    void closeRequiredPollingEndPoints()
    {
        closeAll(requiredPollingEndPoints);
        requiredPollingEndPoints = new ReceiverEndPoint[0];
    }

    public void close()
    {
        closeRequiredPollingEndPoints();
        closeAll(endPoints);
        super.close();
    }

    private void closeAll(final ReceiverEndPoint[] endPoints)
    {
        Stream.of(endPoints).forEach(receiverEndPoint -> receiverEndPoint.close(ENGINE_SHUTDOWN));
    }

    public void disconnectILinkConnections(final int libraryId, final LongConsumer removeFunc)
    {
        endPoints = disconnectILinkConnections(libraryId, endPoints, removeFunc);
        requiredPollingEndPoints = disconnectILinkConnections(libraryId, requiredPollingEndPoints, removeFunc);
        selectNowToForceProcessing();
    }

    static ReceiverEndPoint[] disconnectILinkConnections(
        final int libraryId, final ReceiverEndPoint[] endPoints, final LongConsumer removeFunc)
    {
        int out = 0;
        final int length = endPoints.length;
        for (int i = 0; i < length; i++)
        {
            final ReceiverEndPoint endPoint = endPoints[i];
            if (endPoint.libraryId() == libraryId && endPoint instanceof InitiatorFixPReceiverEndPoint)
            {
                removeFunc.accept(endPoint.connectionId());
                endPoint.close(DisconnectReason.LIBRARY_DISCONNECT);
            }
            else
            {
                endPoints[out] = endPoint;
                out++;
            }
        }

        if (out < length)
        {
            return Arrays.copyOf(endPoints, out);
        }
        else
        {
            return endPoints;
        }
    }

    public String toString()
    {
        return "ReceiverEndPoints{" +
            "errorHandler=" + errorHandler +
            ", requiredPollingEndPoints=" + Arrays.toString(requiredPollingEndPoints) +
            ", endPoints=" + Arrays.toString(endPoints) +
            ", backpressuredEndPoint=" + backpressuredEndPoint +
            '}';
    }
}
