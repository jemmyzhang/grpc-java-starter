package com.nxest.grpc.spring.server;

import com.google.common.base.Strings;
import com.google.common.net.InetAddresses;
import com.nxest.grpc.spring.server.configure.GrpcServerProperties;
import com.nxest.grpc.spring.server.executor.GrpcDiscardPolicy;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.SelfSignedCertificate;
import io.grpc.netty.shaded.io.netty.util.concurrent.DefaultThreadFactory;
import org.springframework.util.ResourceUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Logger;

import static java.lang.String.format;

/**
 * Grpc netty server factory
 */
public class NettyGrpcServerFactory implements GrpcServerFactory {

    private static final Logger logger = Logger.getLogger(NettyGrpcServerFactory.class.getName());

    private GrpcServerBuilderConfigurer configurer;

    private GrpcServerProperties properties;

    private GrpcServiceDiscoverer discoverer;

    public NettyGrpcServerFactory(GrpcServiceDiscoverer discoverer) {
        this(discoverer, GrpcServerProperties.DEFAULT);
    }

    public NettyGrpcServerFactory(GrpcServiceDiscoverer discoverer, GrpcServerProperties properties) {
        this(discoverer, properties, null);
    }

    public NettyGrpcServerFactory(GrpcServiceDiscoverer discoverer, GrpcServerProperties properties, GrpcServerBuilderConfigurer configurer) {
        this.discoverer = discoverer;
        this.properties = properties;
        this.configurer = configurer;
    }

    @Override
    public GrpcServer createServer() {
        logger.info("Starting grpc Server ...");

        //init default server builder
        NettyServerBuilder serverBuilder = NettyServerBuilder.forAddress(
            new InetSocketAddress(InetAddresses.forString(properties.getHost()), properties.getPort()));

        //find and bind services
        configureServices(serverBuilder);

        //SSL/TLS supports
        configurerSslContext(serverBuilder);

        configureMessageLimits(serverBuilder);

        configureExecutorPool(serverBuilder);

        //add custom server configure
        if (configurer != null) {
            configurer.configure(serverBuilder);
        }

        //init the server
        serverBuilder.build();

        return new GrpcServer(serverBuilder.build(), properties);
    }


    private void configureServices(final NettyServerBuilder builder) {
        Collection<GrpcServiceDefinition> serviceList = discoverer.findGrpcServices();
        for (final GrpcServiceDefinition service : serviceList) {
            final String serviceName = service.getDefinition().getServiceDescriptor().getName();
            builder.addService(service.getDefinition());
            logger.info(format("Grpc service %s has been registered.", serviceName));
        }
    }

    private void configureExecutorPool(final NettyServerBuilder serverBuilder) {
        GrpcServerProperties.ExecutorProperties executor = properties.getExecutor();
        logger.info(format("Grpc server executor properties: %s", executor));

        if (executor == null) {
            return;
        }
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(executor.getCorePoolSize(),
            executor.getMaximumPoolSize(),
            executor.getKeepAliveTime(),
            executor.getKeepAliveTimeUnit(),
            new LinkedBlockingQueue<>(executor.getWorkQueueCapacity()),
            new DefaultThreadFactory("grpc-server-pool", true),
            new GrpcDiscardPolicy()
        );
        serverBuilder.executor(threadPoolExecutor);
    }

    private void configureMessageLimits(final NettyServerBuilder builder) {
        final Integer maxInboundMessageSize = properties.getMaxInboundMessageSize();
        if (maxInboundMessageSize == null) {
            //use default size
            return;
        }
        builder.maxInboundMessageSize(maxInboundMessageSize == -1 ? Integer.MAX_VALUE : maxInboundMessageSize);
    }

    private void configurerSslContext(NettyServerBuilder serverBuilder) {
        GrpcServerProperties.SecurityProperties security = this.properties.getSecurity();

        if (security == null || !security.isEnableSsl()) {
            logger.warning("Grpc server SSL/TLS disabled. This is not recommended.");
            return;
        }

        try {
            logger.info("Begin init grpc server SSL/TLS.");
            String certChainFile = security.getCertChainFile();
            String privateKeyFile = security.getPrivateKeyFile();
            logger.info(format("Grpc server SSL/TLS certChainFile is %s.", certChainFile));
            logger.info(format("Grpc server SSL/TLS privateKeyFile is %s.", privateKeyFile));

            SslContextBuilder sslClientContextBuilder;
            if (Strings.isNullOrEmpty(certChainFile) || Strings.isNullOrEmpty(certChainFile)) {
                logger.warning("Grpc server SSL/TLS certChainFile or privateKeyFile  is empty,use default SelfSignedCertificate.");
                SelfSignedCertificate ssc = new SelfSignedCertificate();
                sslClientContextBuilder = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey());
            } else {
                sslClientContextBuilder = SslContextBuilder.forServer(ResourceUtils.getURL(certChainFile).openStream(),
                    ResourceUtils.getURL(privateKeyFile).openStream());
            }

            // You only need to supply trustCertCollectionFile if you want to enable Mutual TLS.
            String trustCertCollectionFile = security.getTrustCertCollectionFile();
            logger.info(format("Grpc server SSL/TLS trustCertCollectionFile is %s.", trustCertCollectionFile));

            if (!Strings.isNullOrEmpty(trustCertCollectionFile)) {
                sslClientContextBuilder.trustManager(ResourceUtils.getURL(trustCertCollectionFile).openStream());
                sslClientContextBuilder.clientAuth(ClientAuth.REQUIRE);
            }

            serverBuilder.sslContext(GrpcSslContexts.configure(sslClientContextBuilder, sslProvider(security.getSslProvider())).build());

            logger.info("Success init grpc server SSL/TLS.");

        } catch (CertificateException | IOException e) {
            logger.warning("Failed init grpc server SSL/TLS." + e);
            throw new RuntimeException("Failed to init grpc server SSL/TLS.", e);
        }
    }

    private SslProvider sslProvider(String sslProvider) {
        if (Strings.isNullOrEmpty(sslProvider)) {
            return SslProvider.OPENSSL;
        }
        return SslProvider.valueOf(sslProvider.toUpperCase());
    }
}
