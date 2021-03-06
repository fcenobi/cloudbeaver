/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2020 DBeaver Corp and others
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
package io.cloudbeaver.service.core.impl;


import io.cloudbeaver.DBWebException;
import io.cloudbeaver.WebServiceUtils;
import io.cloudbeaver.model.*;
import io.cloudbeaver.model.session.WebSession;
import io.cloudbeaver.server.CBApplication;
import io.cloudbeaver.server.CBPlatform;
import io.cloudbeaver.service.core.DBWServiceCore;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
import org.jkiss.dbeaver.registry.DataSourceNavigatorSettings;
import org.jkiss.dbeaver.runtime.jobs.ConnectionTestJob;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web service implementation
 */
public class WebServiceCore implements DBWServiceCore {

    private static final Log log = Log.getLog(WebServiceCore.class);

    @Override
    public WebServerConfig getServerConfig() {
        return new WebServerConfig(CBApplication.getInstance());
    }

    @Override
    public List<WebDatabaseDriverConfig> getDriverList(WebSession webSession, String driverId) {
        List<WebDatabaseDriverConfig> result = new ArrayList<>();
        for (DBPDriver driver : CBPlatform.getInstance().getApplicableDrivers()) {
            if (driverId == null || driverId.equals(WebServiceUtils.makeDriverFullId(driver))) {
                result.add(new WebDatabaseDriverConfig(webSession, driver));
            }
        }
        return result;
    }

    @Override
    public List<WebDataSourceConfig> getGlobalDataSources() throws DBWebException {

        List<WebDataSourceConfig> result = new ArrayList<>();
        DBPDataSourceRegistry dsRegistry = WebServiceUtils.getDataSourceRegistry();

        for (DBPDataSourceContainer ds : dsRegistry.getDataSources()) {
            if (CBPlatform.getInstance().getApplicableDrivers().contains(ds.getDriver()) && !ds.isProvided()) {
                result.add(new WebDataSourceConfig(ds));
            } else {
                log.debug("Global datasource '" + ds.getName() + "' ignored - driver is not applicable");
            }
        }

        return result;
    }

    @Override
    public String[] getSessionPermissions(WebSession webSession) throws DBWebException {
        try {
            return webSession.getSessionPermissions().toArray(new String[0]);
        } catch (DBCException e) {
            throw new DBWebException("Error reading session permissions", e);
        }
    }

    @Override
    public WebSession openSession(WebSession webSession) {
        return webSession;
    }

    @Override
    public WebSession getSessionState(WebSession webSession) {
        return webSession;
    }

    @Override
    public List<WebServerMessage> readSessionLog(WebSession webSession, Integer maxEntries, Boolean clearEntries) {
        return webSession.readLog(maxEntries, clearEntries);
    }

    @Override
    public boolean closeSession(HttpServletRequest request) {
        return CBPlatform.getInstance().getSessionManager().closeSession(request);
    }

    @Override
    public boolean touchSession(HttpServletRequest request) throws DBWebException {
        return CBPlatform.getInstance().getSessionManager().touchSession(request);
    }

    @Override
    public boolean changeSessionLanguage(WebSession webSession, String locale) {
        webSession.setLocale(locale);
        return true;
    }

    @Override
    public WebConnectionInfo getConnectionState(WebSession webSession, String connectionId) throws DBWebException {
        return webSession.getWebConnectionInfo(connectionId);
    }

    @Override
    public WebConnectionInfo openConnection(WebSession webSession, WebConnectionConfig config) throws DBWebException {
        String dataSourceId = config.getDataSourceId();
        if (CommonUtils.isEmpty(dataSourceId)) {
            throw new DBWebException("Only preconfigured data sources are supported yet");
        }
        DBPDataSourceRegistry templateRegistry = WebServiceUtils.getDataSourceRegistry();
        DBPDataSourceContainer dataSourceTemplate = templateRegistry.getDataSource(dataSourceId);
        if (dataSourceTemplate == null) {
            throw new DBWebException("Datasource '" + dataSourceId + "' not found");
        }

        DBPDataSourceRegistry sessionRegistry = webSession.getDatabasesNode().getDataSourceRegistry();
        DBPDataSourceContainer newDataSource = sessionRegistry.createDataSource(dataSourceTemplate);
        newDataSource.setSavePassword(true);
        ((DataSourceDescriptor)newDataSource).setTemporary(true);

        DBPConnectionConfiguration cfg = newDataSource.getConnectionConfiguration();
        cfg.setUserName(config.getUserName());
        cfg.setUserPassword(config.getUserPassword());
        if (!CommonUtils.isEmpty(config.getName())) {
            newDataSource.setName(config.getName());
        }
        if (!CommonUtils.isEmpty(config.getDescription())) {
            newDataSource.setDescription(config.getDescription());
        }
        try {
            newDataSource.connect(webSession.getProgressMonitor(), true, false);
        } catch (DBException e) {
            throw new DBWebException("Error connecting to database", e);
        }
        sessionRegistry.addDataSource(newDataSource);

        WebConnectionInfo connectionInfo = new WebConnectionInfo(webSession, newDataSource);
        webSession.addConnection(connectionInfo);

        return connectionInfo;
    }

