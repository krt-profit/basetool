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

package de.greluc.krt.profit.basetool.backend.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.lang.reflect.Method;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Unit tests for {@link UserZoneArgumentResolver}: the header-parse contract, and that a {@link
 * UserZone} parameter reaches the controller through Spring MVC's full resolver chain.
 */
class UserZoneArgumentResolverTest {

  private final UserZoneArgumentResolver resolver = new UserZoneArgumentResolver();

  /** Reflection target providing a {@link UserZone} and a plain {@link ZoneId} parameter. */
  @SuppressWarnings("unused")
  private void handlers(UserZone zone, ZoneId plain) {}

  private static MethodParameter param(int index) throws NoSuchMethodException {
    Method method =
        UserZoneArgumentResolverTest.class.getDeclaredMethod(
            "handlers", UserZone.class, ZoneId.class);
    return new MethodParameter(method, index);
  }

  /** Echoes the zone Spring MVC bound to its {@link UserZone} parameter. */
  @RestController
  static class ZoneProbeController {

    /**
     * Answers the bound zone id.
     *
     * @param zone the zone the resolver bound
     * @return the zone id, or {@code none} without a zone
     */
    @GetMapping("/probe")
    String probe(UserZone zone) {
      return zone.zone() == null ? "none" : zone.zone().getId();
    }
  }

  @Test
  void supportsUserZoneParameter() throws Exception {
    assertThat(resolver.supportsParameter(param(0))).isTrue();
  }

  @Test
  void rejectsPlainZoneIdParameter() throws Exception {
    assertThat(resolver.supportsParameter(param(1))).isFalse();
  }

  @Test
  void resolvesValidZoneFromHeader() throws Exception {
    NativeWebRequest request = mock(NativeWebRequest.class);
    when(request.getHeader(UserZoneArgumentResolver.HEADER)).thenReturn("Europe/Berlin");
    assertThat(resolver.resolveArgument(param(0), null, request, null))
        .isEqualTo(new UserZone(ZoneId.of("Europe/Berlin")));
  }

  @Test
  void resolvesToEmptyZoneWhenHeaderAbsent() throws Exception {
    NativeWebRequest request = mock(NativeWebRequest.class);
    when(request.getHeader(UserZoneArgumentResolver.HEADER)).thenReturn(null);
    assertThat(resolver.resolveArgument(param(0), null, request, null))
        .isEqualTo(new UserZone(null));
  }

  @Test
  void springMvcBindsTheHeaderZoneRatherThanTheServerZone() throws Exception {
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new ZoneProbeController())
            .setCustomArgumentResolvers(new UserZoneArgumentResolver())
            .build();

    mvc.perform(get("/probe").header(UserZoneArgumentResolver.HEADER, "Pacific/Auckland"))
        .andExpect(content().string("Pacific/Auckland"));
    mvc.perform(get("/probe")).andExpect(content().string("none"));
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
