package org.grobid.service;

import ru.vyarus.dropwizard.guice.GuiceBundle;
import io.dropwizard.core.Application;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.forms.MultiPartBundle;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import org.eclipse.jetty.server.handler.CrossOriginHandler;
import org.eclipse.jetty.server.handler.QoSHandler;
import org.grobid.service.configuration.DatastetServiceConfiguration;
import org.grobid.service.controller.HealthCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

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

        // Enable CORS via Jetty 12's CrossOriginHandler (replaces the removed
        // org.eclipse.jetty.servlets.CrossOriginFilter). Inserted above the application
        // context so it applies to all served paths.
        CrossOriginHandler cors = new CrossOriginHandler();
        cors.setAllowedOriginPatterns(toSet(configuration.getCorsAllowedOrigins()));
        cors.setAllowedMethods(toSet(configuration.getCorsAllowedMethods()));
        cors.setAllowedHeaders(toSet(configuration.getCorsAllowedHeaders()));
        cors.setAllowCredentials(false);
        environment.getApplicationContext().insertHandler(cors);

        // Limit concurrent requests via Jetty 12's QoSHandler (replaces the removed
        // org.eclipse.jetty.servlets.QoSFilter). A non-positive value means unlimited.
        int maxParallelRequests = configuration.getMaxParallelRequests();
        if (maxParallelRequests > 0) {
            QoSHandler qos = new QoSHandler();
            qos.setMaxRequestCount(maxParallelRequests);
            environment.getApplicationContext().insertHandler(qos);
        }
    }

    /**
     * Splits a comma-separated config value (e.g. "OPTIONS,GET,POST") into a set of trimmed,
     * non-empty entries preserving order, as expected by {@link CrossOriginHandler}.
     */
    private static Set<String> toSet(String csv) {
        if (csv == null) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static void main(String[] args) throws Exception {
        new DatastetApplication().run(args);
    }
}
