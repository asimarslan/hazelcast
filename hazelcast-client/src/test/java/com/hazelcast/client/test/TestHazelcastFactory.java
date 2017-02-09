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

package com.hazelcast.client.test;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.XmlClientConfigBuilder;
import com.hazelcast.client.connection.AddressProvider;
import com.hazelcast.client.impl.ClientConnectionManagerFactory;
import com.hazelcast.client.impl.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.HazelcastClientProxy;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.util.AddressHelper;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.OutOfMemoryErrorDispatcher;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.serialization.SerializationService;
import com.hazelcast.test.TestEnvironment;
import com.hazelcast.test.TestHazelcastInstanceFactory;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.*;

public class TestHazelcastFactory extends TestHazelcastInstanceFactory {

    private static final Set<String> allClasses;
    private static PrintWriter pw;
    static {
        try {
            pw = new PrintWriter("classNames.txt");
        } catch (FileNotFoundException e) {
            pw  = null;
            e.printStackTrace();
        }
        allClasses = newSetFromMap(new ConcurrentHashMap());
        final SerializationService serializationService = new DefaultSerializationServiceBuilder()
                .setVersion(InternalSerializationService.VERSION_1)
                .build();
        ClientMessage.processor = new ClientMessage.DataProcessor() {
            @Override
            public void process(Data data) {
                try {
                    Object object = serializationService.toObject(data);
                    if ("com.hazelcast.instance.MemberImpl".equals(object.getClass().getName())){
                        new Throwable().printStackTrace();
                    }
                    allClasses.add(object.getClass().getName());
                } catch (Exception e) {
                    //ignore
                }
            }
        };
    }

    private static final AtomicInteger CLIENT_PORTS = new AtomicInteger(40000);

    private final boolean mockNetwork = TestEnvironment.isMockNetwork();
    private final List<HazelcastClientInstanceImpl> clients = new ArrayList<HazelcastClientInstanceImpl>(10);
    private final TestClientRegistry clientRegistry;

    public TestHazelcastFactory() {
        super(0);
        this.clientRegistry = new TestClientRegistry(getRegistry());
    }

    public HazelcastInstance newHazelcastClient() {
        return newHazelcastClient(null);
    }

    public HazelcastInstance newHazelcastClient(ClientConfig config) {
        if (!mockNetwork) {
            return HazelcastClient.newHazelcastClient(config);
        }

        if (config == null) {
            config = new XmlClientConfigBuilder().build();
        }

        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        HazelcastClientProxy proxy;
        try {
            if (tccl == ClassLoader.getSystemClassLoader()) {
                Thread.currentThread().setContextClassLoader(HazelcastClient.class.getClassLoader());
            }
            ClientConnectionManagerFactory clientConnectionManagerFactory =
                    clientRegistry.createClientServiceFactory("127.0.0.1", CLIENT_PORTS);
            AddressProvider testAddressProvider = createAddressProvider(config);
            HazelcastClientInstanceImpl client =
                    new HazelcastClientInstanceImpl(config, clientConnectionManagerFactory, testAddressProvider);
            client.start();
            clients.add(client);
            OutOfMemoryErrorDispatcher.registerClient(client);
            proxy = new HazelcastClientProxy(client);
        } finally {
            Thread.currentThread().setContextClassLoader(tccl);
        }
        return proxy;
    }

    private AddressProvider createAddressProvider(ClientConfig config) {
        List<String> userConfiguredAddresses = config.getNetworkConfig().getAddresses();
        if (!userConfiguredAddresses.contains("localhost")) {
            // addresses are set explicitly, don't add more addresses
            return null;
        }

        return new AddressProvider() {
            @Override
            public Collection<InetSocketAddress> loadAddresses() {
                Collection<InetSocketAddress> inetAddresses = new ArrayList<InetSocketAddress>();
                for (Address address : getKnownAddresses()) {
                    Collection<InetSocketAddress> addresses = AddressHelper.getPossibleSocketAddresses(address.getPort(),
                            address.getHost(), 3);
                    inetAddresses.addAll(addresses);
                }
                return inetAddresses;
            }
        };
    }

    public void shutdownAllMembers() {
        super.shutdownAll();
    }

    @Override
    public void shutdownAll() {
        if (mockNetwork) {
            for (HazelcastClientInstanceImpl client : clients) {
                client.shutdown();
            }
        } else {
            // for client terminateAll() and shutdownAll() is the same
            HazelcastClient.shutdownAll();
        }
        super.shutdownAll();

        pw.println("====================== ALL CLASS NAMES ====================");
        for (String clazz : TestHazelcastFactory.allClasses) {
            pw.println(clazz);
        }
        pw.println("====================== END ================================");

        pw.flush();
    }

    @Override
    public void terminateAll() {
        if (mockNetwork) {
            for (HazelcastClientInstanceImpl client : clients) {
                client.getLifecycleService().terminate();
            }
        } else {
            // for client terminateAll() and shutdownAll() is the same
            HazelcastClient.shutdownAll();
        }
        super.terminateAll();
    }
}
