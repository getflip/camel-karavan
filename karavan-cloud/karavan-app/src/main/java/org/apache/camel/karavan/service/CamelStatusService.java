/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.karavan.service;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.apache.camel.karavan.datagrid.DatagridService;
import org.apache.camel.karavan.datagrid.model.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

@ApplicationScoped
public class CamelStatusService {

    private static final Logger LOGGER = Logger.getLogger(CamelStatusService.class.getName());
    public static final String CMD_COLLECT_CAMEL_STATUS = "collect-camel-status";
    public static final String CMD_DELETE_CAMEL_STATUS = "delete-camel-status";
    public static final String DEVMODE_SUFFIX = "devmode";

    @Inject
    DatagridService datagridService;

    @Inject
    KubernetesService kubernetesService;

    @ConfigProperty(name = "karavan.environment")
    String environment;

    @Inject
    Vertx vertx;

    @Inject
    EventBus eventBus;

    WebClient webClient;

    public WebClient getWebClient() {
        if (webClient == null) {
            webClient = WebClient.create(vertx);
        }
        return webClient;
    }

    public void reloadProjectCode(String projectId) {
        LOGGER.info("Reload project code " + projectId);
        String containerName = projectId + "-" + DEVMODE_SUFFIX;
        try {
            datagridService.getProjectFiles(projectId).forEach(projectFile -> putRequest(containerName, projectFile.getName(), projectFile.getCode(), 1000));
            reloadRequest(containerName);
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
        }
        datagridService.deleteDevModeStatus(projectId);
    }

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 1000)
    public boolean putRequest(String containerName, String fileName, String body, int timeout) {
        try {
            String url = getContainerAddress(containerName) + "/q/upload/" + fileName;
            HttpResponse<Buffer> result = getWebClient().putAbs(url)
                    .timeout(timeout).sendBuffer(Buffer.buffer(body)).subscribeAsCompletionStage().toCompletableFuture().get();
            return result.statusCode() == 200;
        } catch (Exception e) {
            LOGGER.info(e.getMessage());
        }
        return false;
    }

    public String reloadRequest(String containerName) {
        String url = getContainerAddress(containerName) + "/q/dev/reload?reload=true";
        try {
            return result(url, 1000);
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error(e.getMessage());
        }
        return null;
    }

    public String getContainerAddress(String containerName) {
        if (kubernetesService.inKubernetes()) {
            return "http://" + containerName + "." + kubernetesService.getNamespace() + ".svc.cluster.local";
        } else {
            return "http://" + containerName + ":8080";
        }
    }

    @Scheduled(every = "{karavan.devmode-status-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void collectDevModeStatuses() {
        System.out.println("Collect DevMode Statuses");
        if (datagridService.isReady()) {
            datagridService.getDevModeStatuses().forEach(dms -> {
                CamelStatusRequest csr = new CamelStatusRequest(dms.getProjectId(), dms.getContainerName());
                eventBus.publish(CMD_COLLECT_CAMEL_STATUS, JsonObject.mapFrom(csr));
            });
        }
    }

    @Scheduled(every = "{karavan.camel-status-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void collectNonDevModeStatuses() {
        System.out.println("Collect NonDevMode Statuses");
        if (datagridService.isReady()) {
            datagridService.getPodStatuses(environment).forEach(pod -> {
                CamelStatusRequest csr = new CamelStatusRequest(pod.getProjectId(), pod.getName());
                eventBus.publish(CMD_COLLECT_CAMEL_STATUS, JsonObject.mapFrom(csr));
            });
        }
    }

    @ConsumeEvent(value = CMD_COLLECT_CAMEL_STATUS, blocking = true, ordered = true)
    public void collectCamelStatuses(JsonObject data) {
        DevModeStatus dms = data.mapTo(DevModeStatus.class);
        Arrays.stream(CamelStatusName.values()).forEach(statusName -> {
            String containerName = dms.getContainerName();
            String status = getCamelStatus(containerName, statusName);
            if (status != null) {
                CamelStatus cs = new CamelStatus(dms.getProjectId(), containerName, statusName, status, environment);
                datagridService.saveCamelStatus(cs);
            }
        });
    }

    @Scheduled(every = "{karavan.devmode-status-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cleanupDevModeStatuses() {
        System.out.println("Clean DevMode Statuses");
        if (datagridService.isReady()) {
            datagridService.getDevModeStatuses().forEach(dms -> {
                PodStatus pod = datagridService.getDevModePodStatuses(dms.getProjectId(), environment);
                if (pod == null) {
                    eventBus.publish(CMD_DELETE_CAMEL_STATUS, JsonObject.mapFrom(dms));
                }
            });
        }
    }

    @ConsumeEvent(value = CMD_DELETE_CAMEL_STATUS, blocking = true, ordered = true)
    public void cleanupDevModeStatus(JsonObject data) {
        DevModeStatus dms = data.mapTo(DevModeStatus.class);
        Arrays.stream(CamelStatusName.values()).forEach(name -> {
            datagridService.deleteCamelStatus(dms.getProjectId(), name.name(), environment);
        });
    }

    private void reloadCode(String podName, String oldContext, String newContext) {
        String projectName = podName.replace("-" + DEVMODE_SUFFIX, "");
        String newState = getContextState(newContext);
        String oldState = getContextState(oldContext);
        if (newContext != null && !Objects.equals(newState, oldState) && "Started".equals(newState)) {
            reloadProjectCode(projectName);
        }
    }

    private String getContextState(String context) {
        if (context != null) {
            JsonObject obj = new JsonObject(context);
            return obj.getJsonObject("context").getString("state");
        } else {
            return null;
        }
    }

    public String getCamelStatus(String podName, CamelStatusName statusName) {
        String url = getContainerAddress(podName) + "/q/dev/" + statusName.name();
        try {
            return result(url, 500);
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error(e.getMessage());
        }
        return null;
    }

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 1000)
    public String result(String url, int timeout) throws InterruptedException, ExecutionException {
        try {
            HttpResponse<Buffer> result = getWebClient().getAbs(url).putHeader("Accept", "application/json")
                    .timeout(timeout).send().subscribeAsCompletionStage().toCompletableFuture().get();
            if (result.statusCode() == 200) {
                JsonObject res = result.bodyAsJsonObject();
                return res.encodePrettily();
            }
        } catch (Exception e) {
            LOGGER.info(e.getMessage());
        }
        return null;
    }

    public class CamelStatusRequest {
        private final String projectId;
        private final String containerName;

        public CamelStatusRequest(String projectId, String containerName) {
            this.projectId = projectId;
            this.containerName = containerName;
        }

        public String getProjectId() {
            return projectId;
        }

        public String getContainerName() {
            return containerName;
        }
    }
}