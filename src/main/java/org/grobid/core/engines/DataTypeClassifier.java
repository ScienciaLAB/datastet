package org.grobid.core.engines;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.jni.DeLFTClassifierModel;
import org.grobid.core.utilities.DatastetConfiguration;
import org.grobid.core.utilities.DatastetUtilities;
import org.grobid.core.utilities.GrobidConfig.ModelParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * Data type classifier for dataset sentences via DeLFT.
 *
 * @author Patrice
 */
public class DataTypeClassifier {
    private static final Logger logger = LoggerFactory.getLogger(DataTypeClassifier.class);

    private static volatile DataTypeClassifier instance;

    private DeLFTClassifierModel classifierBinary = null;
    private DeLFTClassifierModel classifierFirstLevel = null;
    private DeLFTClassifierModel classifierReuse = null;

    private DatastetConfiguration datastetConfiguration = null;

    public static DataTypeClassifier getInstance() {
        if (instance == null) {
            getNewInstance();
        }
        return instance;
    }

    /**
     * Create a new instance.
     */
    private static synchronized void getNewInstance() {
        instance = new DataTypeClassifier();
    }

    private DataTypeClassifier() {
        try {
            this.datastetConfiguration = null;
            try {
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

                File configFile = new File("resources/config/config.yml").getAbsoluteFile();
                datastetConfiguration = mapper.readValue(configFile, DatastetConfiguration.class);
            } catch(Exception e) {
                logger.error("The config file does not appear valid, see resources/config/config.yml", e);
            }

            // Datatype classifier via DeLFT
            for(ModelParameters parameter : datastetConfiguration.getModels()) {
                if (parameter.name.equals("dataseer-binary")) {
                    this.classifierBinary = new DeLFTClassifierModel("dataseer-binary", parameter.delft.architecture);
                } else if (parameter.name.equals("dataseer-first")) {
                    this.classifierFirstLevel = new DeLFTClassifierModel("dataseer-first", parameter.delft.architecture);
                } else if (parameter.name.equals("dataseer-reuse")) {
                    this.classifierReuse = new DeLFTClassifierModel("dataseer-reuse", parameter.delft.architecture);
                }
            }

        } catch (Exception e) {
            throw new GrobidException("Cannot initialise DataType classifier", e);
        }
    }

    public DatastetConfiguration getDatastetConfiguration() {
        return this.datastetConfiguration;
    }

    /**
     * Classify a simple piece of text
     * @return JSON string
     */
    public String classify(String text) throws Exception {
        if (StringUtils.isEmpty(text))
            return null;
        List<String> texts = new ArrayList<>();
        texts.add(text);
        return classify(texts);
    }

    /**
     * Classify a simple piece of text whether it refers to some dataset or not
     * @return JSON string
     */
    public String classifyBinary(String text) throws Exception {
        if (StringUtils.isEmpty(text))
            return null;
        List<String> texts = new ArrayList<String>();
        texts.add(text);
        return classifyBinary(texts);
    }

    /**
     * Classify a simple piece of text for the data type of a referenced dataset
     * @return JSON string
     */
    public String classifyFirstLevel(String text) throws Exception {
        if (StringUtils.isEmpty(text))
            return null;
        List<String> texts = new ArrayList<String>();
        texts.add(text);
        return classifyFirstLevel(texts);
    }

