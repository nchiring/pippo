/*
 * Copyright (C) 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ro.pippo.jetty;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.pippo.core.AbstractWebServer;
import ro.pippo.core.HttpConstants;
import ro.pippo.core.PippoRuntimeException;

import javax.servlet.DispatcherType;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * @author Decebal Suiu
 */
public class JettyServer extends AbstractWebServer {

    private static final Logger log = LoggerFactory.getLogger(JettyServer.class);

    private Server server;

    @Override
    public void start() {
        ServerConnector serverConnector = createServerConnector();
        serverConnector.setIdleTimeout(TimeUnit.HOURS.toMillis(1));
        serverConnector.setSoLingerTime(-1);
        serverConnector.setHost(settings.getHost());
        serverConnector.setPort(settings.getPort());

        server = serverConnector.getServer();

        ServerConnector[] connectors = new ServerConnector[1];
        connectors[0] = serverConnector;
        server.setConnectors(connectors);

        Handler pippoHandler = createPippoHandler();
        server.setHandler(pippoHandler);

        try {
            String version = server.getClass().getPackage().getImplementationVersion();
            log.info("Starting Jetty Server {} on port {}", version, settings.getPort());
            server.start();
            server.join();
        } catch (Exception e) {
            throw new PippoRuntimeException(e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                throw new PippoRuntimeException("Cannot stop Jetty Server", e);
            }
        }
    }

    protected ServerConnector createServerConnector() {
        if (settings.getKeystoreFile() == null) {
            return new ServerConnector(new Server());
        }

        SslContextFactory sslContextFactory = new SslContextFactory(settings.getKeystoreFile());

        if (settings.getKeystorePassword() != null) {
            sslContextFactory.setKeyStorePassword(settings.getKeystorePassword());
        }
        if (settings.getTruststoreFile() != null) {
            sslContextFactory.setTrustStorePath(settings.getKeystorePassword());
        }
        if (settings.getTruststorePassword() != null) {
            sslContextFactory.setTrustStorePassword(settings.getTruststorePassword());
        }

        return new ServerConnector(new Server(), sslContextFactory);
    }

    protected ServletContextHandler createPippoHandler() {
        String location = pippoFilter.getApplication().getUploadLocation();
        long maxFileSize = pippoFilter.getApplication().getMaximumUploadSize();
        MultipartConfigElement multipartConfig = new MultipartConfigElement(location, maxFileSize, -1L, 0);
        ServletContextHandler handler = new PippoHandler(ServletContextHandler.SESSIONS, multipartConfig);
        handler.setContextPath(settings.getContextPath());

        String filterPath = pippoFilter.getFilterPath();
        if (filterPath == null) {
            filterPath = "/*"; // default value
        }

        EnumSet<DispatcherType> dispatches = EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR);

        FilterHolder pippoFilterHolder = new FilterHolder(pippoFilter);
        handler.addFilter(pippoFilterHolder, filterPath, dispatches);
        log.debug("Using pippo filter for path '{}'", filterPath);

        return handler;
    }

    /**
     * Inject a MultipartConfig in a filter.
     */
    private static class PippoHandler extends ServletContextHandler {

        private MultipartConfigElement multipartConfig;

        private PippoHandler(int options, MultipartConfigElement multipartConfig) {
            super(options);

            this.multipartConfig = multipartConfig;
        }

        @Override
        public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {

            if (isMultipartRequest(request)) {
                baseRequest.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, multipartConfig);
            }

            super.doHandle(target, baseRequest, request, response);
        }

        private boolean isMultipartRequest(HttpServletRequest request) {
            return HttpConstants.Method.POST.equalsIgnoreCase(request.getMethod())
                && request.getContentType() != null
                && request.getContentType().toLowerCase().startsWith(HttpConstants.ContentType.MULTIPART_FORM_DATA);
        }

    }

}
