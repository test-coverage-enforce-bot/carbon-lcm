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

package org.wso2.carbon.lcm;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.w3c.dom.Document;
import org.wso2.carbon.config.ConfigurationException;
import org.wso2.carbon.config.provider.ConfigProvider;
import org.wso2.carbon.lcm.constants.TestConstants;
import org.wso2.carbon.lcm.core.LifecycleOperationManager;
import org.wso2.carbon.lcm.core.beans.InputBean;
import org.wso2.carbon.lcm.core.beans.LifecycleNode;
import org.wso2.carbon.lcm.core.exception.LifecycleException;
import org.wso2.carbon.lcm.core.impl.LifecycleDataProvider;
import org.wso2.carbon.lcm.core.impl.LifecycleState;
import org.wso2.carbon.lcm.core.util.LifecycleUtils;
import org.wso2.carbon.lcm.sql.beans.LifecycleHistoryBean;
import org.wso2.carbon.lcm.sql.config.LifecycleConfigBuilder;
import org.wso2.carbon.lcm.sql.config.model.LifecycleConfig;
import org.wso2.carbon.lcm.sql.dao.LifecycleMgtDAO;
import org.wso2.carbon.lcm.sql.utils.LifecycleMgtDBUtil;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.Hashtable;
import java.util.List;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;
import javax.naming.spi.InitialContextFactoryBuilder;
import javax.naming.spi.NamingManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

@Test
public class LifecycleOperationsTest {

    public static LifecycleMgtDAO lifecycleMgtDAO;
    public static SampleAPI sampleAPI;
    private static Logger  log = LoggerFactory.getLogger(LifecycleOperationsTest.class);

    @BeforeClass
    protected void setUp() throws Exception {
        String dbConfigPath = System.getProperty("LCManagerDBConfigurationPath");
        setupInitialContext(dbConfigPath);
        LifecycleConfigBuilder.build(new ConfigProvider() {

            @Override public <T> T getConfigurationObject(Class<T> configClass) throws ConfigurationException {
                T lifecycleConfig = (T) new LifecycleConfig();
                return lifecycleConfig;
            }

            @Override
            public Object getConfigurationObject(String s) throws ConfigurationException {
                return null;
            }
        });
        LifecycleUtils.initiateLCMap();
        LifecycleMgtDBUtil.initialize();
        lifecycleMgtDAO = LifecycleMgtDAO.getInstance();
    }

    private static void setupInitialContext(String configFilePath) {
        try {
            NamingManager.setInitialContextFactoryBuilder(new InitialContextFactoryBuilder() {

                @Override public InitialContextFactory createInitialContextFactory(Hashtable<?, ?> environment)
                        throws NamingException {
                    return new InitialContextFactory() {

                        @Override public Context getInitialContext(Hashtable<?, ?> environment) throws NamingException {
                            return new InitialContext() {

                                private Hashtable<String, HikariDataSource> dataSources = new Hashtable<>();

                                @Override public Object lookup(String name) throws NamingException {

                                    if (dataSources.isEmpty()) { //init datasources
                                        try {
                                            File fXmlFile = new File(configFilePath);
                                            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                                            DocumentBuilder dBuilder = null;
                                            dBuilder = dbFactory.newDocumentBuilder();
                                            Document doc = null;
                                            doc = dBuilder.parse(fXmlFile);

                                            String databaseURL = doc.getElementsByTagName("URL").item(0)
                                                    .getTextContent();
                                            String databaseUser = doc.getElementsByTagName("Username").item(0)
                                                    .getTextContent();
                                            String databasePass = doc.getElementsByTagName("Password").item(0)
                                                    .getTextContent();
                                            String databaseDriver = doc.getElementsByTagName("Driver").item(0)
                                                    .getTextContent();

                                            HikariDataSource basicDataSource = new HikariDataSource();
                                            basicDataSource.setDriverClassName(databaseDriver);
                                            basicDataSource.setJdbcUrl(databaseURL);
                                            basicDataSource.setUsername(databaseUser);
                                            basicDataSource.setPassword(databasePass);
                                            dataSources.put("java:comp/env/jdbc/WSO2LifecycleDB", basicDataSource);
                                        } catch (IOException | ParserConfigurationException | SAXException e) {
                                            log.error("Error while setting datasource properties.", e);
                                        }

                                        //add more datasources to the list as necessary
                                    }

                                    if (dataSources.containsKey(name)) {
                                        return dataSources.get(name);
                                    }

                                    throw new NamingException("Unable to find datasource: " + name);
                                }
                            };
                        }

                    };
                }

            });
        } catch (NamingException e) {
            log.error("Error while setting initial context" + e);
        }
    }

