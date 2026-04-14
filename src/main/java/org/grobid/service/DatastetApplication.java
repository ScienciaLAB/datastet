package org.grobid.service;

import ru.vyarus.dropwizard.guice.GuiceBundle;
import io.dropwizard.core.Application;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.forms.MultiPartBundle;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.servlets.QoSFilter;
import org.grobid.service.configuration.DatastetServiceConfiguration;
import org.grobid.service.controller.HealthCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import java.util.Arrays;
import java.util.EnumSet;

public class DatastetApplication extends Application<DatastetServiceConfiguration> {
    private static final String RESOURCES = "/service";

    private static final Logger LOGGER = LoggerFactory.getLogger(DatastetApplication.class);

    @Override
    public String getName() {
        return "datastet";
    }

    @Override
    public void initialize(Bootstrap<DatastetServiceConfiguration> bootstrap) {
        GuiceBundle guiceBundle = GuiceBundle.builder()
                .modules(new DatastetServiceModule())
                .build();
        bootstrap.addBundle(guiceBundle);
        bootstrap.addBundle(new MultiPartBundle());
        bootstrap.addBundle(new AssetsBundle("/web", "/", "index.html", "assets"));
        //bootstrap.addCommand(new CreateTrainingCommand());
    }

    @Override
    public void run(DatastetServiceConfiguration configuration, Environment environment) {
        environment.healthChecks().register("health-check", new HealthCheck(configuration));

        environment.jersey().setUrlPattern(RESOURCES + "/*");

        String allowedOrigins = configuration.getCorsAllowedOrigins();
        String allowedMethods = configuration.getCorsAllowedMethods();
        String allowedHeaders = configuration.getCorsAllowedHeaders();

        // Enable CORS headers
        final FilterRegistration.Dynamic cors =
                environment.servlets().addFilter("CORS", CrossOriginFilter.class);

        // CORS parameters
        cors.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, allowedOrigins);
        cors.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, allowedMethods);
        cors.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, allowedHeaders);

        // Add URL mapping
        cors.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");

        // Enable QoS filter
        final FilterRegistration.Dynamic qos = environment.servlets().addFilter("QOS", QoSFilter.class);
        qos.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");
        qos.setInitParameter("maxRequests", String.valueOf(configuration.getMaxParallelRequests()));
    }

    public static void main(String[] args) throws Exception {
        new DatastetApplication().run(args);
    }
}
