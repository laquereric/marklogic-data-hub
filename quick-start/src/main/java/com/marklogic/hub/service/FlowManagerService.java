package com.marklogic.hub.service;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.marklogic.client.DatabaseClient;
import com.marklogic.client.DatabaseClientFactory;
import com.marklogic.client.DatabaseClientFactory.Authentication;
import com.marklogic.client.io.Format;
import com.marklogic.hub.FlowManager;
import com.marklogic.hub.PluginFormat;
import com.marklogic.hub.config.EnvironmentConfiguration;
import com.marklogic.hub.exception.FlowManagerException;
import com.marklogic.hub.factory.FlowModelFactory;
import com.marklogic.hub.flow.Flow;
import com.marklogic.hub.flow.FlowType;
import com.marklogic.hub.model.FlowModel;

@Service
public class FlowManagerService {

    private static final Logger LOGGER = LoggerFactory
            .getLogger(FlowManagerService.class);

    @Autowired
    private EnvironmentConfiguration environmentConfiguration;

    public FlowManager getFlowManager() {

        Authentication authMethod = Authentication
                .valueOf(environmentConfiguration.getMLAuth().toUpperCase());
        DatabaseClient client = DatabaseClientFactory.newClient(
                environmentConfiguration.getMLHost(),
                Integer.parseInt(environmentConfiguration.getMLStagingPort()),
                environmentConfiguration.getMLUsername(),
                environmentConfiguration.getMLPassword(), authMethod);
        return new FlowManager(client);

    }

    public List<Flow> getFlows(String entityName) {
        FlowManager flowManager = getFlowManager();
        return flowManager.getFlows(entityName);
    }

    public Flow getFlow(String entityName, String flowName) {
        FlowManager flowManager = getFlowManager();
        return flowManager.getFlow(entityName, flowName);
    }

    public void installFlow(Flow flow) {
        FlowManager flowManager = getFlowManager();
        flowManager.installFlow(flow);
    }

    public void uninstallFlow(String flowName) {
        FlowManager flowManager = getFlowManager();
        flowManager.uninstallFlow(flowName);
    }

    public JobExecution testFlow(Flow flow) {
        FlowManager flowManager = getFlowManager();
        return flowManager.testFlow(flow);
    }

    public JobExecution runFlow(Flow flow, int batchSize) {
        return runFlow(flow, batchSize, null);
    }

    public JobExecution runFlow(Flow flow, int batchSize, JobExecutionListener listener) {
        FlowManager flowManager = getFlowManager();
        return flowManager.runFlow(flow, batchSize, listener);
    }

    public void runFlowsInParallel(Flow... flows) {
        FlowManager flowManager = getFlowManager();
        flowManager.runFlowsInParallel(flows);
    }

    public FlowModel createFlow(String entityName, String flowName,
            FlowType flowType, PluginFormat pluginFormat, Format dataFormat) {
        FlowModelFactory flowModelFactory = new FlowModelFactory(entityName);
        File pluginDir = new File(environmentConfiguration.getUserPluginDir());
        FlowModel flowModel;
        try {
            flowModel = flowModelFactory.createNewFlow(pluginDir, flowName,
                    flowType, pluginFormat, dataFormat);
        } catch (IOException e) {
            throw new FlowManagerException(e.getMessage(), e);
        }
        return flowModel;
    }
}
