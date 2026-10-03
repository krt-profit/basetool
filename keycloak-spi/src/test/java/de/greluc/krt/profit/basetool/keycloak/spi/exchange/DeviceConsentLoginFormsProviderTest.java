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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.forms.login.LoginFormsProviderFactory;
import org.keycloak.forms.login.LoginFormsSpi;
import org.keycloak.forms.login.freemarker.FreeMarkerLoginFormsProviderFactory;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OAuth2DeviceUserCodeProvider;
import org.keycloak.protocol.oidc.grants.device.DeviceGrantType;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.services.DefaultKeycloakSessionFactory;
import org.keycloak.sessions.AuthenticationSessionModel;

/** The login forms that hand a device login's user code to the consent page (REQ-XCH-005). */
class DeviceConsentLoginFormsProviderTest {

  private final KeycloakSession session = mock(KeycloakSession.class);
  private final AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
  private DeviceConsentLoginFormsProvider forms;

  @BeforeEach
  void setUp() {
    when(session.getContext()).thenReturn(mock(KeycloakContext.class));
    forms = new DeviceConsentLoginFormsProvider(session);
  }

  @Test
  void aDeviceLoginHandsItsUserCodeToTheConsentPageAsTheClientShowsIt() {
    OAuth2DeviceUserCodeProvider codes = mock(OAuth2DeviceUserCodeProvider.class);
    when(codes.display("WDJBMJHT")).thenReturn("WDJB-MJHT");
    when(session.getProvider(OAuth2DeviceUserCodeProvider.class)).thenReturn(codes);
    when(authSession.getClientNote(DeviceGrantType.OAUTH2_DEVICE_VERIFIED_USER_CODE))
        .thenReturn("WDJBMJHT");
    forms.setAuthenticationSession(authSession);

    forms.exposeDeviceUserCode();

    assertEquals("WDJB-MJHT", forms.attribute(DeviceConsentLoginFormsProvider.USER_CODE_ATTRIBUTE));
  }

  @Test
  void withoutAUserCodeProviderTheStoredCodeIsShown() {
    when(authSession.getClientNote(DeviceGrantType.OAUTH2_DEVICE_VERIFIED_USER_CODE))
        .thenReturn("WDJBMJHT");
    forms.setAuthenticationSession(authSession);

    forms.exposeDeviceUserCode();

    assertEquals("WDJBMJHT", forms.attribute(DeviceConsentLoginFormsProvider.USER_CODE_ATTRIBUTE));
  }

  @Test
  void aBrowserLoginCarriesNoUserCode() {
    forms.setAuthenticationSession(authSession);

    forms.exposeDeviceUserCode();

    assertNull(forms.attribute(DeviceConsentLoginFormsProvider.USER_CODE_ATTRIBUTE));
  }

  @Test
  void aBlankNoteOrNoAuthenticationSessionCarriesNoUserCode() {
    forms.setAttribute(DeviceConsentLoginFormsProvider.USER_CODE_ATTRIBUTE, "STALE-CODE");

    forms.exposeDeviceUserCode();

    assertNull(forms.attribute(DeviceConsentLoginFormsProvider.USER_CODE_ATTRIBUTE));

    when(authSession.getClientNote(DeviceGrantType.OAUTH2_DEVICE_VERIFIED_USER_CODE))
        .thenReturn(" ");
    forms.setAuthenticationSession(authSession);
    forms.exposeDeviceUserCode();

    assertNull(forms.attribute(DeviceConsentLoginFormsProvider.USER_CODE_ATTRIBUTE));
  }

  @Test
  void theFactoryOutranksKeycloaksOwnFormsWithoutConfiguration() {
    DeviceConsentLoginFormsProviderFactory factory = new DeviceConsentLoginFormsProviderFactory();
    FreeMarkerLoginFormsProviderFactory keycloaks = new FreeMarkerLoginFormsProviderFactory();
    Map<String, ProviderFactory> installed = new LinkedHashMap<>();
    installed.put(keycloaks.getId(), keycloaks);
    installed.put(factory.getId(), factory);

    String chosen =
        DefaultKeycloakSessionFactory.resolveDefaultProvider(installed, new LoginFormsSpi());

    assertEquals(DeviceConsentLoginFormsProviderFactory.PROVIDER_ID, chosen);
    assertInstanceOf(DeviceConsentLoginFormsProvider.class, factory.create(session));
  }

  @Test
  void theFactoryIsRegisteredForTheLoginSpi() throws IOException {
    try (InputStream in =
        getClass()
            .getResourceAsStream(
                "/META-INF/services/" + LoginFormsProviderFactory.class.getName())) {
      assertTrue(in != null, "service file present");
      String registered = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      assertEquals(DeviceConsentLoginFormsProviderFactory.class.getName(), registered);
    }
    assertTrue(
        ServiceLoader.load(LoginFormsProviderFactory.class).stream()
            .anyMatch(p -> p.type() == DeviceConsentLoginFormsProviderFactory.class));
  }
}
