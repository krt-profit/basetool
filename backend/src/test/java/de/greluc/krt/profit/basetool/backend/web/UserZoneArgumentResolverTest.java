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

package de.greluc.krt.profit.basetool.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Unit tests for {@link UserZoneArgumentResolver}, asserting the header-parse contract the resolver
 * consolidated: valid zones parse (with trimming), and absent/blank/invalid values fall back to
 * {@code null} so the report services render in UTC.
 */
class UserZoneArgumentResolverTest {

  private final UserZoneArgumentResolver resolver = new UserZoneArgumentResolver();

  /** Reflection target providing the {@code @UserZone ZoneId} and control parameters. */
  @SuppressWarnings("unused")
  private void handlers(@UserZone ZoneId zone, @UserZone String zoneOnWrongType, ZoneId plain) {}

  private static MethodParameter param(int index) throws NoSuchMethodException {
    Method method =
        UserZoneArgumentResolverTest.class.getDeclaredMethod(
            "handlers", ZoneId.class, String.class, ZoneId.class);
    return new MethodParameter(method, index);
  }

  @Test
  void supportsUserZoneOnZoneId() throws Exception {
    assertThat(resolver.supportsParameter(param(0))).isTrue();
  }

  @Test
  void rejectsUserZoneOnNonZoneIdParameter() throws Exception {
    assertThat(resolver.supportsParameter(param(1))).isFalse();
  }

  @Test
  void rejectsUnannotatedZoneIdParameter() throws Exception {
    assertThat(resolver.supportsParameter(param(2))).isFalse();
  }

  @Test
  void resolvesValidZoneFromHeader() throws Exception {
    NativeWebRequest request = mock(NativeWebRequest.class);
    when(request.getHeader(UserZoneArgumentResolver.HEADER)).thenReturn("Europe/Berlin");
    Object resolved = resolver.resolveArgument(param(0), null, request, null);
    assertThat(resolved).isEqualTo(ZoneId.of("Europe/Berlin"));
  }

  @Test
  void resolvesToNullWhenHeaderAbsent() throws Exception {
    NativeWebRequest request = mock(NativeWebRequest.class);
    when(request.getHeader(UserZoneArgumentResolver.HEADER)).thenReturn(null);
    assertThat(resolver.resolveArgument(param(0), null, request, null)).isNull();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "Not/A/Zone", "Europe/Nowhere", "12345"})
  void parseFallsBackToNullForAbsentOrInvalidValues(String raw) {
    assertThat(UserZoneArgumentResolver.parse(raw)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Europe/Berlin", "  Europe/Berlin  ", "UTC", "America/New_York", "Z"})
  void parseAcceptsValidZonesAndTrims(String raw) {
    assertThat(UserZoneArgumentResolver.parse(raw)).isEqualTo(ZoneId.of(raw.trim()));
  }
}
