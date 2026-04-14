package org.grobid.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.ImmutableList;
import org.grobid.core.engines.DataTypeClassifier;
import org.grobid.core.engines.DatasetContextClassifier;
import org.grobid.core.engines.DatasetParser;
import org.grobid.core.lexicon.DatastetLexicon;
import org.grobid.core.main.GrobidHomeFinder;
import org.grobid.core.main.LibraryLoader;
import org.grobid.service.configuration.DatastetConfiguration;
import org.grobid.core.utilities.GrobidConfig;
import org.grobid.core.utilities.GrobidConfig.ModelParameters;
import org.grobid.core.utilities.GrobidProperties;
import org.grobid.service.configuration.DatastetServiceConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.File;
import java.lang.reflect.Field;

@Singleton
public class GrobidEngineInitialiser {
    private static final Logger LOGGER = LoggerFactory.getLogger(org.grobid.service.GrobidEngineInitialiser.class);

    @Inject
    public GrobidEngineInitialiser(DatastetServiceConfiguration configuration) {
        LOGGER.info("Initialising Grobid");
        GrobidHomeFinder grobidHomeFinder = new GrobidHomeFinder(ImmutableList.of(configuration.getGrobidHome()));
        GrobidProperties.getInstance(grobidHomeFinder);
        DatastetLexicon.getInstance();

        DatastetConfiguration datastetConfiguration = null;
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            datastetConfiguration = mapper.readValue(new File("resources/config/config.yml").getAbsoluteFile(), DatastetConfiguration.class);
        } catch (Exception e) {
            LOGGER.error("The config file does not appear valid, see resources/config/config.yml", e);
            datastetConfiguration = null;
        }

        configuration.setDatastetConfiguration(datastetConfiguration);

        if (datastetConfiguration != null && datastetConfiguration.getModels() != null) {
            for (ModelParameters model : datastetConfiguration.getModels())
                GrobidProperties.getInstance().addModel(model);
        }

        Class<?> clazz = null; // if you know class name dynamically i.e. at runtime
        try {
            clazz = Class.forName("org.grobid.core.utilities.GrobidProperties");
            Field field = clazz.getDeclaredField("grobidConfig");
            field.setAccessible(true);
            GrobidConfig grobidConfig = (GrobidConfig) field.get("grobidConfig");
            grobidConfig.grobid.concurrency = configuration.getMaxParallelRequests();
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            LOGGER.error("Invalid operation when hacking the GrobidProperties", e);
        }

        LibraryLoader.load();

        // Eagerly initialise the models so that failures surface at startup
        // (and are reported via the /service/health diagnostic endpoint)
        // instead of only on the first request. This is gated on the
        // `modelPreload` configuration flag (default: enabled), matching
        // grobid's convention.
        if (isModelPreloadEnabled(datastetConfiguration)) {
            preloadModels(configuration);
        } else {
            LOGGER.info("Model preloading is disabled (modelPreload=false); " +
                    "models will be loaded lazily on first request.");
        }
    }

    private static boolean isModelPreloadEnabled(DatastetConfiguration datastetConfiguration) {
        if (datastetConfiguration == null) {
            return true;
        }
        Boolean flag = datastetConfiguration.getModelPreload();
        return flag == null || flag;
    }

    /**
     * Eagerly instantiates the three model-backed singletons used by the
     * service (the CRF dataset parser, the data-type classifier and the
     * dataset-context classifier). Each is loaded in isolation so that a
     * failure of one model does not prevent the others from being tried.
     *
     * <p>The outcome of every model load is recorded in
     * {@link ModelLoadStatus} so that {@link
     * org.grobid.service.controller.HealthCheck} can report the service as
     * unhealthy (HTTP 500) whenever any model fails to load.
     */
    private void preloadModels(DatastetServiceConfiguration configuration) {
        LOGGER.info("Preloading datastet models...");

        // CRF dataset recognition model ("datasets")
        try {
            DatasetParser.getInstance(configuration, null, null, null);
            ModelLoadStatus.markLoaded("datasets");
            LOGGER.info("Model 'datasets' loaded successfully");
        } catch (Throwable t) {
            ModelLoadStatus.markFailed("datasets", describe(t));
            LOGGER.error("Failed to load model 'datasets'", t);
        }

        // Data-type classifier: dataseer-binary, dataseer-first, dataseer-reuse
        String[] dataseerModels = {"dataseer-binary", "dataseer-first", "dataseer-reuse"};
        try {
            DataTypeClassifier.getInstance();
            for (String name : dataseerModels) {
                ModelLoadStatus.markLoaded(name);
            }
            LOGGER.info("DataTypeClassifier models loaded successfully");
        } catch (Throwable t) {
            String reason = describe(t);
            for (String name : dataseerModels) {
                ModelLoadStatus.markFailed(name, reason);
            }
            LOGGER.error("Failed to load DataTypeClassifier models", t);
        }

        // Context classifier: depending on config either "context" or the
        // three binary variants (context_used, context_creation,
        // context_shared) are loaded.
        DatastetConfiguration datastetConfig = configuration.getDatastetConfiguration();
        boolean useBinary = datastetConfig == null
                || datastetConfig.getUseBinaryContextClassifiers() == null
                || datastetConfig.getUseBinaryContextClassifiers();
        String[] contextModels = useBinary
                ? new String[]{"context_used", "context_creation", "context_shared"}
                : new String[]{"context"};
        try {
            DatasetContextClassifier.getInstance(configuration);
            for (String name : contextModels) {
                ModelLoadStatus.markLoaded(name);
            }
            LOGGER.info("DatasetContextClassifier models loaded successfully");
        } catch (Throwable t) {
            String reason = describe(t);
            for (String name : contextModels) {
                ModelLoadStatus.markFailed(name, reason);
            }
            LOGGER.error("Failed to load DatasetContextClassifier models", t);
        }

        if (ModelLoadStatus.hasFailures()) {
            LOGGER.error("Some models failed to load: {}", ModelLoadStatus.getFailedModels());
        } else {
            LOGGER.info("All datastet models loaded: {}", ModelLoadStatus.getLoadedModels().keySet());
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + msg;
    }
}
