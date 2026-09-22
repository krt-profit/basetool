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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticatorConfigModel;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Orchestration tests for {@link DiscordGuildRoleGateAuthenticator#authenticate}: the fail-closed
 * membership branches (missing config, missing brokered token, membership denial) and the fail-open
 * account-existence precheck (REQ-SEC-022) — deny-on-collision, allow-on-no-collision,
 * allow-on-uncertain (fail open), and the three skip paths (account linking, unconfigured URL,
 * non-HTTPS URL). The brokered-identity and environment reads are overridden per test (they would
 * otherwise need a live first-broker-login session and real environment variables).
 */
@ExtendWith(MockitoExtension.class)
class DiscordGuildRoleGateAuthenticatorTest {

  @Mock private AuthenticationFlowContext context;
  @Mock private DiscordMembershipChecker checker;
  @Mock private BackendAccountChecker backendChecker;
  @Mock private LoginFormsProvider form;
  @Mock private HttpClient discord;
  @Mock private HttpResponse<String> member;

  private static final Map<String, String> VALID_CONFIG =
      Map.of("guildId", "123", "krtMitgliedRoleId", "999");
  private static final String PRECHECK_URL =
      "https://backend:11261/internal/discord/account-existence";
  private static final String SECRET = "s3cr3t";

  // Overridable env/identity seams, settable per test before building the authenticator.
  private String precheckUrl;
  private String sharedSecret;
  private boolean accountLinking;

  /**
   * Builds the authenticator with overridden brokered-identity + environment seams, bypassing the
   * live session and process environment.
   */
  private DiscordGuildRoleGateAuthenticator authenticator(
      String token, String username, String email) {
    return new DiscordGuildRoleGateAuthenticator(checker, backendChecker) {
      @Override
      Brokered brokered(AuthenticationFlowContext ctx) {
        return token == null ? null : new Brokered(token, username, email);
      }

      @Override
      boolean isAccountLinking(AuthenticationFlowContext ctx) {
        return accountLinking;
      }

      @Override
      String backendPrecheckUrl() {
        return precheckUrl;
      }

      @Override
      String backendSharedSecret() {
        return sharedSecret;
      }
    };
  }

  /**
   * An allowed membership lookup whose member object carries the given per-guild nickname.
   *
   * @param nick the {@code nick} field, or {@code null} for none
   * @return the lookup the checker answers with
   */
  private static DiscordMembershipChecker.MemberLookup allowed(String nick) {
    String body =
        nick == null
            ? "{\"nick\":null,\"roles\":[\"999\"]}"
            : "{\"nick\":\"" + nick + "\",\"roles\":[\"999\"]}";
    return new DiscordMembershipChecker.MemberLookup(DiscordMembershipChecker.Result.ALLOWED, body);
  }

  private void stubConfig(Map<String, String> config) {
    AuthenticatorConfigModel model = mock(AuthenticatorConfigModel.class);
    when(model.getConfig()).thenReturn(config);
    when(context.getAuthenticatorConfig()).thenReturn(model);
  }

  private void stubDenyForm() {
    when(context.form()).thenReturn(form);
    when(form.setError(anyString())).thenReturn(form);
    when(form.createErrorPage(any())).thenReturn(mock(Response.class));
  }

  @Test
  void deniesClosed_whenGuildOrRoleConfigMissing() {
    when(context.getAuthenticatorConfig()).thenReturn(null);
    stubDenyForm();

    authenticator("tok", "Maverick", "mav@example.com").authenticate(context);

    verify(context).failure(eq(AuthenticationFlowError.ACCESS_DENIED), any());
    verifyNoInteractions(checker, backendChecker);
  }

  @Test
  void deniesClosed_whenBrokeredTokenMissing() {
    stubConfig(VALID_CONFIG);
    stubDenyForm();

    authenticator(null, null, null).authenticate(context);

    verify(context).failure(eq(AuthenticationFlowError.ACCESS_DENIED), any());
    verifyNoInteractions(checker, backendChecker);
  }

  @Test
  void denies_whenMembershipCheckerDenies() {
    stubConfig(VALID_CONFIG);
    stubDenyForm();
    when(checker.lookup(any(), any(), any(), any()))
        .thenReturn(
            DiscordMembershipChecker.MemberLookup.denied(
                DiscordMembershipChecker.Result.DENIED_NOT_MEMBER));

    authenticator("tok", "Maverick", "mav@example.com").authenticate(context);

    verify(context).failure(eq(AuthenticationFlowError.ACCESS_DENIED), any());
    verify(form).setError(DiscordGuildRoleGateAuthenticator.ERROR_MESSAGE_KEY);
    verifyNoInteractions(backendChecker);
  }

  @Test
  void succeeds_whenMembershipAllows_andPrecheckUnconfigured() {
    stubConfig(VALID_CONFIG);
    when(checker.lookup(any(), eq("123"), eq("999"), eq("tok"))).thenReturn(allowed(null));

    authenticator("tok", "Maverick", "mav@example.com").authenticate(context);

    verify(context).success();
    verifyNoInteractions(backendChecker);
  }

  @Test
  void deniesAccountExists_whenBackendReportsExists() {
    precheckUrl = PRECHECK_URL;
    sharedSecret = SECRET;
    stubConfig(VALID_CONFIG);
    stubDenyForm();
    when(checker.lookup(any(), any(), any(), any())).thenReturn(allowed("Mav"));
    when(backendChecker.check(
            eq(PRECHECK_URL), eq(SECRET), eq("Maverick"), eq("mav@example.com"), eq("Mav")))
        .thenReturn(BackendAccountChecker.Result.EXISTS);

    authenticator("tok", "Maverick", "mav@example.com").authenticate(context);

    verify(context).failure(eq(AuthenticationFlowError.ACCESS_DENIED), any());
    verify(form).setError(DiscordGuildRoleGateAuthenticator.ACCOUNT_EXISTS_MESSAGE_KEY);
  }

  @Test
  void succeeds_whenBackendReportsNotExists() {
    precheckUrl = PRECHECK_URL;
    sharedSecret = SECRET;
    stubConfig(VALID_CONFIG);
    when(checker.lookup(any(), any(), any(), any())).thenReturn(allowed(null));
    when(backendChecker.check(any(), any(), any(), any(), any()))
        .thenReturn(BackendAccountChecker.Result.NOT_EXISTS);

    authenticator("tok", "Maverick", "mav@example.com").authenticate(context);

    verify(context).success();
  }

  @Test
  void succeedsFailOpen_whenBackendUnknown() {
    precheckUrl = PRECHECK_URL;
    sharedSecret = SECRET;
    stubConfig(VALID_CONFIG);
    when(checker.lookup(any(), any(), any(), any())).thenReturn(allowed(null));
    when(backendChecker.check(any(), any(), any(), any(), any()))
        .thenReturn(BackendAccountChecker.Result.UNKNOWN);

    authenticator("tok", "Maverick", "mav@example.com").authenticate(context);

    verify(context).success();
  }

  @Test
  void skipsPrecheck_whenAccountLinking() {
    precheckUrl = PRECHECK_URL;
    sharedSecret = SECRET;
    accountLinking = true;
    stubConfig(VALID_CONFIG);
    when(checker.lookup(any(), any(), any(), any())).thenReturn(allowed(null));

    authenticator("tok", "Maverick", "mav@example.com").authenticate(context);

    verify(context).success();
    verifyNoInteractions(backendChecker);
  }

  @Test
  void skipsPrecheck_whenUrlNotConfigured() {
    precheckUrl = null;
    sharedSecret = SECRET;
    stubConfig(VALID_CONFIG);
    when(checker.lookup(any(), any(), any(), any())).thenReturn(allowed(null));

    authenticator("tok", "Maverick", "mav@example.com").authenticate(context);

    verify(context).success();
    verifyNoInteractions(backendChecker);
  }

  /**
   * KC-PERF-01: a first login with the precheck configured makes exactly ONE Discord call — the
   * guild-member read — and the server nickname the precheck needs comes out of that same object.
   * It used to be two here (roles, then the same endpoint again for the nickname), plus the
   * identity provider's own read, i.e. three calls of the user's Discord rate-limit budget for one
   * member object.
   */
  @Test
  void makesExactlyOneDiscordCallPerFirstLogin_andTakesTheNicknameFromIt() throws Exception {
    precheckUrl = PRECHECK_URL;
    sharedSecret = SECRET;
    stubConfig(VALID_CONFIG);
    when(member.statusCode()).thenReturn(200);
    when(member.body()).thenReturn("{\"nick\":\"Mav\",\"roles\":[\"999\"]}");
    when(discord.send(
            any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(member);
    when(backendChecker.check(any(), any(), any(), any(), any()))
        .thenReturn(BackendAccountChecker.Result.NOT_EXISTS);
    DiscordMembershipChecker realChecker =
        new DiscordMembershipChecker(discord, Duration.ofSeconds(1), 2, Duration.ofMillis(10));
    DiscordGuildRoleGateAuthenticator gate =
        new DiscordGuildRoleGateAuthenticator(realChecker, backendChecker) {
          @Override
          Brokered brokered(AuthenticationFlowContext ctx) {
            return new Brokered("tok", "Maverick", "mav@example.com");
          }

          @Override
          boolean isAccountLinking(AuthenticationFlowContext ctx) {
            return false;
          }

          @Override
          String backendPrecheckUrl() {
            return precheckUrl;
          }

          @Override
          String backendSharedSecret() {
            return sharedSecret;
          }
        };

    gate.authenticate(context);

    verify(discord, times(1))
        .send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    verify(backendChecker)
        .check(eq(PRECHECK_URL), eq(SECRET), eq("Maverick"), eq("mav@example.com"), eq("Mav"));
    verify(context).success();
  }

  /** A denial carries no member body, so the fail-open precheck never runs on a denied login. */
  @Test
  void aDeniedLookupCarriesNoMemberBody() {
    DiscordMembershipChecker.MemberLookup denied =
        DiscordMembershipChecker.MemberLookup.denied(DiscordMembershipChecker.Result.DENIED_ERROR);

    org.junit.jupiter.api.Assertions.assertNull(denied.memberBody());
    org.junit.jupiter.api.Assertions.assertFalse(denied.toString().contains("roles"));
  }

  @Test
  void skipsPrecheck_whenUrlNotHttps() {
    precheckUrl = "http://backend:11261/internal/discord/account-existence";
    sharedSecret = SECRET;
    stubConfig(VALID_CONFIG);
    when(checker.lookup(any(), any(), any(), any())).thenReturn(allowed(null));

    authenticator("tok", "Maverick", "mav@example.com").authenticate(context);

    verify(context).success();
    verifyNoInteractions(backendChecker);
  }
}
