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

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.eventbus.EventBus;
import org.apache.camel.karavan.model.Environment;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

@ApplicationScoped
public class KaravanService {

    private static final Logger LOGGER = Logger.getLogger(KaravanService.class.getName());

    @Inject
    InfinispanService infinispanService;

    @Inject
    KubernetesService kubernetesService;

    @Inject
    EventBus bus;

    @ConfigProperty(name = "karavan.environment")
    String environment;

    @ConfigProperty(name = "karavan.pipeline")
    String pipeline;

    void onStart(@Observes StartupEvent ev) {
        infinispanService.start();
        setEnvironment();
        initialImport();
        startInformers();
    }

    void setEnvironment() {
        String cluster = kubernetesService.getCluster();
        String namespace = kubernetesService.getNamespace();
        infinispanService.saveEnvironment(new Environment(environment, cluster, namespace, pipeline));
    }

    void initialImport() {
        if (infinispanService.getProjects().isEmpty()) {
            LOGGER.info("No projects found in the Data Grid");
            bus.publish(ImportService.IMPORT_PROJECTS, "");
        }
        bus.publish(ImportService.IMPORT_KAMELETS, "");
    }

    void startInformers() {
        bus.publish(KubernetesService.START_WATCHERS, "");
    }
}
