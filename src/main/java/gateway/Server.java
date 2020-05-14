package gateway;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.REQUEST_TIMEOUT;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class Server {

    private final int port;

    private final NioEventLoopGroup boosGroup;
    private final NioEventLoopGroup wordGroup;
    private final NioEventLoopGroup proxyGroup;

    public Server(int port, NioEventLoopGroup boosGroup, NioEventLoopGroup wordGroup, NioEventLoopGroup proxyGroup) {
        this.port = port;
        this.boosGroup  = boosGroup;
        this.wordGroup  = wordGroup;
        this.proxyGroup = proxyGroup;
    }

    public void start() {
        try {
            new ServerBootstrap()
                    .group(boosGroup, wordGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        public void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ReadTimeoutHandler(30 * 1000, TimeUnit.MILLISECONDS),
                                    new WriteTimeoutHandler(30 * 1000, TimeUnit.MILLISECONDS),
                                    new HttpRequestDecoder(),
                                    new HttpResponseEncoder(),
                                    new ServerHandler(proxyGroup)
                            );
                        }
                    })
                    .option(ChannelOption.SO_RCVBUF, 1024 * 1024 * 8)
                    .option(ChannelOption.SO_BACKLOG, 256)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .bind(port)
                    .sync();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class ServerHandler extends SimpleChannelInboundHandler<Object> {

        private static final Logger logger = LoggerFactory.getLogger(ServerHandler.class);

        private final Proxy proxy;
        private ChannelFuture future;
        private URI uri;

        private ServerHandler(NioEventLoopGroup group) {
            proxy = new Proxy(group);
        }

        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {

            if (msg instanceof HttpRequest) {
                String host = ((HttpRequest) msg).headers().get("Host");
                uri = Conf.valueOf(host);
                if (uri == null) {
                    DefaultFullHttpResponse not = new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
                    not.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8")
                            .set(ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                            .set(ACCESS_CONTROL_ALLOW_HEADERS, "Origin, X-Requested-With, Content-Type, Accept")
                            .set(ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT,DELETE");
                    ctx.writeAndFlush(not);
                    return;
                }
                future = proxy.react(ctx, uri);
                HttpRequest message = (HttpRequest) msg;
                HttpHeaders headers = message.headers();
                headers.set("GatewayHost", headers.get("Host"));
                headers.set("Host", uri.getHost());
            }

            future.addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    DefaultFullHttpResponse out = new DefaultFullHttpResponse(HTTP_1_1, REQUEST_TIMEOUT);
                    out.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8")
                            .set(ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                            .set(ACCESS_CONTROL_ALLOW_HEADERS, "Origin, X-Requested-With, Content-Type, Accept")
                            .set(ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT,DELETE");
                    ctx.writeAndFlush(out);
                } else {
                    future.channel().writeAndFlush(msg);
                }
            });

            logger.info("--- Server ---\t{}\n{}", uri, msg);
        }

//        @Override
//        public void channelInactive(ChannelHandlerContext ctx) {
//            ctx.close();
//        }
//
//        @Override
//        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
//            logger.error("--- Server ---\t{}\n", uri, cause);
//            ctx.close();
//        }
    }
}