    @Test
    public void testAssociateLifecycle() throws Exception {
        sampleAPI = createSampleAPI();

        sampleAPI.associateLifecycle(
                LifecycleOperationManager.addLifecycle(TestConstants.SERVICE_LIFE_CYCLE, TestConstants.ADMIN));
        assertNotNull(sampleAPI.getLifecycleState().getState());
        assertNotNull(sampleAPI.getLifecycleState().getLifecycleId());
    }

    @Test(dependsOnMethods = "testAssociateLifecycle")
    public void testValidTargetStateProvided() throws Exception {
        LifecycleState currentState = sampleAPI.getLifecycleState();
        String uuid = currentState.getLifecycleId();
        String targetState = "Production";
        try {
            sampleAPI.setLifecycleState(
                    LifecycleOperationManager.executeLifecycleEvent(targetState, uuid, TestConstants.ADMIN, sampleAPI));
        } catch (LifecycleException e) {
            assertTrue(e.getMessage()
                    .contains("The specified target state " + targetState + " is not a valid target " + "state"));
        }

    }

    @Test (dependsOnMethods = { "testAssociateLifecycle", "testValidTargetStateProvided" })
    public void testChangeLifecycleState() throws Exception {
        LifecycleState currentState = sampleAPI.getLifecycleState();
        String uuid = currentState.getLifecycleId();
        String targetState = currentState.getAvailableTransitionBeanList().get(0).getTargetState();
        // Lets set custom input values as well
        for (InputBean inputBean : currentState.getInputBeanList()) {
            inputBean.setValues("value 1");
        }
        try {
            sampleAPI.setLifecycleState(
                    LifecycleOperationManager.executeLifecycleEvent(targetState, uuid, TestConstants.ADMIN, sampleAPI));
        } catch (LifecycleException e) {
            assertTrue(e.getMessage().contains("Required checklist items are not selected"));
        }
        sampleAPI.setLifecycleState(LifecycleOperationManager
                .checkListItemEvent(uuid, sampleAPI.getLifecycleState().getState(), "Code Completed", true));
        sampleAPI.setLifecycleState(
                LifecycleOperationManager.executeLifecycleEvent(targetState, uuid, TestConstants.ADMIN, sampleAPI));
        assertEquals(sampleAPI.getLifecycleState().getState(), targetState);

    }

    @Test(dependsOnMethods = "testChangeLifecycleState")
    public void testGettingLifecycleHistory () throws Exception {
        String uuid = sampleAPI.getLifecycleState().getLifecycleId();
        List<LifecycleHistoryBean> lifecycleHistoryBeanList = LifecycleDataProvider.getLifecycleHistory(uuid);
        assertTrue(lifecycleHistoryBeanList.size() == 2);
        assertTrue(TestConstants.DEVELOPMENT.equals(lifecycleHistoryBeanList.get(0).getPostState()));
        assertTrue(TestConstants.TESTING.equals(lifecycleHistoryBeanList.get(1).getPostState()));
    }

    @Test(dependsOnMethods = "testChangeLifecycleState")
    public void testGetLifecycleIdsFromState() throws Exception {
        List<String> stateList = LifecycleDataProvider
                .getIdsFromState(TestConstants.TESTING, TestConstants.SERVICE_LIFE_CYCLE);
        assertTrue(stateList.size() == 1);
    }

    @Test(dependsOnMethods = "testGetLifecycleIdsFromState")
    public void testDissociateLifecycle() throws Exception {
        LifecycleOperationManager.removeLifecycle(sampleAPI.getLifecycleState().getLifecycleId());
        try {
            LifecycleOperationManager.getCurrentLifecycleState(sampleAPI.getLifecycleState().getLifecycleId());
        } catch (LifecycleException e) {
            assertTrue(e.getMessage().contains("Error while getting lifecycle data for id"));
        }
    }

    @Test
    public void testGetLifecycleGraph() throws Exception {
        List<LifecycleNode> graph = LifecycleDataProvider.getLifecycleGraph(TestConstants.API_LIFE_CYCLE);
        assertTrue(graph.size() == 6);
    }

    private SampleAPI createSampleAPI() {
        SampleAPI sampleAPI = new SampleAPI();
        sampleAPI.setName("API 1");
        sampleAPI.setVersion("1.0.0");
        return sampleAPI;
    }

}
