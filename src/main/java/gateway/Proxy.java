package gateway;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

public class Proxy {

    private static final Logger logger = LoggerFactory.getLogger(Proxy.class);

    private final NioEventLoopGroup proxyGroup;

    private ChannelHandlerContext context;
    private URI uri;

    public Proxy(NioEventLoopGroup proxyGroup) {
        this.proxyGroup = proxyGroup;
    }

    public ChannelFuture react(ChannelHandlerContext context, URI uri) {
        this.context = context;
        this.uri = uri;

        String host = this.uri.getHost();
        int port = this.uri.getPort();
        boolean isSsl = "https".equalsIgnoreCase(this.uri.getScheme());
        if (port <= 0 || port >= 65536) {
            if (isSsl) {
                port = 443;
            } else {
                port = 80;
            }
        }

        Bootstrap bootstrap = new Bootstrap()
                .group(this.proxyGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    protected void initChannel(SocketChannel ch) throws SSLException {

                        ChannelPipeline pipeline = ch.pipeline();

                        if (isSsl) {
                            SslContext sslContext = SslContextBuilder.forClient().build();
                            SSLEngine sslEngine = sslContext.newEngine(ch.alloc());
                            pipeline.addLast(new SslHandler(sslEngine));
                        }

                        pipeline.addLast(
                                new IdleStateHandler(0, 0, 30 * 1000, TimeUnit.MILLISECONDS),
                                new HttpRequestEncoder(),
                                new HttpResponseDecoder(),
                                new ProxyHandler()
                        );
                    }
                });

        return bootstrap.connect(host, port);
    }

    private class ProxyHandler extends SimpleChannelInboundHandler<Object> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws InterruptedException {
            logger.info("--- Proxy ---\t{}\n{}", uri, msg);
            context.writeAndFlush(msg).sync();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
            logger.error("--- Proxy ---\n", cause);
        }

        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                logger.error("--- Proxy ---\n{} time out", uri);
                context.close();
                ctx.close();
            }
        }
    }
}