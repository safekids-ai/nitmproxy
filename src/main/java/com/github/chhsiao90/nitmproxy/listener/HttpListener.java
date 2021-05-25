package com.github.chhsiao90.nitmproxy.listener;

import com.github.chhsiao90.nitmproxy.event.HttpEvent;
import com.github.chhsiao90.nitmproxy.handler.protocol.http2.Http2FrameWrapper;
import com.github.chhsiao90.nitmproxy.handler.protocol.http2.Http2FramesWrapper;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;

import java.util.Optional;

public interface HttpListener {

    default void onHttpEvent(HttpEvent event) {
    }

    default Optional<FullHttpResponse> onHttp1Request(FullHttpRequest request) {
        return Optional.empty();
    }

    default void onHttp1Response(HttpResponse response) {
    }

    default void onHttp1ResponseData(HttpContent data) {
    }

    default Optional<Http2FramesWrapper> onHttp2Request(Http2FramesWrapper request) {
        return Optional.empty();
    }

    default void onHttp2ResponseFrame(Http2FrameWrapper<?> frame) {
    }
}