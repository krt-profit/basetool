/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that every OIDC scope the frontend requests is offered to {@code basetool-frontend} by
 * the committed test realms, because Keycloak answers a scope the client is not offered with {@code
 * error=invalid_scope} and the browser sign-in fails.
 */
class FrontendRequestedScopesParityTest {

  /** The frontend's shipped configuration carrying the requested scopes. */
  private static final String APPLICATION_YAML = "src/main/resources/application.yml";

  /** Property holding the comma-separated scopes the browser sign-in requests. */
  private static final String SCOPE_PROPERTY =
      "spring.security.oauth2.client.registration.keycloak.scope";

  /** Keycloak client the frontend signs in as. */
  private static final String FRONTEND_CLIENT_ID = "basetool-frontend";

  /** Scope Keycloak grants every OIDC request without a client scope of that name. */
  private static final String OPENID = "openid";

  /**
   * Pins each requested scope to the client's default or optional scopes in one realm.
   *
   * @param realmPath module-relative path of the committed realm
   * @throws IOException if the realm cannot be read
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "src/e2e/resources/realm-export.e2e.json",
        "../docker/sandbox/keycloak/realm-basetool.json"
      })
  void everyRequestedScope_isOfferedToTheFrontendClient(String realmPath) throws IOException {
    JsonNode client = frontendClient(realmPath);
    Set<String> offered = new HashSet<>();
    client.path("defaultClientScopes").forEach(scope -> offered.add(scope.asString()));
    client.path("optionalClientScopes").forEach(scope -> offered.add(scope.asString()));
    offered.add(OPENID);

    assertThat(requestedScopes())
        .as(
            "scopes %s requests that %s does not offer in %s",
            APPLICATION_YAML, FRONTEND_CLIENT_ID, realmPath)
        .isSubsetOf(offered);
  }

  /**
   * Reads the scopes the frontend's OAuth2 registration requests.
   *
   * @return the trimmed scope names
   */
  private static @NotNull List<String> requestedScopes() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new FileSystemResource(resolve(APPLICATION_YAML)));
    Properties properties = yaml.getObject();
    String scopes = properties == null ? null : properties.getProperty(SCOPE_PROPERTY);
    if (scopes == null) {
      return fail("%s not set in %s", SCOPE_PROPERTY, APPLICATION_YAML);
    }
    return Arrays.stream(scopes.split(",")).map(String::trim).toList();
  }

  /**
   * Finds the frontend client in one committed realm.
   *
   * @param realmPath module-relative path of the realm
   * @return the client node
   * @throws IOException if the realm cannot be read
   */
  private static @NotNull JsonNode frontendClient(@NotNull String realmPath) throws IOException {
    JsonNode realm =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(resolve(realmPath), StandardCharsets.UTF_8));
    for (JsonNode client : realm.path("clients")) {
      if (FRONTEND_CLIENT_ID.equals(client.path("clientId").asString())) {
        return client;
      }
    }
    return fail("no %s client in %s", FRONTEND_CLIENT_ID, realmPath);
  }

  /**
   * Resolves a module-relative path whether the test runs from the {@code frontend} module or the
   * repository root.
   *
   * @param moduleRelative the path relative to the {@code frontend} module directory
   * @return the first candidate that exists
   */
  private static @NotNull Path resolve(@NotNull String moduleRelative) {
    Path fromModule = Path.of(moduleRelative);
    return Files.exists(fromModule) ? fromModule : Path.of("frontend").resolve(moduleRelative);
  }
}
