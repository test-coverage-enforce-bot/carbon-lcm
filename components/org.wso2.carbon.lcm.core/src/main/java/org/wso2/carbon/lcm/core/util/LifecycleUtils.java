/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.lcm.core.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.wso2.carbon.lcm.core.constants.LifecycleConstants;
import org.wso2.carbon.lcm.core.exception.LifecycleException;
import org.wso2.carbon.utils.Utils;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

/**
 * This utility class provides methods to perform CRUD operations for lifecycle configurations.
 */
public class LifecycleUtils {

    private static final Logger log = LoggerFactory.getLogger(LifecycleUtils.class);
    private static Map<String, Document> lifecycleMap;
    private static Validator lifecycleSchemaValidator;

    /**
     * Get the lifecycle configuration with a particular name.
     *
     * @param lcName                Name of the lifecycle.
     * @return                      Lifecycle configuration.
     * @throws LifecycleException   If failed to get lifecycle configurations.
     */
    public static Document getLifecycleConfiguration(String lcName) throws LifecycleException {
        if (lifecycleMap != null && lifecycleMap.containsKey(lcName)) {
            return lifecycleMap.get(lcName);
        }
        throw new LifecycleException("Lifecycle configuration does not exist with name " + lcName);
    }

    /**
     * Initiates the static lifecycle map during startup.
     */
    //TODO : move to seperate class. not util
    public static void initiateLCMap() {
        lifecycleMap = new ConcurrentHashMap<>();
        String defaultLifecycleConfigLocation = getDefaltLifecycleConfigLocation();
        File defaultLifecycleConfigDirectory = new File(defaultLifecycleConfigLocation);
        if (!defaultLifecycleConfigDirectory.exists()) {
            return;
        }

        final FilenameFilter filenameFilter = (dir, name) -> name.endsWith(".xml");

        File[] lifecycleConfigFiles = defaultLifecycleConfigDirectory.listFiles(filenameFilter);
        if (lifecycleConfigFiles == null || lifecycleConfigFiles.length == 0) {
            return;
        }

        for (File lifecycleConfigFile : lifecycleConfigFiles) {
            String fileName = FilenameUtils.removeExtension(lifecycleConfigFile.getName());
            //here configuration file name should be same as aspect name
            String fileContent = null;

            try {
                fileContent = FileUtils.readFileToString(lifecycleConfigFile);
            } catch (IOException e) {
                String msg = String.format("Error while reading lifecycle config file %s ", fileName);
                log.error(msg, e);
                    /* The exception is not thrown, because if we throw the error, the for loop will be broken and
                    other files won't be read */
            }
            if ((fileContent != null) && !fileContent.isEmpty()) {
                try {
                    Document lcConfig = getLifecycleElement(fileContent);
                    Element element = (Element) lcConfig
                            .getElementsByTagName(LifecycleConstants.ASPECT).item(0);
                    String lcName = element.getAttribute("name");
                    if (fileName.equalsIgnoreCase(lcName)) {
                        validateLifecycleContent(fileContent);
                        validateSCXMLDataModel(lcConfig);
                        getLifecycleMapInstance().put(lcName, lcConfig);
                    } else {
                        String msg = String
                                .format("Configuration file name %s not matched with lifecycle name %s ", fileName,
                                        lcName);
                        log.error(msg);
                            /* The error is not thrown, because if we throw the error, the for loop will be broken and
                            other files won't be read */
                    }
                } catch (LifecycleException e) {
                    String msg = String.format("Error while adding lifecycle %s ", fileName);
                    log.error(msg, e);

                }
            }
        }

    }

    /**
     * This method is used to get the initial state defined in the lifecycle
     *
     * @param lcName : lifecycle name
     * @return : initial state
     * @throws LifecycleException  If failed to get initial state.
     */
    public static String getInitialState(String lcName) throws LifecycleException {
        return LifecycleOperationUtil.getInitialState(getLifecycleConfiguration(lcName), lcName);
    }

    /**
     * This method is used to read lifecycle config and provide permission details associated with each state change.
     *
     * @param lcConfig                          Lifecycle configuration element.
     * @return                                  Document element for the lifecycle confi
     * @throws LifecycleException               If failed to get lifecycle element.
     */
    public static Document getLifecycleElement(String lcConfig) throws LifecycleException {

        try {
            InputStream inputStream = new ByteArrayInputStream(lcConfig.getBytes(StandardCharsets.UTF_8));
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setNamespaceAware(false);
            Document document = documentBuilderFactory.newDocumentBuilder().parse(inputStream);
            return document;
        } catch (SAXException | IOException | ParserConfigurationException e) {
            throw new LifecycleException("Error while building lifecycle config document element", e);
        }
    }

