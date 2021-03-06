/*
 * Copyright 2014-2020 Real Logic Limited.
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
package io.aeron;

import io.aeron.command.*;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.broadcast.CopyBroadcastReceiver;

import static io.aeron.ErrorCode.CHANNEL_ENDPOINT_ERROR;
import static io.aeron.command.ControlProtocolEvents.*;

/**
 * Analogue of {@link DriverProxy} on the client side for dispatching driver events to the client conductor.
 */
class DriverEventsAdapter implements MessageHandler
{
    private final ErrorResponseFlyweight errorResponse = new ErrorResponseFlyweight();
    private final PublicationBuffersReadyFlyweight publicationReady = new PublicationBuffersReadyFlyweight();
    private final SubscriptionReadyFlyweight subscriptionReady = new SubscriptionReadyFlyweight();
    private final ImageBuffersReadyFlyweight imageReady = new ImageBuffersReadyFlyweight();
    private final OperationSucceededFlyweight operationSucceeded = new OperationSucceededFlyweight();
    private final ImageMessageFlyweight imageMessage = new ImageMessageFlyweight();
    private final CounterUpdateFlyweight counterUpdate = new CounterUpdateFlyweight();
    private final ClientTimeoutFlyweight clientTimeout = new ClientTimeoutFlyweight();
    private final LongHashSet asyncCommandIdSet;
    private final CopyBroadcastReceiver receiver;
    private final DriverEventsListener listener;
    private final long clientId;

    private long activeCorrelationId;
    private long receivedCorrelationId;
    private boolean isInvalid;

    DriverEventsAdapter(
        final CopyBroadcastReceiver receiver,
        final long clientId,
        final DriverEventsListener listener,
        final LongHashSet asyncCommandIdSet)
    {
        this.receiver = receiver;
        this.clientId = clientId;
        this.listener = listener;
        this.asyncCommandIdSet = asyncCommandIdSet;
    }

    int receive(final long activeCorrelationId)
    {
        this.activeCorrelationId = activeCorrelationId;
        this.receivedCorrelationId = Aeron.NULL_VALUE;

        try
        {
            return receiver.receive(this);
        }
        catch (final IllegalStateException ex)
        {
            isInvalid = true;
            throw ex;
        }
    }

    long receivedCorrelationId()
    {
        return receivedCorrelationId;
    }

    boolean isInvalid()
    {
        return isInvalid;
    }

    long clientId()
    {
        return clientId;
    }

    @SuppressWarnings("MethodLength")
    public void onMessage(final int msgTypeId, final MutableDirectBuffer buffer, final int index, final int length)
    {
        switch (msgTypeId)
        {
            case ON_ERROR:
            {
                errorResponse.wrap(buffer, index);

                final int correlationId = (int)errorResponse.offendingCommandCorrelationId();
                final int errorCodeValue = errorResponse.errorCodeValue();
                final ErrorCode errorCode = ErrorCode.get(errorCodeValue);
                boolean notProcessed = true;

                if (CHANNEL_ENDPOINT_ERROR == errorCode)
                {
                    notProcessed = false;
                    listener.onChannelEndpointError(correlationId, errorResponse.errorMessage());
                }
                else if (correlationId == activeCorrelationId)
                {
                    notProcessed = false;
                    receivedCorrelationId = correlationId;
                    listener.onError(correlationId, errorCodeValue, errorCode, errorResponse.errorMessage());
                }

                if (asyncCommandIdSet.remove(correlationId) && notProcessed)
                {
                    listener.onAsyncError(correlationId, errorCodeValue, errorCode, errorResponse.errorMessage());
                }
                break;
            }

            case ON_AVAILABLE_IMAGE:
            {
                imageReady.wrap(buffer, index);

                listener.onAvailableImage(
                    imageReady.correlationId(),
                    imageReady.sessionId(),
                    imageReady.subscriptionRegistrationId(),
                    imageReady.subscriberPositionId(),
                    imageReady.logFileName(),
                    imageReady.sourceIdentity());
                break;
            }

            case ON_PUBLICATION_READY:
            {
                publicationReady.wrap(buffer, index);

                final long correlationId = publicationReady.correlationId();
                if (correlationId == activeCorrelationId)
                {
                    receivedCorrelationId = correlationId;
                    listener.onNewPublication(
                        correlationId,
                        publicationReady.registrationId(),
                        publicationReady.streamId(),
                        publicationReady.sessionId(),
                        publicationReady.publicationLimitCounterId(),
                        publicationReady.channelStatusCounterId(),
                        publicationReady.logFileName());
                }
                break;
            }

            case ON_SUBSCRIPTION_READY:
            {
                subscriptionReady.wrap(buffer, index);

                final long correlationId = subscriptionReady.correlationId();
                if (correlationId == activeCorrelationId)
                {
                    receivedCorrelationId = correlationId;
                    listener.onNewSubscription(correlationId, subscriptionReady.channelStatusCounterId());
                }
                break;
            }

            case ON_OPERATION_SUCCESS:
            {
                operationSucceeded.wrap(buffer, index);

                final long correlationId = operationSucceeded.correlationId();
                asyncCommandIdSet.remove(correlationId);
                if (correlationId == activeCorrelationId)
                {
                    receivedCorrelationId = correlationId;
                }
                break;
            }

            case ON_UNAVAILABLE_IMAGE:
            {
                imageMessage.wrap(buffer, index);

                listener.onUnavailableImage(
                    imageMessage.correlationId(),
                    imageMessage.subscriptionRegistrationId());
                break;
            }

            case ON_EXCLUSIVE_PUBLICATION_READY:
            {
                publicationReady.wrap(buffer, index);

                final long correlationId = publicationReady.correlationId();
                if (correlationId == activeCorrelationId)
                {
                    receivedCorrelationId = correlationId;
                    listener.onNewExclusivePublication(
                        correlationId,
                        publicationReady.registrationId(),
                        publicationReady.streamId(),
                        publicationReady.sessionId(),
                        publicationReady.publicationLimitCounterId(),
                        publicationReady.channelStatusCounterId(),
                        publicationReady.logFileName());
                }
                break;
            }

            case ON_COUNTER_READY:
            {
                counterUpdate.wrap(buffer, index);

                final int counterId = counterUpdate.counterId();
                final long correlationId = counterUpdate.correlationId();
                if (correlationId == activeCorrelationId)
                {
                    receivedCorrelationId = correlationId;
                    listener.onNewCounter(correlationId, counterId);
                }
                else
                {
                    listener.onAvailableCounter(correlationId, counterId);
                }
                break;
            }

            case ON_UNAVAILABLE_COUNTER:
            {
                counterUpdate.wrap(buffer, index);

                listener.onUnavailableCounter(counterUpdate.correlationId(), counterUpdate.counterId());
                break;
            }

            case ON_CLIENT_TIMEOUT:
            {
                clientTimeout.wrap(buffer, index);

                if (clientTimeout.clientId() == clientId)
                {
                    listener.onClientTimeout();
                }
                break;
            }
        }
    }
}
