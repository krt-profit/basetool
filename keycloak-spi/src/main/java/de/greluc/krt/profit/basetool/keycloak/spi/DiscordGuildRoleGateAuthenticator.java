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

import jakarta.ws.rs.core.Response;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.broker.AbstractIdpAuthenticator;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * First-broker-login authenticator that gates Discord federation on das-kartell guild membership
 * AND guards against duplicate registrations.
 *
 * <p>It runs two checks, in order, before a brand-new Discord user is created:
 *
 * <ol>
 *   <li><strong>Membership gate (REQ-SEC-016, fail-closed).</strong> Admits the login only when the
 *       brokered Discord user is in the configured guild and holds the configured KRT-Mitglied
 *       role, delegating to {@link DiscordMembershipChecker} (which fails closed). On any denial it
 *       renders the localized {@code discordMembershipDenied} error page and ends the flow with
 *       {@link AuthenticationFlowError#ACCESS_DENIED}, so no Keycloak session is issued.
 *   <li><strong>Account-existence gate (REQ-SEC-022, fail-open).</strong> Once membership is
 *       confirmed, it asks the Basetool backend whether an account already exists matching the
 *       incoming Discord username / server nickname (against username or display name) or e-mail,
 *       via {@link BackendAccountChecker} over HTTPS. A confident match denies the first-login with
 *       the localized {@code discordAccountAlreadyExists} page, directing the member to link their
 *       existing account (Account Console → Linked accounts → Discord, ADR-0036) instead of
 *       registering anew. This match only ever <em>rejects</em> — it never links or inherits
 *       (REQ-DATA-006 still forbids that). It is deliberately fail-open: it is skipped when the
 *       feature is unconfigured or a name match cannot be confidently established, so a transient
 *       backend/Discord hiccup never blocks a legitimate new member — they fall through to the
 *       normal PENDING approval queue. It is also skipped while an existing account is
 *       <em>linking</em> Discord (an already-authenticated session, ADR-0036), so the legitimate
 *       link is not denied against the very account it targets.
 * </ol>
 *
 * <p>It never logs the token, the membership payload, the candidate names/e-mail, or any Discord id
 * — only the coarse decision.
 */
@JBossLog
@RequiredArgsConstructor
public class DiscordGuildRoleGateAuthenticator implements Authenticator {

  /**
   * Login-theme message key for the membership denial page. Add a localized entry to the krt-theme
   * login messages; an absent key renders as the key itself (still a hard denial).
   */
  static final String ERROR_MESSAGE_KEY = "discordMembershipDenied";

  /**
   * Login-theme message key for the "account already exists, link it instead" denial page
   * (REQ-SEC-022). Localized in the krt-theme login messages alongside {@link #ERROR_MESSAGE_KEY}.
   */
  static final String ACCOUNT_EXISTS_MESSAGE_KEY = "discordAccountAlreadyExists";

  /** Environment variable holding the HTTPS URL of the backend account-existence endpoint. */
  static final String BACKEND_PRECHECK_URL_ENV = "KRT_BACKEND_PRECHECK_URL";

  /** Environment variable holding the shared secret presented to the backend endpoint. */
  static final String SHARED_SECRET_ENV = "KRT_DISCORD_SPI_SHARED_SECRET";

  private static final String DEFAULT_API_BASE_URL = "https://discord.com/api/v10";
  private static final String HTTPS_PREFIX = "https://";

  /** The fail-closed membership decision logic. */
  private final @NotNull DiscordMembershipChecker checker;

  /** The best-effort per-guild server-nickname reader (a precheck candidate). */
  private final @NotNull DiscordGuildNicknameReader nicknameReader;

  /** The fail-open backend account-existence client. */
  private final @NotNull BackendAccountChecker backendChecker;

  @Override
  public void authenticate(@NotNull AuthenticationFlowContext context) {
    Map<String, String> config = config(context);
    String guildId =
        trimToNull(config.get(DiscordGuildRoleGateAuthenticatorFactory.CONFIG_GUILD_ID));
    String roleId =
        trimToNull(
            config.get(DiscordGuildRoleGateAuthenticatorFactory.CONFIG_KRT_MITGLIED_ROLE_ID));
    String apiBaseUrl =
        orDefault(
            config.get(DiscordGuildRoleGateAuthenticatorFactory.CONFIG_API_BASE_URL),
            DEFAULT_API_BASE_URL);

    if (guildId == null || roleId == null) {
      log.error(
          "Discord guild/role gate is misconfigured (missing guildId/roleId); failing closed.");
      deny(context, ERROR_MESSAGE_KEY);
      return;
    }

    Brokered brokered = brokered(context);
    if (brokered == null || brokered.accessToken() == null) {
      log.warn("No federated Discord access token on the auth session; failing closed.");
      deny(context, ERROR_MESSAGE_KEY);
      return;
    }

    DiscordMembershipChecker.Result result =
        checker.check(apiBaseUrl, guildId, roleId, brokered.accessToken());
    if (result != DiscordMembershipChecker.Result.ALLOWED) {
      // Coarse reason only — never the token, payload or any Discord id.
      log.infof("Discord membership gate denied login (reason=%s).", result);
      deny(context, ERROR_MESSAGE_KEY);
      return;
    }

    // Membership confirmed. Now the fail-open duplicate-account guard (REQ-SEC-022).
    if (accountAlreadyExists(context, apiBaseUrl, guildId, brokered)) {
      log.info(
          "Discord first-login denied: a Basetool account already exists for this identity; "
              + "directing the user to link instead.");
      deny(context, ACCOUNT_EXISTS_MESSAGE_KEY);
      return;
    }

    context.success();
  }

  /**
   * Fail-open account-existence precheck (REQ-SEC-022). Returns {@code true} only when the backend
   * confidently reports a collision; every skip/ambiguity returns {@code false} so the login
   * proceeds.
   *
   * @param context the authentication flow context
   * @param apiBaseUrl the Discord API base URL (for the server-nickname fetch)
   * @param guildId the configured guild id (for the server-nickname fetch)
   * @param brokered the brokered Discord identity (username + e-mail + access token)
   * @return {@code true} iff a collision is confidently established and the login must be denied
   */
  private boolean accountAlreadyExists(
      @NotNull AuthenticationFlowContext context,
      @NotNull String apiBaseUrl,
      @NotNull String guildId,
      @NotNull Brokered brokered) {
    // ADR-0036: an already-authenticated session means an existing account is LINKING Discord, not
    // registering. Skip — otherwise the precheck would match the very account being linked and
    // wrongly deny a legitimate link.
    if (isAccountLinking(context)) {
      return false;
    }

    String url = backendPrecheckUrl();
    String secret = backendSharedSecret();
    if (url == null || !isHttps(url) || secret == null || secret.isBlank()) {
      // Feature off, or a non-HTTPS URL we refuse to call — fail open (HTTPS only, never HTTP).
      return false;
    }

    String serverNickname =
        nicknameReader.readNickname(apiBaseUrl, guildId, brokered.accessToken()).orElse(null);
    BackendAccountChecker.Result existence =
        backendChecker.check(url, secret, brokered.username(), brokered.email(), serverNickname);
    // Fail open: only a confident EXISTS denies; NOT_EXISTS and UNKNOWN allow.
    return existence == BackendAccountChecker.Result.EXISTS;
  }

  private void deny(@NotNull AuthenticationFlowContext context, @NotNull String messageKey) {
    Response challenge =
        context.form().setError(messageKey).createErrorPage(Response.Status.FORBIDDEN);
    context.failure(AuthenticationFlowError.ACCESS_DENIED, challenge);
  }

  private @NotNull Map<String, String> config(@NotNull AuthenticationFlowContext context) {
    AuthenticatorConfigModel model = context.getAuthenticatorConfig();
    return (model != null && model.getConfig() != null) ? model.getConfig() : Map.of();
  }

  /**
   * Deserializes the brokered Discord identity from the first-broker-login session: the access
   * token, the Discord username and the Discord e-mail. Package-visible (not private) so a unit
   * test can override it without a live Keycloak session.
   *
   * @param context the authentication flow context
   * @return the brokered identity, or {@code null} when no brokered context is present
   */
  @Nullable
  Brokered brokered(@NotNull AuthenticationFlowContext context) {
    SerializedBrokeredIdentityContext serialized =
        SerializedBrokeredIdentityContext.readFromAuthenticationSession(
            context.getAuthenticationSession(), AbstractIdpAuthenticator.BROKERED_CONTEXT_NOTE);
    if (serialized == null) {
      return null;
    }
    BrokeredIdentityContext broker =
        serialized.deserialize(context.getSession(), context.getAuthenticationSession());
    Object token =
        broker.getContextData().get(AbstractOAuth2IdentityProvider.FEDERATED_ACCESS_TOKEN);
    return new Brokered(
        token == null ? null : token.toString(), broker.getUsername(), broker.getEmail());
  }

  /**
   * Whether an already-authenticated user is present on the auth session, which marks an
   * account-linking flow (ADR-0036) rather than a brand-new registration. Package-visible so a unit
   * test can drive the linking branch without a live session.
   *
   * @param context the authentication flow context
   * @return {@code true} when an existing user is linking Discord to their account
   */
  boolean isAccountLinking(@NotNull AuthenticationFlowContext context) {
    return context.getAuthenticationSession() != null
        && context.getAuthenticationSession().getAuthenticatedUser() != null;
  }

  /**
   * The configured HTTPS URL of the backend account-existence endpoint, or {@code null} when the
   * feature is unconfigured. Read from the {@value #BACKEND_PRECHECK_URL_ENV} environment variable.
   * Package-visible so a unit test can supply a value without a real environment.
   *
   * @return the trimmed URL, or {@code null} when unset/blank
   */
  @Nullable
  String backendPrecheckUrl() {
    return trimToNull(System.getenv(BACKEND_PRECHECK_URL_ENV));
  }

  /**
   * The configured shared secret presented to the backend endpoint, or {@code null} when unset.
   * Read from the {@value #SHARED_SECRET_ENV} environment variable. Package-visible so a unit test
   * can supply a value without a real environment.
   *
   * @return the secret, or {@code null} when unset
   */
  @Nullable
  String backendSharedSecret() {
    return System.getenv(SHARED_SECRET_ENV);
  }

  @Contract(pure = true)
  private static boolean isHttps(@NotNull String url) {
    return url.length() >= HTTPS_PREFIX.length()
        && url.regionMatches(true, 0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length());
  }

  @Contract(value = "null -> null", pure = true)
  private static @Nullable String trimToNull(@Nullable String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }

  @Contract(pure = true)
  private static @NotNull String orDefault(@Nullable String value, @NotNull String fallback) {
    String trimmed = trimToNull(value);
    return trimmed == null ? fallback : trimmed;
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    // Non-interactive: authenticate() terminates with success/failure, so this is never reached.
  }

  @Override
  public boolean requiresUser() {
    // The gate inspects the brokered Discord identity, not a local Keycloak user (which may not yet
    // exist during first-broker login), so no pre-existing user is required.
    return false;
  }

  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    return true;
  }

  @Override
  public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    // The gate neither sets nor clears required actions.
  }

  @Override
  public void close() {
    // Stateless; nothing to release.
  }

  /**
   * The brokered Discord identity fields the gate needs: the federated access token plus the
   * Discord username and e-mail used as account-existence candidates.
   *
   * @param accessToken the brokered Discord access token, or {@code null} when absent
   * @param username the brokered Discord username, or {@code null}
   * @param email the brokered Discord e-mail, or {@code null}
   */
  record Brokered(
      @Nullable String accessToken, @Nullable String username, @Nullable String email) {}
}
