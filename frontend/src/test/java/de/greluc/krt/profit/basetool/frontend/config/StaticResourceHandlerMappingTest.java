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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

/**
 * Pins the narrow static-resource handler set of {@link WebMvcConfig} against the asset trees on
 * the classpath, so a controller route never walks the {@link ResourceUrlProvider} chain and every
 * asset tree is still served.
 */
@SpringBootTest
@ActiveProfiles("test")
class StaticResourceHandlerMappingTest {

  /** The classpath roots {@link WebMvcConfig} serves from, in the order it lists them. */
  private static final String[] ASSET_LOCATIONS = {
    "classpath*:/META-INF/resources/*", "classpath*:/static/*"
  };

  /** A content-hashed asset URL, as {@code VersionResourceResolver} emits it. */
  private static final Pattern CONTENT_HASHED = Pattern.compile(".*-[0-9a-f]{32}\\.[a-z0-9]+$");

  @Autowired private WebApplicationContext context;

  @Autowired private ResourceUrlProvider resourceUrlProvider;

  @MockitoBean private WebClient webClient;

  @MockitoBean(name = "termsDocumentClient")
  private WebClient termsDocumentClient;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * The registered patterns are exactly the asset trees on the classpath, derived from {@code
   * classpath*:}; fails on an unserved tree and on a catch-all pattern.
   *
   * @throws IOException if the classpath cannot be scanned
   */
  @Test
  void registeredPatternsMatchTheClasspathExactly() throws IOException {
    Set<String> expected = new TreeSet<>();
    PathMatchingResourcePatternResolver resolver =
        new PathMatchingResourcePatternResolver(context.getClassLoader());
    for (String location : ASSET_LOCATIONS) {
      for (Resource resource : resolver.getResources(location)) {
        String name = resource.getFilename();
        assertNotNull(name, () -> "unnamed classpath entry under " + location);
        expected.add(resource.getFile().isDirectory() ? "/" + name + "/**" : "/" + name);
      }
    }

    SimpleUrlHandlerMapping mapping =
        context.getBean("resourceHandlerMapping", SimpleUrlHandlerMapping.class);

    assertEquals(
        expected,
        new TreeSet<>(mapping.getUrlMap().keySet()),
        "the resource handler patterns must match the asset trees on the classpath exactly — a new"
            + " tree needs a pattern in WebMvcConfig, and a catch-all must never come back");
  }

  /**
   * No asset tree lies inside the ETag filter's scope, and the filter is not on {@code /*}
   * (FE-PERF-03).
   */
  @Test
  void noAssetTreeIsInsideTheEtagFilterScope() {
    SimpleUrlHandlerMapping mapping =
        context.getBean("resourceHandlerMapping", SimpleUrlHandlerMapping.class);
    Set<String> servletPatterns = new TreeSet<>();
    for (String pattern : mapping.getUrlMap().keySet()) {
      servletPatterns.add(
          pattern.endsWith("/**") ? pattern.substring(0, pattern.length() - 1) : pattern);
    }

    assertTrue(
        EtagConfig.ETAG_URL_PATTERNS.stream().noneMatch(servletPatterns::contains),
        () ->
            "asset trees must stay outside the ETag filter; found: "
                + EtagConfig.ETAG_URL_PATTERNS);
    assertFalse(
        EtagConfig.ETAG_URL_PATTERNS.contains("/*"),
        "the ETag filter buffers every response it covers; it must not cover every route again");
  }

  /**
   * {@link ResourceUrlProvider} does not walk the resource chain for a controller route.
   *
   * @param lookupPath a route served by a controller, never by a file
   */
  @ParameterizedTest
  @ValueSource(strings = {"/missions", "/bank", "/orders", "/.well-known/assetlinks.json"})
  void controllerRoutesAreNotProbedAgainstTheAssetTrees(String lookupPath) {
    assertNull(
        resourceUrlProvider.getForLookupPath(lookupPath),
        () -> lookupPath + " is a controller route and must not match a resource handler");
  }

  /**
   * One real file per asset tree is served, with the immutable cache header.
   *
   * @param path the asset URL to fetch
   * @throws Exception if the request cannot be performed
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/css/styles.css",
        "/js/escape-html.js",
        "/fonts/Lato-Regular.woff2",
        "/images/made-by-the-community.png",
        "/images/pattern.svg",
        "/logos/basetool-favicon.svg",
        "/robots.txt"
      })
  @WithAnonymousUser
  void everyAssetTreeServesItsFiles(String path) throws Exception {
    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
  }

  /**
   * A path that names no file answers 404, not 500, both inside an asset tree and outside every
   * pattern.
   *
   * @param path a path that names no file
   * @throws Exception if the request cannot be performed
   */
  @ParameterizedTest
  @ValueSource(strings = {"/css/typo.css", "/favicon.ico", "/sm/deadbeef.map"})
  @WithAnonymousUser
  void aPathThatNamesNoFileAnswersNotFound(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isNotFound());
  }

  /**
   * Every asset tree resolves its files to a content-hashed URL through {@link
   * ResourceUrlProvider}, which the one-year {@code immutable} cache header relies on.
   *
   * @param path an asset path, one per registered tree
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/css/styles.css",
        "/js/escape-html.js",
        "/fonts/Lato-Regular.woff2",
        "/images/made-by-the-community.png",
        "/images/pattern.svg",
        "/logos/basetool-favicon.svg"
      })
  void everyAssetTreeResolvesToAContentHashedUrl(String path) {
    String resolved = resourceUrlProvider.getForLookupPath(path);

    assertNotNull(resolved, () -> path + " must resolve through a resource handler");
    assertTrue(
        CONTENT_HASHED.matcher(resolved).matches(),
        () -> path + " must resolve to a content-hashed URL, got " + resolved);
  }
}
