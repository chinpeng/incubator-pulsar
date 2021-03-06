/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.common.api;

import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.common.api.proto.PulsarApi.CommandPing;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandPong;
import org.apache.pulsar.common.api.proto.PulsarApi.ProtocolVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.ScheduledFuture;

public abstract class PulsarHandler extends PulsarDecoder {
    protected ChannelHandlerContext ctx;
    protected SocketAddress remoteAddress;
    protected int remoteEndpointProtocolVersion = ProtocolVersion.v0.getNumber();
    private final long keepAliveIntervalSeconds;
    private boolean waitingForPingResponse = false;
    private ScheduledFuture<?> keepAliveTask;

    public int getRemoteEndpointProtocolVersion() {
        return remoteEndpointProtocolVersion;
    }

    public PulsarHandler(int keepAliveInterval, TimeUnit unit) {
        this.keepAliveIntervalSeconds = unit.toSeconds(keepAliveInterval);
    }

    @Override
    final protected void messageReceived() {
        waitingForPingResponse = false;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.remoteAddress = ctx.channel().remoteAddress();
        this.ctx = ctx;

        if (log.isDebugEnabled()) {
            log.debug("[{}] Scheduling keep-alive task every {} s", ctx.channel(), keepAliveIntervalSeconds);
        }
        if (keepAliveIntervalSeconds > 0) {
            this.keepAliveTask = ctx.executor().scheduleAtFixedRate(this::handleKeepAliveTimeout,
                    keepAliveIntervalSeconds, keepAliveIntervalSeconds, TimeUnit.SECONDS);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (keepAliveTask != null) {
            keepAliveTask.cancel(false);
        }
    }

    @Override
    final protected void handlePing(CommandPing ping) {
        // Immediately reply success to ping requests
        if (log.isDebugEnabled()) {
            log.debug("[{}] Replying back to ping message", ctx.channel());
        }
        ctx.writeAndFlush(Commands.newPong());
    }

    @Override
    final protected void handlePong(CommandPong pong) {
    }

    private void handleKeepAliveTimeout() {
        if (!ctx.channel().isOpen()) {
            return;
        }

        if (!isHandshakeCompleted()) {
            log.warn("[{}] Pulsar Handshake was not completed within timeout, closing connection", ctx.channel());
            ctx.close();
        } else if (waitingForPingResponse && ctx.channel().config().isAutoRead()) {
            // We were waiting for a response and another keep-alive just completed.
            // If auto-read was disabled, it means we stopped reading from the connection, so we might receive the Ping
            // response later and thus not enforce the strict timeout here.
            log.warn("[{}] Forcing connection to close after keep-alive timeout", ctx.channel());
            ctx.close();
        } else if (remoteEndpointProtocolVersion >= ProtocolVersion.v1.getNumber()) {
            // Send keep alive probe to peer only if it supports the ping/pong commands, added in v1
            if (log.isDebugEnabled()) {
                log.debug("[{}] Sending ping message", ctx.channel());
            }
            waitingForPingResponse = true;
            ctx.writeAndFlush(Commands.newPing());
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Peer doesn't support keep-alive", ctx.channel());
            }
        }
    }

    /**
     * @return true if the connection is ready to use, meaning the Pulsar handshake was already completed
     */
    protected abstract boolean isHandshakeCompleted();

    private static final Logger log = LoggerFactory.getLogger(PulsarHandler.class);
}
