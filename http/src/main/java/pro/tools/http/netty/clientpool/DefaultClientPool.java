package pro.tools.http.netty.clientpool;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.tools.constant.StrConst;
import pro.tools.data.text.ToolJson;
import pro.tools.http.netty.handler.HttpClientChannelPoolHandler;
import pro.tools.http.netty.handler.HttpClientHandler;
import pro.tools.http.pojo.HttpDefaultHeaders;
import pro.tools.http.pojo.HttpException;
import pro.tools.http.pojo.HttpMethod;
import pro.tools.http.pojo.HttpReceive;
import pro.tools.http.pojo.HttpScheme;
import pro.tools.http.pojo.HttpSend;

import javax.net.ssl.SSLException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.AbstractCollection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author SeanDragon
 * <p>
 * Create By 2017-07-21 10:02
 */
public class DefaultClientPool {
    private static final Logger log = LoggerFactory.getLogger(DefaultClientPool.class);
    private static EventLoopGroup GROUP;
    private static volatile boolean haveInit;

    private ChannelPool channelPool;
    private String host;
    private String scheme;
    private Integer port;
    private SslContext sslContext;

    public DefaultClientPool(final String url) throws HttpException {
        if (!haveInit) {
            GROUP = new NioEventLoopGroup();
            Runtime.getRuntime().addShutdownHook(new Thread(DefaultClientPool::stopAll));
            haveInit = true;
        }

        try {
            init(url);
        } catch (URISyntaxException | SSLException e) {
            throw new HttpException(e);
        }
    }

    public static void stopAll() {
        GROUP.shutdownGracefully();
    }

    private void init(String url) throws URISyntaxException, SSLException{
        URI uri = new URI(url);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("uri不合法");
        }
        scheme = uri.getScheme();
        host = uri.getHost();
        port = uri.getPort();
        if (port == -1) {
            if (HttpScheme.HTTP.equalsIgnoreCase(scheme)) {
                port = 80;
            } else if (HttpScheme.HTTPS.equalsIgnoreCase(scheme)) {
                port = 443;
            }
        }

        if (!HttpScheme.HTTP.equalsIgnoreCase(scheme) && !HttpScheme.HTTPS.equalsIgnoreCase(scheme)) {
            if (log.isErrorEnabled()) {
                log.error("仅有HTTP（S）是支持的。");
            }
            return;
        }

        final boolean ssl = HttpScheme.HTTPS.equalsIgnoreCase(scheme);

        this.setSSlContext(ssl);

        final Bootstrap b = new Bootstrap();
        b.group(GROUP)
                .channel(NioSocketChannel.class)
                .remoteAddress(host, port)
        ;

        channelPool = new FixedChannelPool(b, new HttpClientChannelPoolHandler(sslContext), Integer.MAX_VALUE);
    }

    public HttpReceive request(HttpSend httpSend) {
        return request(httpSend, Integer.MAX_VALUE, TimeUnit.DAYS);
    }

    public HttpReceive request(HttpSend httpSend, long timeout, TimeUnit timeUnit) {
        final HttpReceive httpReceive = new HttpReceive();
        final Channel channel = channelPool.acquire().syncUninterruptibly().getNow();
        try {
            channel.pipeline().addLast(new HttpClientHandler(httpSend, httpReceive));

            final FullHttpRequest fullHttpRequest = convertRequest(httpSend);

            channel.pipeline().writeAndFlush(fullHttpRequest);

            channel.closeFuture().await(timeout, timeUnit);

            if (!httpReceive.getIsDone()) {
                httpReceive.setHaveError(true);
                httpReceive.setErrMsg("请求已经超时");
            }
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn(e.getMessage(), e);
            }
            httpReceive.setHaveError(true)
                    .setErrMsg(e.getMessage())
                    .setThrowable(e)
                    .setIsDone(true);
        } finally {
            channelPool.release(channel).awaitUninterruptibly(0, TimeUnit.MILLISECONDS);
        }

        return httpReceive;
    }

    private static final HttpHeaders DEFAULT_HTTP_HEADERS;

    static {
        DEFAULT_HTTP_HEADERS = new DefaultHttpHeaders();
        HttpDefaultHeaders.getDefaultHeaders().forEach(DEFAULT_HTTP_HEADERS::set);
    }

    public void stop() {
        this.channelPool.close();
    }

    private FullHttpRequest convertRequest(HttpSend httpSend) {
        io.netty.handler.codec.http.HttpMethod httpMethod;
        HttpMethod method = httpSend.getMethod();
        Map<String, Object> params = httpSend.getParams();
        Map<String, Object> headers = httpSend.getHeaders();

        httpMethod = new io.netty.handler.codec.http.HttpMethod(method.name());

        if (httpSend.getUrl().trim().charAt(0) != '/') {
            httpSend.setUrl("/" + httpSend.getUrl());
        }


        QueryStringEncoder queryStringEncoder = new QueryStringEncoder(scheme + "://" + host + ":" + port + httpSend.getUrl(), httpSend.getCharset());

        String content = "";
        if (params != null) {
            final StringBuilder tempContent = new StringBuilder();
            params.forEach((key, value) -> {
                String v;
                if (value instanceof AbstractCollection
                        || value instanceof Map
                        || value instanceof Number
                        || value instanceof String) {
                    v = value.toString();
                } else {
                    v = ToolJson.anyToJson(value);
                }

                try {
                    v = URLEncoder.encode(v, StrConst.DEFAULT_CHARSET_NAME);
                } catch (UnsupportedEncodingException ignored) {
                }
                tempContent.append(key).append("=").append(v).append("&");
            });

            if (!params.isEmpty()) {
                content = tempContent.substring(0, tempContent.length() - 1);
            }
        }

        URI sendURI;
        try {
            sendURI = new URI(queryStringEncoder.toString());
        } catch (URISyntaxException e) {
            log.warn(e.getMessage(), e);
            throw new RuntimeException(e);
        }

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1
                , httpMethod
                , sendURI.toString()
                , Unpooled.copiedBuffer(content.getBytes())
        );

        // FIXME: 2017/7/27 暂未加Cookie
        // if (cookies != null) {
        //    List<Cookie> cookieList = Lists.newArrayListWithCapacity(cookies.size());
        //    cookies.forEach((key, value) -> {
        //        cookieList.add(new DefaultCookie(key, value));
        //    });
        //
        //    request.headers().set(
        //            HttpHeaderNames.COOKIE,
        //            ClientCookieEncoder.STRICT.encode(cookieList)
        //    );
        // }

        request.headers().add(DEFAULT_HTTP_HEADERS);
        if (headers != null) {
            headers.forEach((key, value) -> {
                request.headers().set(key, value);
            });
        }

        request.headers().set(HttpHeaderNames.HOST, host + ":" + port);
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED + ";charset" + httpSend.getCharset().toString());
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, request.content().readableBytes());

        return request;
    }

    private void setSSlContext(boolean ssl) throws SSLException {
        if (ssl) {
            sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } else {
            sslContext = null;
        }
    }
}