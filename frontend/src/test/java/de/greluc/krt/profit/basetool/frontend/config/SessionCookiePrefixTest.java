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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer.CookieValue;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Verifies the {@code __Host-} session cookie (FE-SEC-06, REQ-SEC-025): its name and the {@code
 * Secure}, {@code Path=/} and no-{@code Domain} conditions in {@code application.yml}, and that no
 * profile overrides them.
 */
class SessionCookiePrefixTest {

  private static final String PREFIX = "server.servlet.session.cookie.";
  private static final String COOKIE_NAME = "__Host-SESSION";

  @Test
  void theBaseConfigurationNamesTheCookieWithTheHostPrefixAndMeetsItsConditions() {
    Properties base = yaml("application.yml");

    assertThat(base.getProperty(PREFIX + "name")).isEqualTo(COOKIE_NAME);
    assertThat(base.getProperty(PREFIX + "secure")).isEqualTo("true");
    assertThat(base.getProperty(PREFIX + "domain"))
        .as("a __Host- cookie must not carry a Domain attribute")
        .isNull();
    assertThat(base.getProperty(PREFIX + "path"))
        .as("a __Host- cookie must be scoped to Path=/")
        .isIn(null, "/");
  }

  static Stream<String> profileFiles() {
    return Stream.of("application-dev.yml", "application-prod.yml", "application-test.yml");
  }

  @ParameterizedTest
  @MethodSource("profileFiles")
  void noProfileOverridesTheCookieNameOrItsConditions(String file) {
    Properties profile = yaml(file);

    assertThat(profile.stringPropertyNames())
        .as("%s must not override the session cookie's name, Secure flag, Domain or Path", file)
        .doesNotContain(PREFIX + "name", PREFIX + "secure", PREFIX + "domain", PREFIX + "path");
  }

  @Test
  void theRenderedCookieSatisfiesTheBrowsersHostPrefixRules() {
    Properties base = yaml("application.yml");
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName(base.getProperty(PREFIX + "name"));
    serializer.setUseSecureCookie(Boolean.parseBoolean(base.getProperty(PREFIX + "secure")));
    serializer.setUseHttpOnlyCookie(Boolean.parseBoolean(base.getProperty(PREFIX + "http-only")));
    serializer.setSameSite("Strict");
    serializer.setCookieMaxAge((int) Duration.ofDays(30).toSeconds());

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContextPath("");
    MockHttpServletResponse response = new MockHttpServletResponse();
    serializer.writeCookieValue(new CookieValue(request, response, "session-id"));

    String header = response.getHeader("Set-Cookie");
    assertThat(header).startsWith(COOKIE_NAME + "=");
    assertThat(header).contains("; Path=/;").contains("; Secure").contains("; HttpOnly");
    assertThat(header).doesNotContainIgnoringCase("Domain=");
  }

  private static Properties yaml(String file) {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource(file));
    Properties properties = factory.getObject();
    assertThat(properties).as("%s must be on the classpath", file).isNotNull();
    return properties;
  }
}
