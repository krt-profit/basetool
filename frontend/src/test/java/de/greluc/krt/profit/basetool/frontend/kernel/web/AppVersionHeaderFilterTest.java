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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link AppVersionHeaderFilter} (REQ-FE-042): every answer names the running
 * version, the build's version or the {@code dev} fallback, and the request goes on unchanged.
 */
class AppVersionHeaderFilterTest {

  @Test
  void everyAnswerNamesTheBuildVersionAndTheRequestGoesOn() throws Exception {
    Properties props = new Properties();
    props.setProperty("version", "1.27.0");
    AppVersionHeaderFilter filter =
        new AppVersionHeaderFilter(Optional.of(new BuildProperties(props)));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/favicon.ico");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals("1.27.0", response.getHeader(AppVersionHeaderFilter.HEADER));
    assertSame(request, chain.getRequest());
  }

  @Test
  void withoutBuildInfoTheAnswerNamesTheDevFallback() throws Exception {
    AppVersionHeaderFilter filter = new AppVersionHeaderFilter(Optional.empty());
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        new MockHttpServletRequest("GET", "/missions"), response, new MockFilterChain());

    assertEquals("dev", response.getHeader(AppVersionHeaderFilter.HEADER));
  }
}