    /**
     * Classify an array of texts
     * @return JSON string
     */
    public String classify(List<String> texts) throws Exception {

        if (CollectionUtils.isEmpty(texts))
            return null;
        logger.info("classify: " + texts.size() + " sentence(s)");
        ObjectMapper mapper = new ObjectMapper();

        String the_json = classifierBinary.classify(texts);
        // first pass to select texts to be cascaded to next level
        List<String> cascaded_texts = new ArrayList<>();
        JsonNode root = null;
        if (the_json != null && the_json.length() > 0) {
            root = mapper.readTree(the_json);
            JsonNode classificationsNode = root.findPath("classifications");
            if ((classificationsNode != null) && (!classificationsNode.isMissingNode())) {
                Iterator<JsonNode> ite = classificationsNode.elements();
                while (ite.hasNext()) {
                    JsonNode classificationNode = ite.next();
                    JsonNode datasetNode = classificationNode.findPath("dataset");
                    JsonNode noDatasetNode = classificationNode.findPath("no_dataset");

                    if ((datasetNode != null) && (!datasetNode.isMissingNode()) &&
                        (noDatasetNode != null) && (!noDatasetNode.isMissingNode()) ) {
                        double probDataset = datasetNode.asDouble();
                        double probNoDataset = noDatasetNode.asDouble();

                        if (probDataset > probNoDataset) {
                            JsonNode textNode = classificationNode.findPath("text");
                            cascaded_texts.add(textNode.asText());
                        }

                        // rename "dataset" attribute to avoid confusion with "Dataset" type of the taxonomy
                        ((ObjectNode)classificationNode).put("has_dataset", probDataset);
                        ((ObjectNode)classificationNode).remove("dataset");
                    }
                }
            }
        }
        String cascaded_json = null;
        JsonNode rootCascaded = null;
        if (cascaded_texts.size() > 0) {
            cascaded_json = classifierFirstLevel.classify(cascaded_texts);
            if (cascaded_json != null && cascaded_json.length() > 0)
                rootCascaded = mapper.readTree(cascaded_json);
        }

        if (rootCascaded == null) {
            return this.shadowModelName(the_json);
        }

        // application of the reuse model on the positive texts
        String cascaded_reuse_json = null;
        JsonNode rootReuseCascaded = null;
        if (cascaded_texts.size() > 0) {
            cascaded_reuse_json = classifierReuse.classify(cascaded_texts);
            if (cascaded_reuse_json != null && cascaded_reuse_json.length() > 0)
                rootReuseCascaded = mapper.readTree(cascaded_reuse_json);
        }

        StringBuilder builder = new StringBuilder();
        builder.append("{\n\t\"model\": \"dataseer\",\n\t\"software\": \"DeLFT\",\n\t\"date\": \"" +
            DatastetUtilities.getISO8601Date() + "\",\n\t\"classifications\": [");

        boolean first = true;
        // second pass to inject additional results
        if (root != null && rootCascaded != null && rootReuseCascaded != null) {
            JsonNode classificationsNode = root.findPath("classifications");
            JsonNode classificationsCascadedNode = rootCascaded.findPath("classifications");
            JsonNode classificationsReuseCascadedNode = rootReuseCascaded.findPath("classifications");
            if ((classificationsNode != null) && (!classificationsNode.isMissingNode()) &&
                (classificationsCascadedNode != null) && (!classificationsCascadedNode.isMissingNode()) &&
                (classificationsReuseCascadedNode != null) && (!classificationsReuseCascadedNode.isMissingNode())) {
                Iterator<JsonNode> ite = classificationsNode.elements();
                Iterator<JsonNode> iteCascaded = classificationsCascadedNode.elements();
                Iterator<JsonNode> iteReuseCascaded = classificationsReuseCascadedNode.elements();
                while (ite.hasNext()) {
                    JsonNode classificationNode = ite.next();
                    JsonNode datasetNode = classificationNode.findPath("has_dataset");
                    JsonNode noDatasetNode = classificationNode.findPath("no_dataset");

                    if ((datasetNode != null) && (!datasetNode.isMissingNode()) &&
                        (noDatasetNode != null) && (!noDatasetNode.isMissingNode()) ) {
                        double probDataset = datasetNode.asDouble();
                        double probNoDataset = noDatasetNode.asDouble();

                        if (probDataset > probNoDataset) {
                            JsonNode textNode = classificationNode.findPath("text");
                            if (iteCascaded.hasNext()) {
                                JsonNode classificationCascadedNode = iteCascaded.next();
                                // inject dataset/no_dataset probabilities as extra-information relevant for post-processing
                                ((ObjectNode)classificationCascadedNode).put("has_dataset", probDataset);
                                ((ObjectNode)classificationCascadedNode).put("no_dataset", probNoDataset);

                                if (iteReuseCascaded.hasNext()) {
                                    JsonNode classificationReuseCascadedNode = iteReuseCascaded.next();
                                    JsonNode reuseNode = classificationReuseCascadedNode.findPath("reuse");
                                    JsonNode noReuseNode = classificationReuseCascadedNode.findPath("not_reuse");

                                    if ((reuseNode != null) && (!reuseNode.isMissingNode()) &&
                                        (noReuseNode != null) && (!noReuseNode.isMissingNode()) ) {
                                        double probReuse = reuseNode.asDouble();
                                        double probNoReuse = noReuseNode.asDouble();

                                        if (probReuse > probNoReuse) {
                                            ((ObjectNode)classificationCascadedNode).put("reuse", true);
                                        } else {
                                            ((ObjectNode)classificationCascadedNode).put("reuse", false);
                                        }
                                    }
                                }

                                if (first)
                                    first = false;
                                else
                                    builder.append(",");
                                builder.append("\n\t\t");
                                builder.append(this.prettyPrintJsonNode(classificationCascadedNode, mapper));
                            }
                        } else {
                            if (first)
                                first = false;
                            else
                                builder.append(",");
                            builder.append("\n\t\t");
                            builder.append(this.prettyPrintJsonNode(classificationNode, mapper));
                        }
                    }
                }
            }
        }
        builder.append("\n\t]\n}");

        if (the_json != null) {
            String finalJson = builder.toString();
            return prettyPrintJsonString(finalJson, mapper);
        }
        else
            return null;
    }

