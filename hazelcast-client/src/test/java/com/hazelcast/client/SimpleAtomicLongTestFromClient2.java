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
import org.junit.Ignore;

import java.util.concurrent.CountDownLatch;

@Ignore("Not a JUnit test")
public class SimpleAtomicLongTestFromClient2 {

    private static int MaxThreadCount = 32;
    private static int MaxSize = 1_00_000;

    private static HazelcastInstance client;

    public static void main(String[] args) throws InterruptedException {
        final ClientConfig clientConfig = new ClientConfig();
        clientConfig.getNetworkConfig().addAddress("127.0.0.1:5701");
//        clientConfig.getNetworkConfig().addAddress("10.216.1.24:5701");
        //Hazelcast.newHazelcastInstance();
        //Hazelcast.newHazelcastInstance();
        client = HazelcastClient.newHazelcastClient(clientConfig);

        System.out.println("Client Ready to go...");
        System.out.println("Warm up: " + bench(10, MaxSize / 100));
        System.out.println("Single: " + benchSingle( MaxSize / 10));
        //System.out.println("Single: " + bench(1, MaxSize / 10));
        //System.out.println("Max up: " + bench(25, MaxSize));

        System.out.println("\nRESULTS...\n");
        for (int threadCount = 1; threadCount < MaxThreadCount; threadCount += 1) {
            double benchResult = bench(threadCount, MaxSize);
            System.out.println(threadCount + ": " + benchResult + " ops/sec");
        }

        client.shutdown();
    }

    private static double bench(int threadCount, int maxCount) throws InterruptedException {
        final int mx = maxCount / threadCount;
        long start = System.currentTimeMillis();

        CountDownLatch cde = new CountDownLatch(threadCount);
        IAtomicLong atomicLong = client.getAtomicLong("default");
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < mx; j++) {
                    atomicLong.incrementAndGet();
                }
                cde.countDown();
            });
            t.start();
        }

        cde.await();
        long delta = System.currentTimeMillis() - start;
        return 1000 * mx * threadCount / delta;
    }

    private static double benchSingle(int maxCount) throws InterruptedException {
        long start = System.currentTimeMillis();

        IAtomicLong atomicLong = client.getAtomicLong("default");
        for (int j = 0; j < maxCount; j++) {
            atomicLong.incrementAndGet();
        }
        long delta = System.currentTimeMillis() - start;
        return 1000 * maxCount / delta;
    }

}
