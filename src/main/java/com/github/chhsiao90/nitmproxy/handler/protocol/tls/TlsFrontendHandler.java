package com.github.chhsiao90.nitmproxy.handler.protocol.tls;

import com.github.chhsiao90.nitmproxy.Address;
import com.github.chhsiao90.nitmproxy.ConnectionContext;
import com.github.chhsiao90.nitmproxy.Protocols;
import com.github.chhsiao90.nitmproxy.enums.ProxyMode;
import com.github.chhsiao90.nitmproxy.exception.NitmProxyException;
import com.github.chhsiao90.nitmproxy.tls.TlsUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.AbstractSniHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslClientHelloHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static io.netty.util.ReferenceCountUtil.*;
import static java.lang.String.*;

public class TlsFrontendHandler extends ChannelDuplexHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TlsFrontendHandler.class);

    private ConnectionContext connectionContext;

    public TlsFrontendHandler(ConnectionContext connectionContext) {
        this.connectionContext = connectionContext;
    }

    boolean isTransparentProxy() {
        return connectionContext.config().getProxyMode() == ProxyMode.TRANSPARENT;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        LOGGER.debug("{} : handlerAdded", connectionContext);
        ctx.pipeline()
            .addBefore(ctx.name(), null, new DetectSslHandler(ctx))
            .addBefore(ctx.name(), null, new SniExtractorHandler())
            .addBefore(ctx.name(), null, new AlpnNegotiateHandler(ctx));
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        LOGGER.debug("{} : handlerRemoved", connectionContext);

        ctx.flush();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        LOGGER.debug("{} : channelInactive", connectionContext);
        if (connectionContext.connected()) {
            connectionContext.serverChannel().close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.error(format("%s : exceptionCaught with %s",
                connectionContext, cause.getMessage()),
                cause);
        ctx.close();
    }

    private SslHandler sslHandler(ByteBufAllocator alloc) throws SSLException {
        return TlsUtil.ctxForServer(connectionContext).newHandler(alloc);
    }

    private void configureProtocol(ChannelHandlerContext ctx, String protocol) {
        try {
            ctx.pipeline().replace(this, null, connectionContext.provider().frontendHandler(protocol));
        } catch (NitmProxyException e) {
            LOGGER.error("{} : Unsupported protocol", connectionContext);
            ctx.close();
        }
    }

    private class DetectSslHandler extends SslClientHelloHandler<Boolean> {

        private final ChannelHandlerContext tlsCtx;

        public DetectSslHandler(ChannelHandlerContext tlsCtx) {
            this.tlsCtx = tlsCtx;
        }

        @Override
        protected Future<Boolean> lookup(ChannelHandlerContext ctx, ByteBuf byteBuf) {
            boolean ssl = byteBuf != null;
            LOGGER.debug("SSL detection with {}", ssl);
            return ctx.executor().newSucceededFuture(ssl);
        }

        @Override
        protected void onLookupComplete(ChannelHandlerContext ctx, Future<Boolean> future) {
            if (!future.isSuccess()) {
                LOGGER.debug("SSL detection failed with {}", future.cause().getMessage());
                ctx.close();
            } else if (!future.getNow()) {
                if (isTransparentProxy()) {
                    //in a case of transparent proxy, remote connection happens only
                    //after the SNI lookup since destination IP is not reliable
                    connectionContext.tlsCtx().protocols(ctx.executor().newPromise());
                    connectionContext.tlsCtx().protocol(ctx.executor().newPromise());
                }
                connectionContext.tlsCtx().disableTls();
                ctx.pipeline().addAfter(ctx.name(), null, connectionContext.provider().protocolSelectHandler());
                ctx.pipeline().remove(tlsCtx.name());
                ctx.pipeline().remove(SniExtractorHandler.class);
                ctx.pipeline().remove(AlpnNegotiateHandler.class);
                ctx.pipeline().remove(ctx.name());
            } else {
                ctx.pipeline().remove(ctx.name());
            }
        }
    }

    private class SniExtractorHandler extends AbstractSniHandler<Object> {

        @Override
        protected Future<Object> lookup(ChannelHandlerContext ctx, String hostname) {
            LOGGER.debug("Client SNI lookup with {}", hostname);
            if (hostname != null) {
                Address address = null;

                //in a transparent proxy, destination host ip and port are unreliable.
                //need to assume outbound port of 443 and we need to connect to remote
                if (isTransparentProxy()) {
                    address = new Address(hostname, 443);
                } else {
                    address = new Address(hostname, connectionContext.getServerAddr().getPort());
                }
                connectionContext.withServerAddr(address);
            }
            return ctx.executor().newSucceededFuture(null);
        }

        @Override
        protected void onLookupComplete(ChannelHandlerContext ctx, String hostname, Future<Object> future) {
            if (isTransparentProxy()) {
                ctx.pipeline().replace(ctx.name(), null, new ConnectToRemote());
            } else {
                ctx.pipeline().remove(this);
            }
        }
    }

    public class ConnectToRemote extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            connectionContext.connect(connectionContext.getServerAddr(), ctx).addListener((future) -> {
                if (!future.isSuccess()) {
                    ctx.close();
                }
            });
            ctx.fireChannelRead(msg);
        }
    }

    private class AlpnNegotiateHandler extends AbstractAlpnHandler<String> {

        private final ChannelHandlerContext tlsCtx;

        public AlpnNegotiateHandler(ChannelHandlerContext tlsCtx) {
            this.tlsCtx = tlsCtx;
        }

        @Override
        protected void onLookupComplete(ChannelHandlerContext ctx, List<String> protocols,
                                        Future<String> future) throws Exception {
            if (!future.isSuccess()) {
                LOGGER.debug("ALPN negotiate failed with {}", future.cause().getMessage());
                ctx.close();
            } else {
                LOGGER.debug("ALPN negotiated with {}", future.getNow());
                SslHandler sslHandler = sslHandler(ctx.alloc());
                try {
                    ctx.pipeline()
                        .addAfter(ctx.name(), null, new AlpnHandler(tlsCtx))
                        .replace(ctx.name(), null, sslHandler);
                    sslHandler = null;
                } finally {
                    if (sslHandler != null) {
                        safeRelease(sslHandler.engine());
                    }
                }
            }
        }

        @Override
        protected Future<String> lookup(ChannelHandlerContext ctx, List<String> protocols) {
            LOGGER.debug("Client ALPN lookup with {}", protocols);
            connectionContext.tlsCtx().protocolsPromise().setSuccess(protocols);
            return connectionContext.tlsCtx().protocolPromise();
        }
    }

    private class AlpnHandler extends ApplicationProtocolNegotiationHandler {

        private ChannelHandlerContext tlsCtx;

        private AlpnHandler(ChannelHandlerContext tlsCtx) {
            super(Protocols.FORWARD);
            this.tlsCtx = tlsCtx;
        }

        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
            if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
                configureProtocol(tlsCtx, Protocols.HTTP_1);
            } else if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                configureProtocol(tlsCtx, Protocols.HTTP_2);
            } else {
                configureProtocol(tlsCtx, Protocols.FORWARD);
            }
        }
    }
}
