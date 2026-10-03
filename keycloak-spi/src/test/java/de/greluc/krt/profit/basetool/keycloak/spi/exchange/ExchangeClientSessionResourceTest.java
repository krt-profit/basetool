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

package de.greluc.krt.profit.basetool.keycloak.spi.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.UserSessionManager;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;
import org.keycloak.services.resources.admin.fgap.UserPermissionEvaluator;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/** The admin extension that ends one client inside a member's shared sessions (REQ-XCH-008). */
class ExchangeClientSessionResourceTest {

  private static final String USER_ID = "00000000-0000-0000-0000-0000000000a1";
  private static final String CLIENT_ID = "basetool-sc-extractor";
  private static final String CLIENT_UUID = "client-uuid";

  private final KeycloakSession session = mock(KeycloakSession.class);
  private final RealmModel realm = mock(RealmModel.class);
  private final AdminPermissionEvaluator auth = mock(AdminPermissionEvaluator.class);
  private final UserPermissionEvaluator users = mock(UserPermissionEvaluator.class);
  private final AdminEventBuilder adminEvent = mock(AdminEventBuilder.class, RETURNS_SELF);
  private final UserProvider userProvider = mock(UserProvider.class);
  private final UserSessionProvider sessions = mock(UserSessionProvider.class);
  private final UserModel user = mock(UserModel.class);
  private final ClientModel client = mock(ClientModel.class);
  private final ExchangeClientSessionResource resource =
      new ExchangeClientSessionResource(session, realm, auth, adminEvent);

  @BeforeEach
  void setUp() {
    when(auth.users()).thenReturn(users);
    when(session.users()).thenReturn(userProvider);
    when(session.sessions()).thenReturn(sessions);
    KeycloakContext context = mock(KeycloakContext.class);
    when(context.getHttpRequest()).thenReturn(mock(HttpRequest.class));
    when(session.getContext()).thenReturn(context);
    when(userProvider.getUserById(realm, USER_ID)).thenReturn(user);
    when(realm.getClientByClientId(CLIENT_ID)).thenReturn(client);
    when(client.getId()).thenReturn(CLIENT_UUID);
  }

  @Test
  void theClientLeavesTheSharedSessionAndTheOtherClientsStay() {
    UserSessionModel shared = mock(UserSessionModel.class);
    UserSessionModel webOnly = mock(UserSessionModel.class);
    when(shared.getAuthenticatedClientSessionByClient(CLIENT_UUID))
        .thenReturn(mock(AuthenticatedClientSessionModel.class));
    when(sessions.getUserSessionsStream(realm, user)).thenReturn(Stream.of(shared, webOnly));

    try (MockedStatic<AuthenticationManager> manager = mockStatic(AuthenticationManager.class);
        MockedConstruction<UserSessionManager> offline =
            mockConstruction(UserSessionManager.class)) {
      resource.endClientSessions(USER_ID, CLIENT_ID);

      manager.verify(
          () ->
              AuthenticationManager.backchannelLogoutUserSessionFromClient(
                  eq(session), eq(realm), eq(shared), eq(client), any(), any()));
      manager.verify(
          () ->
              AuthenticationManager.backchannelLogoutUserSessionFromClient(
                  any(), any(), eq(webOnly), any(), any(), any()),
          never());
      verify(offline.constructed().getFirst()).revokeOfflineToken(user, client);
    }
    verify(users).requireManage(user);
    verify(sessions, never()).removeUserSession(any(), any());
    verify(adminEvent).success();
  }

  @Test
  void aCallerWhoMayNotManageTheMemberEndsNothing() {
    doThrow(new Denied()).when(users).requireManage(user);

    try (MockedStatic<AuthenticationManager> manager = mockStatic(AuthenticationManager.class);
        MockedConstruction<UserSessionManager> offline =
            mockConstruction(UserSessionManager.class)) {
      assertThrows(Denied.class, () -> resource.endClientSessions(USER_ID, CLIENT_ID));

      manager.verifyNoInteractions();
      assertEquals(0, offline.constructed().size());
    }
    verify(sessions, never()).getUserSessionsStream(any(), any(UserModel.class));
  }

  @Test
  void anUnknownMemberStillNeedsTheRightAndEndsNothing() {
    when(userProvider.getUserById(realm, USER_ID)).thenReturn(null);
    doThrow(new Denied()).when(users).requireManage();

    assertThrows(Denied.class, () -> resource.endClientSessions(USER_ID, CLIENT_ID));

    verify(sessions, never()).getUserSessionsStream(any(), any(UserModel.class));
  }

  @Test
  void anUnknownClientEndsNothing() {
    when(realm.getClientByClientId(CLIENT_ID)).thenReturn(null);

    resource.endClientSessions(USER_ID, CLIENT_ID);

    verify(users).requireManage(user);
    verify(sessions, never()).getUserSessionsStream(any(), any(UserModel.class));
  }

  @Test
  void theFactoryMountsTheExtensionUnderItsId() {
    ExchangeClientSessionResourceProviderFactory factory =
        new ExchangeClientSessionResourceProviderFactory();

    assertEquals("basetool-exchange", factory.getId());
    assertInstanceOf(
        ExchangeClientSessionResource.class,
        factory.create(session).getResource(session, realm, auth, adminEvent));
  }

  /** Stands in for the permission evaluator's refusal, which needs a JAX-RS runtime to build. */
  private static final class Denied extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
