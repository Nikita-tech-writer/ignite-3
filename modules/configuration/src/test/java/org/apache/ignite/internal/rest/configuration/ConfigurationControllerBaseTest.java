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

package org.apache.ignite.internal.rest.configuration;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.ignite.internal.configuration.ConfigurationRegistry;
import org.apache.ignite.internal.configuration.rest.presentation.ConfigurationPresentation;
import org.apache.ignite.internal.configuration.rest.presentation.TestRootConfiguration;
import org.apache.ignite.internal.rest.api.ErrorResult;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The base test for configuration controllers.
 */
@MicronautTest
public abstract class ConfigurationControllerBaseTest {
    @Inject
    private EmbeddedServer server;

    @Inject
    private ConfigurationPresentation<String> cfgPresentation;

    @Inject
    private ConfigurationRegistry configurationRegistry;

    @Inject
    private ApplicationContext context;

    abstract HttpClient client();

    @BeforeEach
    void beforeEach() throws Exception {
        var cfg = configurationRegistry.getConfiguration(TestRootConfiguration.KEY);
        cfg.change(c -> c.changeFoo("foo").changeSubCfg(subCfg -> subCfg.changeBar("bar"))).get(1, SECONDS);
    }

    @Test
    void testGetConfig() {
        var response = client().toBlocking().exchange("", String.class);

        assertEquals(HttpStatus.OK, response.status());
        assertEquals(cfgPresentation.represent(), response.body());
    }

    @Test
    void testGetConfigByPath() {
        var response = client().toBlocking().exchange("/root.subCfg", String.class);

        assertEquals(HttpStatus.OK, response.status());
        assertEquals(cfgPresentation.representByPath("root.subCfg"), response.body());
    }

    @Test
    void testUpdateConfig() {
        String givenChangedConfig = "{root:{foo:foo,subCfg:{bar:changed}}}";

        var response = client().toBlocking().exchange(
                HttpRequest.PATCH("", givenChangedConfig).contentType(MediaType.TEXT_PLAIN)
        );
        assertEquals(response.status(), HttpStatus.OK);

        String changedConfigValue = client().toBlocking().exchange("/root.subCfg.bar", String.class).body();
        assertEquals("\"changed\"", changedConfigValue);
    }

    @Test
    void testUnrecognizedConfigPath() {
        var thrown = assertThrows(
                HttpClientResponseException.class,
                () -> client().toBlocking().exchange("/no-such-root.some-value")
        );

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getResponse().status());

        var errorResult = getErrorResult(thrown);
        assertEquals("CONFIG_PATH_UNRECOGNIZED", errorResult.type());
        assertEquals("Configuration value 'no-such-root' has not been found", errorResult.message());
    }

    @Test
    void testUnrecognizedConfigPathForUpdate() {
        String givenBrokenConfig = "{root:{foo:foo,subCfg:{no-such-bar:bar}}}";

        var thrown = assertThrows(
                HttpClientResponseException.class,
                () -> client().toBlocking().exchange(HttpRequest.PATCH("", givenBrokenConfig).contentType(MediaType.TEXT_PLAIN))
        );

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getResponse().status());

        var errorResult = getErrorResult(thrown);
        assertEquals("INVALID_CONFIG_FORMAT", errorResult.type());
        assertEquals("'root.subCfg' configuration doesn't have the 'no-such-bar' sub-configuration", errorResult.message());
    }

    @Test
    void testValidationForUpdate() {
        String givenConfigWithError = "{root:{foo:error,subCfg:{bar:bar}}}";

        var thrown = assertThrows(
                HttpClientResponseException.class,
                () -> client().toBlocking().exchange(HttpRequest.PATCH("", givenConfigWithError).contentType(MediaType.TEXT_PLAIN))
        );

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getResponse().status());

        var errorResult = getErrorResult(thrown);
        assertEquals("VALIDATION_EXCEPTION", errorResult.type());
        assertEquals("Error word", errorResult.message());
    }

    @NotNull
    private ErrorResult getErrorResult(HttpClientResponseException exception) {
        return exception.getResponse().getBody(ErrorResult.class).orElseThrow();
    }
}
