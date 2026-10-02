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

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.jetbrains.annotations.NotNull;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.UserSessionManager;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

/**
 * Ends one client's presence in a member's sessions without signing the member out of any other
 * client (REQ-XCH-008): {@code DELETE /admin/realms/{realm}/basetool-exchange/users/{user}/clients/
 * {client}/sessions}. The caller needs {@code manage-users} over the member, the same right that
 * already lets it end a whole session.
 */
@JBossLog
@RequiredArgsConstructor
public class ExchangeClientSessionResource {

  private final KeycloakSession session;
  private final RealmModel realm;
  private final AdminPermissionEvaluator auth;
  private final AdminEventBuilder adminEvent;

  /**
   * Detaches the client from every online session of the member, which logs it out there and makes
   * its refresh tokens fail, and revokes its offline sessions. The member's other clients keep
   * their sessions. An unknown member or client has nothing to end. JAX-RS answers {@code 204}.
   *
   * @param userId the member's Keycloak id
   * @param clientId the client id
   * @throws jakarta.ws.rs.ForbiddenException when the caller may not manage the member
   */
  @DELETE
  @Path("users/{user}/clients/{client}/sessions")
  public void endClientSessions(
      @PathParam("user") @NotNull String userId, @PathParam("client") @NotNull String clientId) {
    UserModel user = session.users().getUserById(realm, userId);
    if (user == null) {
      auth.users().requireManage();
      return;
    }
    auth.users().requireManage(user);
    ClientModel client = realm.getClientByClientId(clientId);
    if (client == null) {
      return;
    }
    List<UserSessionModel> sessions =
        session.sessions().getUserSessionsStream(realm, user).toList();
    int ended = 0;
    for (UserSessionModel userSession : sessions) {
      if (userSession.getAuthenticatedClientSessionByClient(client.getId()) != null) {
        AuthenticationManager.backchannelLogoutUserSessionFromClient(
            session,
            realm,
            userSession,
            client,
            session.getContext().getUri(),
            session.getContext().getHttpRequest().getHttpHeaders());
        ended++;
      }
    }
    boolean offline = new UserSessionManager(session).revokeOfflineToken(user, client);
    log.debugf(
        "Ended client %s in %d online sessions, offline sessions revoked: %s",
        clientId, ended, offline);
    adminEvent
        .operation(OperationType.ACTION)
        .resource(ResourceType.USER_SESSION)
        .resourcePath(session.getContext().getUri())
        .success();
  }
}
