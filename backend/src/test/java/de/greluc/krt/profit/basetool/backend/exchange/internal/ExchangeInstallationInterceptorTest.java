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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;

class ExchangeInstallationInterceptorTest {

  private static final UUID MEMBER = UUID.fromString("5f1d2c3b-0000-0000-0000-0000000000d1");
  private static final String KEY = "k".repeat(43);

  private final ExchangeInstallationService service = mock(ExchangeInstallationService.class);
  private final ExchangeInstallationInterceptor interceptor =
      new ExchangeInstallationInterceptor(service);

  @Test
  void aRelayedRequestTouchesItsInstallation() {
    interceptor.postHandle(
        request(new Relayed("versekit", KEY)), new MockHttpServletResponse(), new Object(), null);

    verify(service).touch("versekit", MEMBER, KEY);
  }

  @Test
  void anythingElseTouchesNothing() {
    interceptor.postHandle(
        request(new Relayed(null, null)), new MockHttpServletResponse(), new Object(), null);
    interceptor.postHandle(
        request(new TestingAuthenticationToken("s", "c")),
        new MockHttpServletResponse(),
        new Object(),
        null);

    verifyNoInteractions(service);
  }

  @Test
  void aFailedWriteNeverFailsTheRequest() {
    doThrow(new IllegalStateException("db down")).when(service).touch(any(), any(), any());

    interceptor.postHandle(
        request(new Relayed("versekit", KEY)), new MockHttpServletResponse(), new Object(), null);

    verify(service).touch("versekit", MEMBER, KEY);
  }

  /**
   * Builds a request carrying a principal.
   *
   * @param principal the principal
   * @return the request
   */
  private static @NotNull MockHttpServletRequest request(
      @NotNull java.security.Principal principal) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/exchange/catalog/locations");
    request.setUserPrincipal(principal);
    return request;
  }

  /** A relayed acting member's authentication. */
  private static final class Relayed extends AbstractAuthenticationToken
      implements SubjectAuthentication {

    private final @Nullable String client;
    private final @Nullable String key;

    /**
     * Creates it.
     *
     * @param client the external client, or {@code null}
     * @param key the installation key, or {@code null}
     */
    Relayed(@Nullable String client, @Nullable String key) {
      super(List.of());
      this.client = client;
      this.key = key;
    }

    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public Object getPrincipal() {
      return subject();
    }

    @Override
    public @NotNull String subject() {
      return MEMBER.toString();
    }

    @Override
    public @Nullable String externalClient() {
      return client;
    }

    @Override
    public @Nullable String exchangeInstallationKey() {
      return key;
    }
  }
}
