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

package com.hazelcast.client;

import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IAtomicLong;
import com.hazelcast.core.IMap;
import com.hazelcast.spi.properties.GroupProperty;
import org.junit.Ignore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Ignore("Not a JUnit test")
public class SimpleAtomicLongTestFromClient {

    private static int threadCount = 100;
    private static int statsSeconds = 10;

    private static AtomicLong counter = new AtomicLong();
    private static AtomicLong ops = new AtomicLong();

    public static void main(String[] args) {
        final ClientConfig clientConfig = new ClientConfig();
        clientConfig.getNetworkConfig().addAddress("127.0.0.1:5701");
        //        clientConfig.getNetworkConfig().addAddress("10.216.1.24:5701");
        //Hazelcast.newHazelcastInstance();
        //Hazelcast.newHazelcastInstance();
        final HazelcastInstance client = HazelcastClient.newHazelcastClient(clientConfig);

        IAtomicLong atomicLong = client.getAtomicLong("default");

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(() -> {
                while (true) {
                    long newValue = atomicLong.incrementAndGet();
                    counter.set(newValue);
                    ops.incrementAndGet();

                }
            });
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }

        Thread statThread = new Thread(SimpleAtomicLongTestFromClient::statDisplayTask);
        statThread.start();
    }

    private static void statDisplayTask() {
        while (true) {
            try {
                Thread.sleep(statsSeconds * 1000);
                long counterTmp = counter.get();
                long opsTmp = ops.get();

                ops.set(0);
                System.out.println("Atomic long : " + counterTmp);
                System.out.println("ops/sec : " + opsTmp);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}
