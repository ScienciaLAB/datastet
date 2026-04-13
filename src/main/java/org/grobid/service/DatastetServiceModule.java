package org.grobid.service;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Provides;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import org.grobid.service.configuration.DatastetServiceConfiguration;
import org.grobid.service.controller.DatastetController;
import org.grobid.service.controller.HealthCheck;
import org.grobid.service.controller.DatastetProcessFile;
import org.grobid.service.controller.DatastetProcessString;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;


public class DatastetServiceModule extends DropwizardAwareModule<DatastetServiceConfiguration> {

    @Override
    protected void configure() {
        // Generic modules
        bind(GrobidEngineInitialiser.class);
        bind(HealthCheck.class);

        // Core components
        bind(DatastetProcessFile.class);
        bind(DatastetProcessString.class);

        // REST
        bind(DatastetController.class);
    }

    @Provides
    protected ObjectMapper getObjectMapper() {
        return environment().getObjectMapper();
    }

    @Provides
    protected MetricRegistry provideMetricRegistry() {
        return getMetricRegistry();
    }

    //for unit tests
    protected MetricRegistry getMetricRegistry() {
        return environment().metrics();
    }

    @Provides
    Client provideClient() {
        return ClientBuilder.newClient();
    }

}