    /**
     * Method used to validate lifecycle config adheres to the schema.
     * @param lcConfig              Lifecycle configuration element.
     * @throws LifecycleException   If validation fails.
     */
    public static void validateLifecycleContent(String lcConfig) throws LifecycleException {
        if (!validateLifecycleContent(lcConfig, getLifecycleSchemaValidator(getLifecycleSchemaLocation()))) {
            String message = "Unable to validate the lifecycle configuration";
            log.error(message);
            throw new LifecycleException(message);
        }
    }

    private static boolean validateLifecycleContent(String lcConfig, Validator validator) {
        try {
            InputStream is = new ByteArrayInputStream(lcConfig.getBytes("utf-8"));
            Source xmlFile = new StreamSource(is);
            if (validator != null) {
                validator.validate(xmlFile);
            } else {
                log.error(
                        "Lifecycle schema validator not found. Check the existence  of resources/lifecycle-config.xsd");
            }
        } catch (SAXException e) {
            log.error("Unable to parse the XML configuration. Please validate the XML configuration", e);
            return false;
        } catch (UnsupportedEncodingException e) {
            log.error("Unsupported content", e);
            return false;
        } catch (IOException e) {
            log.error("Unable to parse the XML configuration. Please validate the XML configuration", e);
            return false;
        }
        return true;
    }

    private static void validateSCXMLDataModel(Document lcConfig) throws LifecycleException {
        NodeList stateList = lcConfig.getElementsByTagName(LifecycleConstants.STATE_TAG);

        for (int i = 0; i < stateList.getLength(); i++) {
            List<String> targetValues = new ArrayList<>();
            if (stateList.item(i).getNodeType() == Node.ELEMENT_NODE) {
                Element state = (Element) stateList.item(i);
                String stateName = state.getAttribute("id");
                NodeList targetList = state.getElementsByTagName(LifecycleConstants.TRANSITION_ATTRIBUTE);
                for (int targetCount = 0; targetCount < targetList.getLength(); targetCount++) {
                    if (targetList.item(targetCount).getNodeType() == Node.ELEMENT_NODE) {
                        Element target = (Element) targetList.item(targetCount);
                        targetValues.add(target.getAttribute(LifecycleConstants.TARGET_ATTRIBUTE));
                    }
                }
                XPath xPathInstance = XPathFactory.newInstance().newXPath();
                String xpathQuery = "//state[@id='" + stateName + "']//@forTarget";
                try {
                    XPathExpression exp = xPathInstance.compile(xpathQuery);
                    NodeList forTargetNodeList = (NodeList) exp.evaluate(state, XPathConstants.NODESET);
                    for (int forTargetCount = 0; forTargetCount < forTargetNodeList.getLength(); forTargetCount++) {
                        if (forTargetNodeList.item(forTargetCount).getNodeType() == Node.ATTRIBUTE_NODE) {
                            Attr attr = (Attr) forTargetNodeList.item(forTargetCount);
                            if (!"".equals(attr.getValue()) && !targetValues.contains(attr.getValue())) {
                                throw new LifecycleException("forTarget attribute value " + attr.getValue()
                                        + " does not included as target state in the state object " + stateName);
                            }
                        }
                    }
                } catch (XPathExpressionException e) {
                    throw new LifecycleException("Error while reading for target attributes ", e);
                }

            }
        }

    }

    /**
     * Method used to get schema validator object for lifecycle configurations.
     * @param schemaPath Schema path in the server extracted directory.
     * @return schema validator object
     */
    public static synchronized Validator getLifecycleSchemaValidator(String schemaPath) {
        if (lifecycleSchemaValidator != null) {
            return lifecycleSchemaValidator;
        }
        try {
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = schemaFactory.newSchema(new File(schemaPath));
            lifecycleSchemaValidator = schema.newValidator();
        } catch (SAXException e) {
            log.error("Unable to get a schema validator from the given file path : " + schemaPath);
        }
        return lifecycleSchemaValidator;
    }

    private static synchronized  Map<String, Document> getLifecycleMapInstance() {
        if (lifecycleMap == null) {
            lifecycleMap = new ConcurrentHashMap<>();
        }

        return lifecycleMap;
    }

    /**
     * This method will return the lifecycle schema location in the server directory.
     * @return schema location.
     */
    private static String getLifecycleSchemaLocation() {
        return Utils.getCarbonHome() + File.separator + "resources" + File.separator + "lifecycle-config.xsd";
    }

    private static String getDefaltLifecycleConfigLocation() {
        return Utils.getCarbonHome() + File.separator + "resources" + File.separator + "lifecycles";
    }
}
