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
package org.apache.camel.karavan.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.apache.camel.karavan.docker.DockerService;
import org.apache.camel.karavan.service.RegistryService;

import java.util.List;

@Path("/api/image")
public class ImagesResource {

    @Inject
    DockerService dockerService;

    @Inject
    RegistryService registryService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{projectId}")
    public List<String> getImagesForProject(@HeaderParam("username") String username,
                                 @PathParam("projectId") String projectId) throws Exception {
        String pattern = registryService.getRegistryWithGroup() + "/" + projectId;
        return dockerService.getImages().stream().filter(s -> s.startsWith(pattern)).toList();
    }
}