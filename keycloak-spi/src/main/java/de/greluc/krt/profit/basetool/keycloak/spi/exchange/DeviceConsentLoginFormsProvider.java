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

import jakarta.ws.rs.core.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.keycloak.forms.login.freemarker.FreeMarkerLoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OAuth2DeviceUserCodeProvider;
import org.keycloak.protocol.oidc.grants.device.DeviceGrantType;

/**
 * Keycloak's FreeMarker login forms that also hand a device login's verified user code to the
 * consent page, so {@code login-oauth-grant.ftl} can show it next to the phishing warning
 * (REQ-XCH-005, ADR-0228).
 */
public class DeviceConsentLoginFormsProvider extends FreeMarkerLoginFormsProvider {

  /** The template attribute that carries the user code; absent outside a device login. */
  public static final String USER_CODE_ATTRIBUTE = "krtDeviceUserCode";

  /**
   * Creates the forms for one Keycloak session.
   *
   * @param session the Keycloak session
   */
  public DeviceConsentLoginFormsProvider(@NotNull KeycloakSession session) {
    super(session);
  }

  /**
   * Renders the consent page, with the user code attribute set when the authentication session
   * verified a device user code.
   *
   * @return the consent page
   */
  @Override
  public Response createOAuthGrant() {
    exposeDeviceUserCode();
    return super.createOAuthGrant();
  }

  /**
   * Sets {@link #USER_CODE_ATTRIBUTE} from the authentication session's verified device user code,
   * in the form the client was given to show, and removes it when there is none.
   */
  void exposeDeviceUserCode() {
    String code =
        authenticationSession == null
            ? null
            : authenticationSession.getClientNote(DeviceGrantType.OAUTH2_DEVICE_VERIFIED_USER_CODE);
    if (code == null || code.isBlank()) {
      setAttribute(USER_CODE_ATTRIBUTE, null);
      return;
    }
    OAuth2DeviceUserCodeProvider codes = session.getProvider(OAuth2DeviceUserCodeProvider.class);
    setAttribute(USER_CODE_ATTRIBUTE, codes == null ? code : codes.display(code));
  }

  /**
   * Reads one template attribute.
   *
   * @param name the attribute name
   * @return its value, or {@code null} when it is not set
   */
  @Nullable
  Object attribute(@NotNull String name) {
    return attributes.get(name);
  }
}
