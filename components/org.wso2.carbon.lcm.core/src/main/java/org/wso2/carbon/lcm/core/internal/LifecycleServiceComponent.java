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
package org.wso2.carbon.lcm.core.internal;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.jndi.JNDIContextManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.config.provider.ConfigProvider;
import org.wso2.carbon.datasource.core.api.DataSourceService;
import org.wso2.carbon.lcm.core.util.LifecycleUtils;
import org.wso2.carbon.lcm.sql.config.LifecycleConfigBuilder;
import org.wso2.carbon.lcm.sql.exception.LifecycleManagerDatabaseException;
import org.wso2.carbon.lcm.sql.utils.LifecycleMgtDBUtil;

/**
 * The bundle activator for lifecycle module.
 */
@Component (
        name = "org.wso2.carbon.apimgt.lifecycle.manager.core",
        immediate = true
)
public class LifecycleServiceComponent   {

    private static Logger  log = LoggerFactory.getLogger(LifecycleServiceComponent.class);
    private ConfigProvider configProvider;

    @Activate
    public void start() {
        LifecycleUtils.initiateLCMap();
        if (log.isDebugEnabled()) {
            log.debug("Lifecycle service is activated.");
        }
    }

    @Reference (
            name = "org.wso2.carbon.datasource.DataSourceService",
            service = DataSourceService.class,
            cardinality = ReferenceCardinality.AT_LEAST_ONE,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unregisterDataSourceService"
    )
    protected void onDataSourceServiceReady(DataSourceService service) {
        //this is required to enforce a dependency on datasources
    }

    @Reference(
            name = "org.wso2.carbon.datasource.jndi",
            service = JNDIContextManager.class,
            cardinality = ReferenceCardinality.AT_LEAST_ONE,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "onJNDIUnregister"
    )
    protected void onJNDIReady(JNDIContextManager service) {
        try {
            LifecycleConfigBuilder.build(configProvider);
            LifecycleMgtDBUtil.initialize();


        } catch (LifecycleManagerDatabaseException e) {
            log.error("Failed to initialize database. " + e);
        }
    }

    protected void onJNDIUnregister(JNDIContextManager jndiContextManager) {
        log.debug("Registering lifecycle data source");
    }

    protected void unregisterDataSourceService(DataSourceService dataSourceService) {
        log.debug("Un registering lifecycle data source");
    }

    /**
     * Get the ConfigProvider service.
     *
     * @param configProvider the ConfigProvider service that is registered as a service.
     */
    @Reference(
            name = "carbon.config.provider",
            service = ConfigProvider.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unregisterConfigProvider"
    )
    protected void registerConfigProvider(ConfigProvider configProvider) {
        this.configProvider = configProvider;
    }

    /**
     * This is the unbind method, which gets called for ConfigProvider instance un-registrations.
     *
     * @param configProvider the ConfigProvider service that get unregistered.
     */
    protected void unregisterConfigProvider(ConfigProvider configProvider) {
        this.configProvider = null;
    }

}
