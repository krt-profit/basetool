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

package de.greluc.krt.profit.basetool.keycloak.spi;

import java.time.Duration;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Factory for {@link DiscordGuildRoleGateAuthenticator}.
 *
 * <p>Registered via {@code META-INF/services/org.keycloak.authentication.AuthenticatorFactory} so
 * the gate can be added as a {@code REQUIRED} execution to a custom <em>First Broker Login</em>
 * flow and bound to the Discord IdP. The config properties (guild id, KRT-Mitglied role id, API
 * base URL) are declared here so they are editable in the admin console; {@link
 * DiscordGuildRoleGateAuthenticator} reads them at authentication time to enforce the membership
 * gate.
 */
public class DiscordGuildRoleGateAuthenticatorFactory implements AuthenticatorFactory {

  /** Stable provider id shown in the authentication-flow editor. */
  public static final String PROVIDER_ID = "discord-guild-role-gate";

  /** Config key — the das-kartell guild id (numeric snowflake). */
  public static final String CONFIG_GUILD_ID = "guildId";

  /** Config key — the KRT-Mitglied role id (numeric snowflake) required for entry. */
  public static final String CONFIG_KRT_MITGLIED_ROLE_ID = "krtMitgliedRoleId";

  /** Config key — the Discord API base URL (override only for tests). */
  public static final String CONFIG_API_BASE_URL = "apiBaseUrl";

  /**
   * Environment variable: filesystem path to a PKCS#12 truststore that trusts the backend's
   * certificate, used by the fail-open account-existence precheck (REQ-SEC-022). Unset disables the
   * pinned trust (the precheck then fails the TLS handshake and fails open).
   */
  public static final String BACKEND_TRUSTSTORE_PATH_ENV = "KRT_BACKEND_TRUSTSTORE_PATH";

  /**
   * Environment variable: password for the backend truststore ({@link
   * #BACKEND_TRUSTSTORE_PATH_ENV}).
   */
  public static final String BACKEND_TRUSTSTORE_PASSWORD_ENV = "KRT_BACKEND_TRUSTSTORE_PASSWORD";

  private static final String DEFAULT_API_BASE_URL = "https://discord.com/api/v10";
  private static final Duration HTTP_TIMEOUT = DiscordHttp.TIMEOUT;

  private static final DiscordMembershipChecker CHECKER =
      new DiscordMembershipChecker(DiscordHttp.CLIENT, HTTP_TIMEOUT, 2, Duration.ofSeconds(2));

  private static final BackendAccountChecker BACKEND_CHECKER =
      new BackendAccountChecker(
          BackendTrustSupport.httpClient(
              HTTP_TIMEOUT,
              System.getenv(BACKEND_TRUSTSTORE_PATH_ENV),
              System.getenv(BACKEND_TRUSTSTORE_PASSWORD_ENV)),
          HTTP_TIMEOUT);

  private static final DiscordGuildRoleGateAuthenticator INSTANCE =
      new DiscordGuildRoleGateAuthenticator(CHECKER, BACKEND_CHECKER);

  private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
    AuthenticationExecutionModel.Requirement.REQUIRED,
    AuthenticationExecutionModel.Requirement.DISABLED
  };

  @Override
  public @NotNull Authenticator create(KeycloakSession session) {
    return INSTANCE;
  }

  @Override
  public void init(Config.Scope config) {}

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}

  @Override
  public @NotNull String getId() {
    return PROVIDER_ID;
  }

  @Override
  public @NotNull String getDisplayType() {
    return "Discord Guild + KRT-Mitglied Gate";
  }

  @Override
  public @NotNull String getReferenceCategory() {
    return "Discord";
  }

  @Override
  public boolean isConfigurable() {
    return true;
  }

  @Override
  public @NotNull AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
    return REQUIREMENT_CHOICES;
  }

  @Override
  public boolean isUserSetupAllowed() {
    return false;
  }

  @Override
  public @NotNull String getHelpText() {
    return "Denies first-broker login unless the federated Discord user is a member of the "
        + "configured guild and holds the configured KRT-Mitglied role (matched by numeric id); "
        + "fails closed on any error or ambiguity. When configured (KRT_BACKEND_PRECHECK_URL + "
        + "KRT_DISCORD_SPI_SHARED_SECRET env), it then fails open and denies a NEW Discord login "
        + "whose username/server-nickname/e-mail already matches a Basetool account, directing the "
        + "user to link their existing account instead (REQ-SEC-022).";
  }

  @Override
  public @NotNull @Unmodifiable List<ProviderConfigProperty> getConfigProperties() {
    ProviderConfigProperty guildId =
        new ProviderConfigProperty(
            CONFIG_GUILD_ID,
            "Guild ID",
            "The das-kartell Discord guild (server) id whose membership is required.",
            ProviderConfigProperty.STRING_TYPE,
            null);
    ProviderConfigProperty roleId =
        new ProviderConfigProperty(
            CONFIG_KRT_MITGLIED_ROLE_ID,
            "KRT-Mitglied role ID",
            "The numeric Discord role id (NOT the display name) a member must hold to be admitted.",
            ProviderConfigProperty.STRING_TYPE,
            null);
    ProviderConfigProperty apiBaseUrl =
        new ProviderConfigProperty(
            CONFIG_API_BASE_URL,
            "Discord API base URL",
            "Base URL of the Discord API. Override only for testing; defaults to the public API.",
            ProviderConfigProperty.STRING_TYPE,
            DEFAULT_API_BASE_URL);
    return List.of(guildId, roleId, apiBaseUrl);
  }
}
