package com.github.chhsiao90.nitmproxy.handler.protocol.http2;

import com.github.chhsiao90.nitmproxy.ConnectionContext;
import com.github.chhsiao90.nitmproxy.NitmProxyMaster;
import com.github.chhsiao90.nitmproxy.event.HttpEvent;
import com.github.chhsiao90.nitmproxy.http.HttpUtil;
import com.github.chhsiao90.nitmproxy.listener.HttpListener;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.chhsiao90.nitmproxy.http.HttpHeadersUtil.*;
import static java.lang.System.*;

public class Http2EventHandler extends ChannelDuplexHandler {

    private HttpListener listener;
    private ConnectionContext connectionContext;

    private Map<Integer, FrameCollector> streams = new ConcurrentHashMap<>();

    /**
     * Create new instance of http1 event handler.
     *
     * @param master            the master
     * @param connectionContext the connection context
     */
    public Http2EventHandler(
            NitmProxyMaster master,
            ConnectionContext connectionContext) {
        this.listener = master.httpEventListener();
        this.connectionContext = connectionContext;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
        if (msg instanceof Http2FrameWrapper) {
            Http2FrameWrapper<?> frameWrapper = (Http2FrameWrapper<?>) msg;
            listener.onHttp2ResponseFrame(frameWrapper);
            FrameCollector frameCollector = streams.computeIfAbsent(frameWrapper.streamId(), this::newFrameCollector);
            if (frameCollector.onResponseFrame(frameWrapper.frame())) {
                try {
                    frameCollector.collect().ifPresent(listener::onHttpEvent);
                } finally {
                    frameCollector.release();
                    streams.remove(frameWrapper.streamId());
                }
            }
        }
        super.write(ctx, msg, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof Http2FrameWrapper)) {
            ctx.fireChannelRead(msg);
            return;
        }

        Http2FrameWrapper<?> frameWrapper = (Http2FrameWrapper<?>) msg;
        FrameCollector frameCollector = streams.computeIfAbsent(frameWrapper.streamId(), this::newFrameCollector);
        Optional<Http2FramesWrapper> fullRequest = frameCollector.onRequestFrame(frameWrapper.frame());
        if (!fullRequest.isPresent()) {
            return;
        }

        Optional<Http2FramesWrapper> response = listener.onHttp2Request(fullRequest.get());
        if (!response.isPresent()) {
            fullRequest.get().getAllFrames().forEach(ctx::fireChannelRead);
            return;
        }

        try {
            List<Http2FrameWrapper<?>> frames = response.get().getAllFrames();
            frames.stream()
                    .map(frame -> frame.frame())
                    .forEach(frameCollector::onResponseFrame);
            frameCollector.collect().ifPresent(listener::onHttpEvent);
            frames.forEach(ctx::write);
            ctx.flush();
        } finally {
            frameCollector.release();
            streams.remove(frameWrapper.streamId());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        streams.values().forEach(FrameCollector::release);
        ctx.fireChannelInactive();
    }

    private FrameCollector newFrameCollector(int streamId) {
        return new FrameCollector(streamId, HttpEvent.builder(connectionContext));
    }

    private static class FrameCollector {

        private int streamId;
        private HttpEvent.Builder httpEventBuilder;
        private Http2HeadersFrame requestHeader;
        private List<Http2DataFrame> requestData = new ArrayList<>();
        private boolean requestDone;

        public FrameCollector(int streamId, HttpEvent.Builder httpEventBuilder) {
            this.streamId = streamId;
            this.httpEventBuilder = httpEventBuilder;
        }

        /**
         * Handles a http2 frame of the request, and return full request frames while the request was ended.
         *
         * @param frame a http2 frame
         * @return full request frames if the request was ended, return empty if there are more frames of the request
         */
        public Optional<Http2FramesWrapper> onRequestFrame(Http2Frame frame) {
            if (frame instanceof Http2HeadersFrame) {
                requestHeader = (Http2HeadersFrame) frame;
                Http2Headers headers = requestHeader.headers();
                httpEventBuilder.method(HttpMethod.valueOf(headers.method().toString()))
                                .version(HttpUtil.HTTP_2)
                                .host(headers.authority().toString())
                                .path(headers.path().toString())
                                .requestTime(currentTimeMillis());
                requestDone = requestHeader.isEndStream();
            } else if (frame instanceof Http2DataFrame) {
                Http2DataFrame data = (Http2DataFrame) frame;
                requestData.add(data);
                httpEventBuilder.addRequestBodySize(data.content().readableBytes());
                requestDone = data.isEndStream();
            }

            if (requestDone) {
                Http2FramesWrapper request = Http2FramesWrapper
                        .builder(streamId)
                        .headers(requestHeader)
                        .data(requestData)
                        .build();
                requestData.clear();
                return Optional.of(request);
            }
            return Optional.empty();
        }

        public boolean onResponseFrame(Http2Frame frame) {
            if (frame instanceof Http2HeadersFrame) {
                Http2HeadersFrame headersFrame = (Http2HeadersFrame) frame;
                Http2Headers headers = headersFrame.headers();
                httpEventBuilder.status(getStatus(headers))
                                .contentType(getContentType(headers))
                                .responseTime(currentTimeMillis());
                return headersFrame.isEndStream();
            } else if (frame instanceof Http2DataFrame) {
                Http2DataFrame data = (Http2DataFrame) frame;
                httpEventBuilder.addResponseBodySize(data.content().readableBytes());
                return data.isEndStream();
            }
            return false;
        }

        public Optional<HttpEvent> collect() {
            if (requestDone) {
                return Optional.of(httpEventBuilder.build());
            }
            return Optional.empty();
        }

        public void release() {
            requestData.forEach(ReferenceCountUtil::release);
        }
    }
}
