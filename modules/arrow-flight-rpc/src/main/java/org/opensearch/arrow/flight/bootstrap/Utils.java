/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.arrow.util.Preconditions;

import java.lang.reflect.Constructor;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Utils {
    private static final Logger logger = Logger.getLogger(Utils.class.getName());
    public static final ChannelFactory<? extends ServerChannel> DEFAULT_SERVER_CHANNEL_FACTORY;
    public static final Class<? extends Channel> DEFAULT_CLIENT_CHANNEL_TYPE;
    public static final Class<? extends Channel> EPOLL_DOMAIN_CLIENT_CHANNEL_TYPE;
    private static final Constructor<? extends EventLoopGroup> EPOLL_EVENT_LOOP_GROUP_CONSTRUCTOR;

    private static boolean isEpollAvailable() {
        try {
            return (Boolean)Class.forName("io.netty.channel.epoll.Epoll").getDeclaredMethod("isAvailable").invoke((Object)null);
        } catch (ClassNotFoundException var1) {
            return false;
        } catch (Exception var2) {
            Exception e = var2;
            throw new RuntimeException("Exception while checking Epoll availability", e);
        }
    }

    private static Throwable getEpollUnavailabilityCause() {
        try {
            return (Throwable)Class.forName("io.netty.channel.epoll.Epoll").getDeclaredMethod("unavailabilityCause").invoke((Object)null);
        } catch (Exception var1) {
            Exception e = var1;
            return e;
        }
    }

    private static Class<? extends Channel> epollChannelType() {
        try {
            Class<? extends Channel> channelType = Class.forName("io.netty.channel.epoll.EpollSocketChannel").asSubclass(Channel.class);
            return channelType;
        } catch (ClassNotFoundException var1) {
            ClassNotFoundException e = var1;
            throw new RuntimeException("Cannot load EpollSocketChannel", e);
        }
    }

    private static Class<? extends Channel> epollDomainSocketChannelType() {
        try {
            Class<? extends Channel> channelType = Class.forName("io.netty.channel.epoll.EpollDomainSocketChannel").asSubclass(Channel.class);
            return channelType;
        } catch (ClassNotFoundException var1) {
            ClassNotFoundException e = var1;
            throw new RuntimeException("Cannot load EpollDomainSocketChannel", e);
        }
    }

    private static Constructor<? extends EventLoopGroup> epollEventLoopGroupConstructor() {
        try {
            return Class.forName("io.netty.channel.epoll.EpollEventLoopGroup").asSubclass(EventLoopGroup.class).getConstructor(Integer.TYPE, ThreadFactory.class);
        } catch (ClassNotFoundException var1) {
            ClassNotFoundException e = var1;
            throw new RuntimeException("Cannot load EpollEventLoopGroup", e);
        } catch (NoSuchMethodException var2) {
            NoSuchMethodException e = var2;
            throw new RuntimeException("EpollEventLoopGroup constructor not found", e);
        }
    }

    private static Class<? extends ServerChannel> epollServerChannelType() {
        try {
            Class<? extends ServerChannel> serverSocketChannel = Class.forName("io.netty.channel.epoll.EpollServerSocketChannel").asSubclass(ServerChannel.class);
            return serverSocketChannel;
        } catch (ClassNotFoundException var1) {
            ClassNotFoundException e = var1;
            throw new RuntimeException("Cannot load EpollServerSocketChannel", e);
        }
    }

    private static EventLoopGroup createEpollEventLoopGroup(int parallelism, ThreadFactory threadFactory) {
        Preconditions.checkState(EPOLL_EVENT_LOOP_GROUP_CONSTRUCTOR != null, "Epoll is not available");
        try {
            return (EventLoopGroup)EPOLL_EVENT_LOOP_GROUP_CONSTRUCTOR.newInstance(parallelism, threadFactory);
        } catch (Exception var3) {
            Exception e = var3;
            throw new RuntimeException("Cannot create Epoll EventLoopGroup", e);
        }
    }

    private static ChannelFactory<ServerChannel> nioServerChannelFactory() {
        return new ChannelFactory<ServerChannel>() {
            public ServerChannel newChannel() {
                return new NioServerSocketChannel();
            }
        };
    }

    private static <T> ChannelOption<T> getEpollChannelOption(String optionName) {
        if (isEpollAvailable()) {
            try {
                return (ChannelOption)Class.forName("io.netty.channel.epoll.EpollChannelOption").getField(optionName).get((Object)null);
            } catch (Exception var2) {
                Exception e = var2;
                throw new RuntimeException("ChannelOption(" + optionName + ") is not available", e);
            }
        } else {
            return null;
        }
    }


    private Utils() {
    }

    static {
        if (isEpollAvailable()) {
            DEFAULT_CLIENT_CHANNEL_TYPE = epollChannelType();
            EPOLL_DOMAIN_CLIENT_CHANNEL_TYPE = epollDomainSocketChannelType();
            DEFAULT_SERVER_CHANNEL_FACTORY = new ReflectiveChannelFactory(epollServerChannelType());
            EPOLL_EVENT_LOOP_GROUP_CONSTRUCTOR = epollEventLoopGroupConstructor();

        } else {
            logger.log(Level.FINE, "Epoll is not available, using Nio.", getEpollUnavailabilityCause());
            DEFAULT_SERVER_CHANNEL_FACTORY = nioServerChannelFactory();
            DEFAULT_CLIENT_CHANNEL_TYPE = NioSocketChannel.class;
            EPOLL_DOMAIN_CLIENT_CHANNEL_TYPE = null;
            EPOLL_EVENT_LOOP_GROUP_CONSTRUCTOR = null;
        }
    }

    public static EventLoopGroup create(String name, int numEventLoops, ThreadFactory threadFactory) {
        // ThreadFactory threadFactory = new DefaultThreadFactory(name, true);
        if (isEpollAvailable()) {
            return createEpollEventLoopGroup(numEventLoops, threadFactory);
        } else {
            return new NioEventLoopGroup(numEventLoops, threadFactory);
        }
    }
}

