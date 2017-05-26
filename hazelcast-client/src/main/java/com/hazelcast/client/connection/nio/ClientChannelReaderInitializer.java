/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.client.connection.nio;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.util.ClientMessageChannelInboundHandler;
import com.hazelcast.internal.networking.ChannelInboundHandler;
import com.hazelcast.internal.networking.ChannelReader;
import com.hazelcast.internal.networking.ChannelReaderInitializer;
import com.hazelcast.nio.IOUtil;

import java.io.IOException;

class ClientChannelReaderInitializer implements ChannelReaderInitializer<ClientConnection> {

    private final int bufferSize;
    private final boolean direct;

    ClientChannelReaderInitializer(int bufferSize, boolean direct) {
        this.bufferSize = bufferSize;
        this.direct = direct;
    }

    @Override
    public void init(final ClientConnection connection, ChannelReader reader) throws IOException {
        reader.initInputBuffer(IOUtil.newByteBuffer(bufferSize, direct));

        ChannelInboundHandler inboundHandler = new ClientMessageChannelInboundHandler(reader.getNormalFramesReadCounter(),
                new ClientMessageChannelInboundHandler.MessageHandler() {
                    @Override
                    public void handleMessage(ClientMessage message) {
                        connection.handleClientMessage(message);
                    }
                });
        reader.setInboundHandler(inboundHandler);
    }
}
