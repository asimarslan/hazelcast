/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.cluster.impl;

import com.hazelcast.cluster.ClusterState;
import com.hazelcast.cluster.Joiner;
import com.hazelcast.cluster.impl.operations.JoinCheckOperation;
import com.hazelcast.cluster.impl.operations.MemberRemoveOperation;
import com.hazelcast.cluster.impl.operations.MergeClustersOperation;
import com.hazelcast.config.Config;
import com.hazelcast.core.Member;
import com.hazelcast.instance.GroupProperty;
import com.hazelcast.instance.Node;
import com.hazelcast.instance.NodeState;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Connection;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.OperationService;
import com.hazelcast.util.Clock;
import com.hazelcast.util.EmptyStatement;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.hazelcast.spi.impl.OperationResponseHandlerFactory.createEmptyResponseHandler;

public abstract class AbstractJoiner implements Joiner {

    private static final long SPLIT_BRAIN_CONN_TIMEOUT = 5000;
    private static final long SPLIT_BRAIN_SLEEP_TIME = 10;

    private final AtomicLong joinStartTime = new AtomicLong(Clock.currentTimeMillis());
    private final AtomicInteger tryCount = new AtomicInteger(0);
    // map blacklisted endpoints. Boolean value represents if blacklist is temporary or permanent
    protected final ConcurrentMap<Address, Boolean> blacklistedAddresses = new ConcurrentHashMap<Address, Boolean>();
    protected final Config config;
    protected final Node node;
    protected final ClusterServiceImpl clusterService;
    protected final ClusterJoinManager clusterJoinManager;
    protected final ILogger logger;

    private final long mergeNextRunDelayMs;
    private volatile Address targetAddress;

    public AbstractJoiner(Node node) {
        this.node = node;
        this.logger = node.loggingService.getLogger(getClass());
        this.config = node.config;
        this.clusterService = node.getClusterService();
        this.clusterJoinManager = clusterService.getClusterJoinManager();
        mergeNextRunDelayMs = node.groupProperties.getMillis(GroupProperty.MERGE_NEXT_RUN_DELAY_SECONDS);
    }

    @Override
    public void blacklist(Address address, boolean permanent) {
        logger.info(address + " is added to the blacklist.");
        blacklistedAddresses.putIfAbsent(address, permanent);
    }

    @Override
    public boolean unblacklist(Address address) {
        if (blacklistedAddresses.remove(address, Boolean.FALSE)) {
            logger.info(address + " is removed from the blacklist.");
            return true;
        }
        return false;
    }

    @Override
    public boolean isBlacklisted(Address address) {
        return blacklistedAddresses.containsKey(address);
    }

    public abstract void doJoin();

    @Override
    public final void join() {
        blacklistedAddresses.clear();
        doJoin();
        postJoin();
    }

    private void postJoin() {
        blacklistedAddresses.clear();

        if (logger.isFinestEnabled()) {
            logger.finest("PostJoin master: " + node.getMasterAddress() + ", isMaster: " + node.isMaster());
        }
        if (node.getState() != NodeState.ACTIVE) {
            return;
        }
        if (tryCount.incrementAndGet() == 5) {
            logger.warning("Join try count exceed limit, setting this node as master!");
            node.setAsMaster();
        }

        if (node.joined()) {
            if (!node.isMaster()) {
                ensureConnectionToAllMembers();
            }

            if (clusterService.getSize() == 1) {
                logger.info('\n' + node.clusterService.membersString());
            }
        }
    }