    /**
     * Classify a simple piece of text whether it refers to some dataset or not
     * @return JSON string
     */
    public String classifyBinary(List<String> texts) throws Exception {
        if (texts == null || texts.size() == 0)
            return null;
        logger.info("classify: " + texts.size() + " sentence(s)");
        ObjectMapper mapper = new ObjectMapper();

        String the_json = classifierBinary.classify(texts);
        JsonNode root = null;
        if (the_json != null && the_json.length() > 0) {
            root = mapper.readTree(the_json);
            JsonNode classificationsNode = root.findPath("classifications");
            if ((classificationsNode != null) && (!classificationsNode.isMissingNode())) {
                Iterator<JsonNode> ite = classificationsNode.elements();
                while (ite.hasNext()) {
                    JsonNode classificationNode = ite.next();
                    JsonNode datasetNode = classificationNode.findPath("dataset");
                    JsonNode noDatasetNode = classificationNode.findPath("no_dataset");

                    if ((datasetNode != null) && (!datasetNode.isMissingNode()) &&
                        (noDatasetNode != null) && (!noDatasetNode.isMissingNode()) ) {
                        double probDataset = datasetNode.asDouble();
                        double probNoDataset = noDatasetNode.asDouble();

                        // rename "dataset" attribute to avoid confusion with "Dataset" type of the taxonomy
                        ((ObjectNode)classificationNode).put("has_dataset", probDataset);
                        ((ObjectNode)classificationNode).remove("dataset");
                    }
                }
            }
        }

        return this.shadowModelName(the_json);
    }

    /**
     * Classify a simple piece of text for the data type of a referenced dataset
     * @return JSON string
     */
    public String classifyFirstLevel(List<String> texts) throws Exception {
        if (texts == null || texts.size() == 0)
            return null;
        logger.info("classify: " + texts.size() + " sentence(s)");
        ObjectMapper mapper = new ObjectMapper();

        JsonNode rootCascaded = null;
        String cascaded_json = classifierFirstLevel.classify(texts);
        if (cascaded_json != null && cascaded_json.length() > 0)
            rootCascaded = mapper.readTree(cascaded_json);

        String finalJson = this.shadowModelName(cascaded_json);
        return prettyPrintJsonString(finalJson, mapper);
    }

    private String shadowModelName(String the_json) {
        if (the_json == null || the_json.trim().length() == 0)
            return the_json;
        the_json = the_json.replace("\"model\": \"dataseer-binary\",", "\"model\": \"dataseer\",");
        return the_json.replace("\"model\": \"dataseer-first\",", "\"model\": \"dataseer\",");
    }

    public String prettyPrintJsonNode(JsonNode jsonNode, ObjectMapper mapper) {
        if (jsonNode == null || jsonNode.isMissingNode())
            return null;
        try {
            Object json = mapper.readValue(jsonNode.toString(), Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public String prettyPrintJsonString(String json, ObjectMapper mapper) {
        try {
            Object root = mapper.readValue(json, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
