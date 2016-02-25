/*
 * Copyright 2012-2016 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.hub;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.client.ResourceAccessException;

import com.google.gson.Gson;
import com.marklogic.appdeployer.AppConfig;
import com.marklogic.appdeployer.ConfigDir;
import com.marklogic.appdeployer.command.Command;
import com.marklogic.appdeployer.command.appservers.UpdateRestApiServersCommand;
import com.marklogic.appdeployer.command.databases.DeployContentDatabasesCommand;
import com.marklogic.appdeployer.command.databases.DeploySchemasDatabaseCommand;
import com.marklogic.appdeployer.command.databases.DeployTriggersDatabaseCommand;
import com.marklogic.appdeployer.command.modules.AssetModulesFinder;
import com.marklogic.appdeployer.command.restapis.DeployRestApiServersCommand;
import com.marklogic.appdeployer.command.security.DeployRolesCommand;
import com.marklogic.appdeployer.command.security.DeployUsersCommand;
import com.marklogic.appdeployer.impl.SimpleAppDeployer;
import com.marklogic.client.modulesloader.Modules;
import com.marklogic.client.modulesloader.ModulesFinder;
import com.marklogic.client.modulesloader.ModulesManager;
import com.marklogic.client.modulesloader.impl.DefaultModulesLoader;
import com.marklogic.hub.commands.LoadModulesCommand;
import com.marklogic.hub.util.GsonUtil;
import com.marklogic.mgmt.ManageClient;
import com.marklogic.mgmt.ManageConfig;
import com.marklogic.mgmt.admin.AdminConfig;
import com.marklogic.mgmt.admin.AdminManager;
import com.marklogic.mgmt.appservers.ServerManager;

public class DataHub {

    static final private Logger LOGGER = LoggerFactory.getLogger(DataHub.class);

    private ManageConfig config;
    private ManageClient client;
    public static String HUB_NAME = "data-hub-in-a-box";
    public static int FORESTS_PER_HOST = 4;
    private String host;
    private int restPort;
    private String username;
    private String password;

    private File assetInstallTimeFile;

    private final static int DEFAULT_REST_PORT = 8010;

    public DataHub(HubConfig config) {
        this(config.getHost(), config.getAdminUsername(), config.getAdminPassword());
    }

    public DataHub(String host, String username, String password) {
        this(host, DEFAULT_REST_PORT, username, password);
    }

    public DataHub(String host, int restPort, String username, String password) {
        config = new ManageConfig(host, 8002, username, password);
        client = new ManageClient(config);
        this.host = host;
        this.restPort = restPort;
        this.username = username;
        this.password = password;
    }
    
    public void setAssetInstallTimeFile(File assetInstallTimeFile) {
        this.assetInstallTimeFile = assetInstallTimeFile;
    }
    
    /**
     * Determines if the data hub is installed in MarkLogic
     * @return true if installed, false otherwise
     */
    public boolean isInstalled() {
        ServerManager sm = new ServerManager(client);
        return sm.exists("data-hub-in-a-box");
    }

    /**
     * Validates the MarkLogic server to ensure compatibility with the hub
     * @throws ServerValidationException if the server is not compatible
     */
    public void validateServer() throws ServerValidationException {
        try {
            AdminConfig adminConfig = new AdminConfig();
            adminConfig.setHost(host);
            adminConfig.setUsername(username);
            adminConfig.setPassword(password);
            AdminManager am = new AdminManager(adminConfig);
            String versionString = am.getServerVersion();
            int major = Integer.parseInt(versionString.substring(0, 1));
            int minor = Integer.parseInt(versionString.substring(2, 3) + versionString.substring(4, 5));
            if (major < 8 || minor < 4) {
                throw new ServerValidationException("Invalid MarkLogic Server Version: " + versionString);
            }
        }
        catch(ResourceAccessException e) {
            throw new ServerValidationException(e.toString());
        }
    }

    private AppConfig getAppConfig() throws IOException {
        AppConfig config = new AppConfig();
        config.setHost(host);
        config.setRestPort(restPort);
        config.setName(HUB_NAME);
        config.setRestAdminUsername(username);
        config.setRestAdminPassword(password);
        List<String> paths = new ArrayList<String>();
        paths.add(new ClassPathResource("ml-modules").getPath());
        config.setConfigDir(new ConfigDir(new File(new ClassPathResource("ml-config").getPath())));
        config.setModulePaths(paths);
        return config;
    }

    /**
     * Installs the data hub configuration and server-side modules into MarkLogic
     * @throws IOException
     */
    public void install() throws IOException {
        AdminManager manager = new AdminManager();
        AppConfig config = getAppConfig();
        SimpleAppDeployer deployer = new SimpleAppDeployer(client, manager);
        deployer.setCommands(getCommands(config));
        deployer.deploy(config);
    }

    /**
     * Installs User Provided modules into the Data Hub
     *
     * @param pathToUserModules
     *            - the absolute path to the user's modules folder
     * @return the canonical/absolute path of files that was loaded, together
     *         with its install time
     * @throws IOException
     */
    public Map<File, Date> installUserModules(String pathToUserModules) throws IOException {
        AppConfig config = new AppConfig();
        config.setHost(host);
        config.setRestPort(restPort);
        config.setName(HUB_NAME);
        config.setRestAdminUsername(username);
        config.setRestAdminPassword(password);

        RestAssetLoader loader = new RestAssetLoader(config.newDatabaseClient());
        DataHubModuleManager modulesManager = new DataHubModuleManager();
        if (assetInstallTimeFile != null) {
            loader.setModulesManager(modulesManager);
        }

        ModulesFinder finder = new AssetModulesFinder();
        Modules modules = finder.findModules(new File(pathToUserModules));

        List<Resource> dirs = modules.getAssetDirectories();
        if (dirs == null || dirs.isEmpty()) {
            return new HashMap<>();
        }

        String[] paths = new String[dirs.size()];
        for (int i = 0; i < dirs.size(); i++) {
            paths[i] = dirs.get(i).getFile().getAbsolutePath();
        }
        Set<File> loadedFiles = loader.loadAssetsViaREST(paths);
        
        if (assetInstallTimeFile != null) {
            Gson gson = GsonUtil.createGson();
            
            String json = gson.toJson(modulesManager.getLastInstallInfo());
            try {
                FileUtils.write(assetInstallTimeFile, json);
            } catch (IOException e) {
                LOGGER.error("Cannot write asset install info.", e);
            }
            
            return modulesManager.getLastInstallInfo();
        }
        else {
            Map<File, Date> fileMap = new HashMap<>();
            for (File file : loadedFiles) {
                fileMap.put(file, file.exists() ? new Date(file.lastModified()) : null);
            }
            return fileMap;
        }
    }

    private List<Command> getCommands(AppConfig config) {
        List<Command> commands = new ArrayList<Command>();

        // Security
        List<Command> securityCommands = new ArrayList<Command>();
        securityCommands.add(new DeployRolesCommand());
        securityCommands.add(new DeployUsersCommand());
        commands.addAll(securityCommands);

        // Databases
        List<Command> dbCommands = new ArrayList<Command>();
        DeployContentDatabasesCommand dcdc = new DeployContentDatabasesCommand();
        dcdc.setForestsPerHost(FORESTS_PER_HOST);
        dbCommands.add(dcdc);
        dbCommands.add(new DeployTriggersDatabaseCommand());
        dbCommands.add(new DeploySchemasDatabaseCommand());
        commands.addAll(dbCommands);

        // REST API instance creation
        commands.add(new DeployRestApiServersCommand());

        // App servers
        List<Command> serverCommands = new ArrayList<Command>();
        serverCommands.add(new UpdateRestApiServersCommand());
        commands.addAll(serverCommands);

        // Modules
        commands.add(new LoadModulesCommand());

        return commands;
    }

    /**
     * Uninstalls the data hub configuration and server-side modules from MarkLogic
     * @throws IOException
     */
    public void uninstall() throws IOException {
        AdminManager manager = new AdminManager();
        AppConfig config = getAppConfig();
        SimpleAppDeployer deployer = new SimpleAppDeployer(client, manager);
        deployer.setCommands(getCommands(config));
        deployer.undeploy(config);
    }
    
    private class DataHubModuleManager implements ModulesManager {
        private Map<File, Date> lastInstallInfo = new HashMap<>();
        
        public Map<File, Date> getLastInstallInfo() {
            return lastInstallInfo;
        }
        
        @Override
        public void initialize() {
            LOGGER.debug("initializing DataHubModuleManager");
        }

        @Override
        public boolean hasFileBeenModifiedSinceLastInstalled(File file) {
            Date lastInstallDate = null;
            try {
                lastInstallDate = lastInstallInfo.get(new File(file.getCanonicalPath()));
            } catch (IOException e) {
                LOGGER.warn("Cannot get canonical path of {}. Using absolute path instead.", file);
                lastInstallDate = lastInstallInfo.get(new File(file.getAbsolutePath()));
            }
            
            // a file has been modified if it has not been previously installed (new file)
            // or when its modified time is after the last install time
            return lastInstallDate == null || file.lastModified() > lastInstallDate.getTime();
        }

        @Override
        public void saveLastInstalledTimestamp(File file, Date date) {
            try {
                LOGGER.trace("saving last timestamp of " + file.getCanonicalPath() + ": " + date);
                lastInstallInfo.put(new File(file.getCanonicalPath()), date);
            } catch (IOException e) {
                LOGGER.warn("Cannot store canonical path of {}. Storing absolute path instead.", file);
                lastInstallInfo.put(new File(file.getAbsolutePath()), date);
            }
        }
        
    }
}