    @Override
    public WebConnectionInfo createConnection(WebSession webSession, WebConnectionConfig connectionConfig) throws DBWebException {
        DBPDataSourceRegistry sessionRegistry = webSession.getDatabasesNode().getDataSourceRegistry();

        DBPDataSourceContainer newDataSource = makeConnectionInstance(connectionConfig, sessionRegistry);
        if (CommonUtils.isEmpty(newDataSource.getName())) {
            newDataSource.setName(CommonUtils.notNull(connectionConfig.getName(), "NewConnection"));
        }
        ((DataSourceDescriptor)newDataSource).setTemporary(true);
        sessionRegistry.addDataSource(newDataSource);

        WebConnectionInfo connectionInfo = new WebConnectionInfo(webSession, newDataSource);
        webSession.addConnection(connectionInfo);

        return connectionInfo;
    }

    @Override
    public WebConnectionInfo testConnection(WebSession webSession, WebConnectionConfig connectionConfig) throws DBWebException {
        DBPDataSourceRegistry sessionRegistry = webSession.getDatabasesNode().getDataSourceRegistry();

        DBPDataSourceContainer newDataSource = makeConnectionInstance(connectionConfig, sessionRegistry);
        try {
            ConnectionTestJob ct = new ConnectionTestJob(newDataSource, param -> {});
            ct.run(webSession.getProgressMonitor());
            if (ct.getConnectError() != null) {
                throw new DBWebException("Connection failed", ct.getConnectError());
            }
            WebConnectionInfo connectionInfo = new WebConnectionInfo(webSession, newDataSource);
            connectionInfo.setConnectError(ct.getConnectError());
            connectionInfo.setServerVersion(ct.getServerVersion());
            connectionInfo.setClientVersion(ct.getClientVersion());
            connectionInfo.setConnectTime(RuntimeUtils.formatExecutionTime(ct.getConnectTime()));
            return connectionInfo;
        } catch (DBException e) {
            throw new DBWebException("Error connecting to database", e);
        }
    }

    @Override
    public boolean closeConnection(WebSession webSession, String connectionId) throws DBWebException {
        WebConnectionInfo connectionInfo = webSession.getWebConnectionInfo(connectionId);

        boolean disconnected = false;
        DBPDataSourceContainer dataSourceContainer = connectionInfo.getDataSourceContainer();
        if (connectionInfo.isConnected()) {
            try {
                dataSourceContainer.disconnect(webSession.getProgressMonitor());
                disconnected = true;
            } catch (DBException e) {
                log.error("Error closing connection", e);
            }
            // Disconnect in async mode?
            //new DisconnectJob(connectionInfo.getDataSource()).schedule();
        }
        if (dataSourceContainer.isTemporary()) {
            webSession.getDatabasesNode().getDataSourceRegistry().removeDataSource(dataSourceContainer);
            webSession.removeConnection(connectionInfo);
        }

        return disconnected;
    }

    @NotNull
    private DBPDataSourceContainer makeConnectionInstance(WebConnectionConfig config, DBPDataSourceRegistry sessionRegistry) throws DBWebException {
        String driverId = config.getDriverId();
        if (CommonUtils.isEmpty(driverId)) {
            throw new DBWebException("Driver not specified");
        }
        DBPDriver driver = WebServiceUtils.getDriverById(driverId);

        DBPConnectionConfiguration dsConfig = new DBPConnectionConfiguration();

        DBPDataSourceContainer newDataSource = sessionRegistry.createDataSource(driver, dsConfig);
        newDataSource.setSavePassword(true);

        if (!CommonUtils.isEmpty(config.getUrl())) {
            dsConfig.setUrl(config.getUrl());
        } else {
            dsConfig.setHostName(config.getHost());
            dsConfig.setHostPort(config.getPort());
            dsConfig.setDatabaseName(config.getDatabaseName());
            dsConfig.setServerName(config.getServerName());
            dsConfig.setUrl(driver.getDataSourceProvider().getConnectionURL(driver, dsConfig));
        }
        if (config.getProperties() != null) {
            for (Map.Entry<String, Object> pe : config.getProperties().entrySet()) {
                dsConfig.setProperty(pe.getKey(), CommonUtils.toString(pe.getValue()));
            }
        }
        dsConfig.setUserName(config.getUserName());
        dsConfig.setUserPassword(config.getUserPassword());
        newDataSource.setName(config.getName());
        newDataSource.setDescription(config.getDescription());

        // Set default navigator settings
        DataSourceNavigatorSettings navSettings = new DataSourceNavigatorSettings(newDataSource.getNavigatorSettings());
        navSettings.setShowSystemObjects(false);
        ((DataSourceDescriptor)newDataSource).setNavigatorSettings(navSettings);

        return newDataSource;
    }


    @Override
    public WebAsyncTaskInfo getAsyncTaskStatus(WebSession webSession, String taskId) throws DBWebException {
        return webSession.asyncTaskStatus(taskId);
    }

    @Override
    public boolean cancelAsyncTask(WebSession webSession, String taskId) throws DBWebException {
        return webSession.asyncTaskCancel(taskId);
    }

}
