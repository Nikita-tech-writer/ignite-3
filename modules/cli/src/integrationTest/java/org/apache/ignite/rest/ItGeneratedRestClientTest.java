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

package org.apache.ignite.rest;

import static java.util.stream.Collectors.toList;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.testNodeName;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgnitionManager;
import org.apache.ignite.internal.testframework.WorkDirectory;
import org.apache.ignite.internal.testframework.WorkDirectoryExtension;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.rest.client.api.ClusterConfigurationApi;
import org.apache.ignite.rest.client.api.ClusterManagementApi;
import org.apache.ignite.rest.client.api.NodeConfigurationApi;
import org.apache.ignite.rest.client.invoker.ApiClient;
import org.apache.ignite.rest.client.invoker.ApiException;
import org.apache.ignite.rest.client.invoker.Configuration;
import org.apache.ignite.rest.client.model.InitCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Test for autogenerated ignite rest client.
 */
@ExtendWith(WorkDirectoryExtension.class)
public class ItGeneratedRestClientTest {
    /** Start network port for test nodes. */
    private static final int BASE_PORT = 3344;

    /** Start rest server port. */
    private static final int BASE_REST_PORT = 10300;

    private final List<String> clusterNodeNames = new ArrayList<>();

    private final List<Ignite> clusterNodes = new ArrayList<>();

    @WorkDirectory
    private Path workDir;

    private CompletableFuture<Ignite> ignite;

    private ClusterConfigurationApi clusterConfigurationApi;

    private NodeConfigurationApi nodeConfigurationApi;

    private ClusterManagementApi clusterManagementApi;

    private static String buildConfig(int nodeIdx) {
        return "{\n"
                + "  network: {\n"
                + "    port: " + (BASE_PORT + nodeIdx) + ",\n"
                + "    portRange: 1,\n"
                + "    nodeFinder: {\n"
                + "      netClusterNodes: [ \"localhost:3344\", \"localhost:3345\", \"localhost:3346\" ] \n"
                + "    }\n"
                + "  }\n"
                + "}";
    }

    @BeforeEach
    void setUp(TestInfo testInfo) {
        List<CompletableFuture<Ignite>> futures = IntStream.range(0, 3)
                .mapToObj(i -> startNodeAsync(testInfo, i))
                .collect(toList());

        String metaStorageNode = testNodeName(testInfo, BASE_PORT);

        IgnitionManager.init(metaStorageNode, List.of(metaStorageNode), "cluster");

        for (CompletableFuture<Ignite> future : futures) {
            assertThat(future, willCompleteSuccessfully());

            clusterNodes.add(future.join());
        }

        ApiClient client = Configuration.getDefaultApiClient();
        client.setBasePath("http://localhost:" + BASE_REST_PORT);

        clusterConfigurationApi = new ClusterConfigurationApi(client);
        nodeConfigurationApi = new NodeConfigurationApi(client);
        clusterManagementApi = new ClusterManagementApi(client);
    }

    @AfterEach
    void tearDown() throws Exception {
        List<AutoCloseable> closeables = clusterNodeNames.stream()
                .map(name -> (AutoCloseable) () -> IgnitionManager.stop(name))
                .collect(toList());

        IgniteUtils.closeAll(closeables);
    }

    @Test
    void getClusterConfiguration() {
        assertDoesNotThrow(() -> {
            String configuration = clusterConfigurationApi.getClusterConfiguration();

            assertNotNull(configuration);
            assertFalse(configuration.isEmpty());
        });
    }

    @Test
    void getClusterConfigurationByPath() {
        assertDoesNotThrow(() -> {
            String configuration = clusterConfigurationApi.getClusterConfigurationByPath("rocksDb.defaultRegion");

            assertNotNull(configuration);
            assertFalse(configuration.isEmpty());
        });
    }

    @Test
    void updateTheSameClusterConfiguration() {
        assertDoesNotThrow(() -> {
            String originalConfiguration = clusterConfigurationApi.getClusterConfiguration();

            clusterConfigurationApi.updateClusterConfiguration(originalConfiguration);
            String updatedConfiguration = clusterConfigurationApi.getClusterConfiguration();

            assertNotNull(updatedConfiguration);
            assertEquals(originalConfiguration, updatedConfiguration);
        });
    }

    @Test
    void getClusterConfigurationByPathBadRequest() {
        try {
            clusterConfigurationApi.getClusterConfigurationByPath("no.such.path");
            fail("Expected ApiException to be thrown");
        } catch (ApiException e) {
            assertEquals(400, e.getCode());
        }
    }

    @Test
    void getNodeConfiguration() {
        assertDoesNotThrow(() -> {
            String configuration = nodeConfigurationApi.getNodeConfiguration();

            assertNotNull(configuration);
            assertFalse(configuration.isEmpty());
        });
    }

    @Test
    void getNodeConfigurationByPath() {
        assertDoesNotThrow(() -> {
            String configuration = nodeConfigurationApi.getNodeConfigurationByPath("clientConnector.connectTimeout");

            assertNotNull(configuration);
            assertFalse(configuration.isEmpty());
        });
    }

    @Test
    void getNodeConfigurationByPathBadRequest() {
        try {
            nodeConfigurationApi.getNodeConfigurationByPath("no.such.path");
            fail("Expected ApiException to be thrown");
        } catch (ApiException e) {
            assertEquals(400, e.getCode());
        }
    }

    @Test
    void updateTheSameNodeConfiguration() {
        assertDoesNotThrow(() -> {
            String originalConfiguration = nodeConfigurationApi.getNodeConfiguration();

            nodeConfigurationApi.updateNodeConfiguration(originalConfiguration);
            String updatedConfiguration = nodeConfigurationApi.getNodeConfiguration();

            assertNotNull(updatedConfiguration);
            assertEquals(originalConfiguration, updatedConfiguration);
        });
    }

    @Test
    void initCluster() {
        assertDoesNotThrow(() -> {
            String nodeName = clusterNodes.get(0).name();
            clusterManagementApi.init(new InitCommand().clusterName("cluster").metaStorageNodes(List.of(nodeName)).cmgNodes(List.of()));
        });
    }

    @Test
    void initClusterNoSuchNode() {
        try {
            clusterManagementApi.init(new InitCommand().metaStorageNodes(List.of("no-such-node")).cmgNodes(List.of()));
            fail("Expected ApiException to be thrown");
        } catch (ApiException e) {
            assertEquals(400, e.getCode());
        }
    }

    private CompletableFuture<Ignite> startNodeAsync(TestInfo testInfo, int index) {
        String nodeName = testNodeName(testInfo, BASE_PORT + index);

        clusterNodeNames.add(nodeName);

        return IgnitionManager.start(nodeName, buildConfig(index), workDir.resolve(nodeName));
    }
}