    private void ensureConnectionToAllMembers() {
        boolean allConnected = false;
        if (node.joined()) {
            logger.finest("Waiting for all connections");
            int connectAllWaitSeconds = node.groupProperties.getSeconds(GroupProperty.CONNECT_ALL_WAIT_SECONDS);
            int checkCount = 0;
            while (checkCount++ < connectAllWaitSeconds && !allConnected) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }

                allConnected = true;
                Collection<Member> members = clusterService.getMembers();
                for (Member member : members) {
                    if (!member.localMember() && node.connectionManager.getOrConnect(member.getAddress()) == null) {
                        allConnected = false;
                        if (logger.isFinestEnabled()) {
                            logger.finest("Not-connected to " + member.getAddress());
                        }
                    }
                }
            }
        }
    }

    protected final long getMaxJoinMillis() {
        return node.getGroupProperties().getMillis(GroupProperty.MAX_JOIN_SECONDS);
    }

    protected final long getMaxJoinTimeToMasterNode() {
        // max join time to found master node,
        // this should be significantly greater than MAX_WAIT_SECONDS_BEFORE_JOIN property
        // hence we add 10 seconds more
        return TimeUnit.SECONDS.toMillis(10) + node.getGroupProperties().getMillis(GroupProperty.MAX_WAIT_SECONDS_BEFORE_JOIN);
    }

    boolean shouldMerge(JoinMessage joinMessage) {
        if (joinMessage == null) {
            return false;
        }

        try {
            boolean validJoinRequest = clusterJoinManager.validateJoinMessage(joinMessage);
            if (!validJoinRequest) {
                logger.finest("Cannot process split brain merge message from " + joinMessage.getAddress()
                        + ", since join-message could not be validated.");
                return false;
            }
        } catch (Exception e) {
            logger.finest(e.getMessage());
            return false;
        }

        try {
            if (clusterService.getMember(joinMessage.getAddress()) != null) {
                if (logger.isFinestEnabled()) {
                    logger.finest("Should not merge to " + joinMessage.getAddress()
                            + ", because it is already member of this cluster.");
                }
                return false;
            }

            Collection<Address> targetMemberAddresses = joinMessage.getMemberAddresses();
            if (targetMemberAddresses.contains(node.getThisAddress())) {
                node.nodeEngine.getOperationService()
                        .send(new MemberRemoveOperation(node.getThisAddress()), joinMessage.getAddress());
                logger.info(node.getThisAddress() + " CANNOT merge to " + joinMessage.getAddress()
                    + ", because it thinks this-node as its member.");
                return false;
            }

            Collection<Address> thisMemberAddresses = clusterService.getMemberAddresses();
            for (Address address : thisMemberAddresses) {
                if (targetMemberAddresses.contains(address)) {
                    logger.info(node.getThisAddress() + " CANNOT merge to " + joinMessage.getAddress()
                            + ", because it thinks " + address + " as its member. "
                            + "But " + address + " is member of this cluster.");
                    return false;
                }
            }

            int currentMemberCount = clusterService.getSize();
            if (joinMessage.getMemberCount() > currentMemberCount) {
                // I should join the other cluster
                logger.info(node.getThisAddress() + " is merging to " + joinMessage.getAddress()
                        + ", because : joinMessage.getMemberCount() > currentMemberCount ["
                        + (joinMessage.getMemberCount() + " > " + currentMemberCount) + ']');
                if (logger.isFinestEnabled()) {
                    logger.finest(joinMessage.toString());
                }
                return true;
            } else if (joinMessage.getMemberCount() == currentMemberCount) {
                // compare the hashes
                if (node.getThisAddress().hashCode() > joinMessage.getAddress().hashCode()) {
                    logger.info(node.getThisAddress() + " is merging to " + joinMessage.getAddress()
                            + ", because : node.getThisAddress().hashCode() > joinMessage.address.hashCode() "
                            + ", this node member count: " + currentMemberCount);
                    if (logger.isFinestEnabled()) {
                        logger.finest(joinMessage.toString());
                    }
                    return true;
                } else {
                    logger.info(joinMessage.getAddress() + " should merge to this node "
                            + ", because : node.getThisAddress().hashCode() < joinMessage.address.hashCode() "
                            + ", this node member count: " + currentMemberCount);
                }
            } else {
                logger.info(joinMessage.getAddress() + " should merge to this node "
                        + ", because : currentMemberCount > joinMessage.getMemberCount() ["
                        + (currentMemberCount + " > " + joinMessage.getMemberCount()) + ']');
            }
        } catch (Throwable e) {
            logger.severe(e);
        }
        return false;
    }

    JoinMessage sendSplitBrainJoinMessage(Address target) {
        if (logger.isFinestEnabled()) {
            logger.finest(node.getThisAddress() + " is connecting to " + target);
        }

        Connection conn = node.connectionManager.getOrConnect(target, true);
        long timeout = SPLIT_BRAIN_CONN_TIMEOUT;
        while (conn == null) {
            if ((timeout -= SPLIT_BRAIN_SLEEP_TIME) < 0) {
                return null;
            }
            try {
                //noinspection BusyWait
                Thread.sleep(SPLIT_BRAIN_SLEEP_TIME);
            } catch (InterruptedException e) {
                EmptyStatement.ignore(e);
                return null;
            }
            conn = node.connectionManager.getConnection(target);
        }

        NodeEngine nodeEngine = node.nodeEngine;
        Future f = nodeEngine.getOperationService().createInvocationBuilder(ClusterServiceImpl.SERVICE_NAME,
                new JoinCheckOperation(node.createSplitBrainJoinMessage()), target)
                .setTryCount(1).invoke();
        try {
            return (JoinMessage) f.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.finest("Timeout during join check!", e);
        } catch (Exception e) {
            logger.warning("Error during join check!", e);
        }
        return null;
    }

    @Override
    public void reset() {
        joinStartTime.set(Clock.currentTimeMillis());
        tryCount.set(0);
    }

    protected void startClusterMerge(final Address targetAddress) {
        ClusterServiceImpl clusterService = node.clusterService;

        if (!prepareClusterState(clusterService)) {
            return;
        }

        OperationService operationService = node.nodeEngine.getOperationService();
        Collection<Member> memberList = clusterService.getMembers();
        for (Member member : memberList) {
            if (!member.localMember()) {
                Operation op = new MergeClustersOperation(targetAddress);
                operationService.invokeOnTarget(ClusterServiceImpl.SERVICE_NAME, op, member.getAddress());
            }
        }

        Operation mergeClustersOperation = new MergeClustersOperation(targetAddress);
        mergeClustersOperation.setNodeEngine(node.nodeEngine).setService(clusterService)
                .setOperationResponseHandler(createEmptyResponseHandler());
        operationService.runOperationOnCallingThread(mergeClustersOperation);
    }

    private boolean prepareClusterState(ClusterServiceImpl clusterService) {
        long until = Clock.currentTimeMillis() + mergeNextRunDelayMs;
        while (clusterService.getClusterState() == ClusterState.ACTIVE) {
            try {
                clusterService.changeClusterState(ClusterState.FROZEN);
                return true;
            } catch (Exception e) {
                String error = e.getClass().getName() + ": " + e.getMessage();
                logger.warning("While freezing cluster state! " + error);
            }

            if (Clock.currentTimeMillis() >= until) {
                logger.warning("Could not change cluster state to FROZEN in time. "
                        + "Postponing merge process until next attempt.");
                return false;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.warning("Interrupted while preparing cluster for merge!");
                // restore interrupt flag
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public final long getStartTime() {
        return joinStartTime.get();
    }

    @Override
    public void setTargetAddress(Address targetAddress) {
        this.targetAddress = targetAddress;
    }

    public Address getTargetAddress() {
        final Address target = targetAddress;
        targetAddress = null;
        return target;
    }
}
