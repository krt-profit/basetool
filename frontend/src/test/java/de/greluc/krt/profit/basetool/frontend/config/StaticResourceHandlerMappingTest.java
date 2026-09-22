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
 * Pins the narrow static-resource handler set of {@link WebMvcConfig} against the asset trees that
 * actually exist on the classpath.
 *
 * <p><strong>Why this test exists.</strong> The handler used to be registered on a catch-all
 * pattern with all four of Spring Boot's default locations, which made it match every URL in the
 * application. With {@code spring.web.resources.chain.strategy.content.enabled} on, {@code
 * ResourceUrlEncodingFilter} routes every Thymeleaf {@code @{...}} link through {@link
 * ResourceUrlProvider}, so each link to a controller route — the shared chrome fragments alone emit
 * 77 of them, on every page — was probed against all four classpath locations on the way out.
 * {@code CachingResourceResolver} caches only non-{@code null} results, so those lookups missed
 * again on every single render: 267 us each, measured. Narrowing the patterns is the fix, and the
 * risk it carries is the mirror image: a tree that loses its pattern stops being served, and a
 * missing stylesheet degrades quietly rather than loudly.
 *
 * <p>{@link #registeredPatternsMatchTheClasspathExactly()} is therefore the load-bearing assertion:
 * it derives the expected pattern set from the classpath rather than from a hand-written list, so
 * adding {@code static/audio/} without a handler fails here instead of 404ing in production, and a
 * reintroduced catch-all — for example by dropping {@code spring.web.resources.add-mappings:
 * false}, which puts Boot's own into the same registry — fails here too.
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
   * The registered patterns are exactly the asset trees on the classpath — no more, no less.
   *
   * <p>Derived from {@code classpath*:} rather than hard-coded, so both ways this configuration can
   * break fail here: a new asset tree with no handler (which would 404), and a reintroduced
   * catch-all (which would restore the per-render chain walk).
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
        // A directory is served as a tree; a bare file at the root as itself (robots.txt).
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
   * Every asset tree lies inside the ETag filter's scope, and the filter is not back on {@code /*}
   * (FE-PERF-03).
   *
   * <p>{@link EtagConfig} lists the asset-tree prefixes by hand, as servlet patterns ({@code
   * /css/*} for the handler's {@code /css/**}). A tree added to {@link WebMvcConfig} without its
   * prefix there would silently lose its ETag; a return to {@code /*} would put the buffer back in
   * front of every page render and the notification stream.
   */
  @Test
  void everyAssetTreeIsInsideTheEtagFilterScope() {
    SimpleUrlHandlerMapping mapping =
        context.getBean("resourceHandlerMapping", SimpleUrlHandlerMapping.class);
    Set<String> servletPatterns = new TreeSet<>();
    for (String pattern : mapping.getUrlMap().keySet()) {
      servletPatterns.add(
          pattern.endsWith("/**") ? pattern.substring(0, pattern.length() - 1) : pattern);
    }

    assertTrue(
        EtagConfig.ETAG_URL_PATTERNS.containsAll(servletPatterns),
        () ->
            "every asset tree needs its prefix in EtagConfig.ETAG_URL_PATTERNS; missing: "
                + servletPatterns.stream()
                    .filter(p -> !EtagConfig.ETAG_URL_PATTERNS.contains(p))
                    .toList());
    assertFalse(
        EtagConfig.ETAG_URL_PATTERNS.contains("/*"),
        "the ETag filter buffers every response it covers; it must not cover every route again");
  }

  /**
   * {@link ResourceUrlProvider} does not walk the resource chain for a controller route.
   *
   * <p>This is the property the narrowing buys. The lookup returned {@code null} before the change
   * too — the difference is that it now returns {@code null} because no pattern matches, instead of
   * after probing four classpath locations and then declining to cache the miss.
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
   * <p>Each row is a different tree and a different backing location, so a pattern pointed at the
   * wrong {@code classpath:} directory fails here rather than in a browser.
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
   * A path that names no file still answers 404 — not 500.
   *
   * <p>This is the regression the narrowing very nearly shipped. While the handler was a catch-all
   * it matched every unmapped URL, so a missing file raised {@code NoResourceFoundException} and
   * {@code GlobalExceptionHandler} rendered the 404 page. Once the patterns became narrow, a path
   * outside every tree matched no handler at all, the dispatcher raised {@code
   * NoHandlerFoundException} instead — reachable for the first time because {@code
   * spring.web.resources.add-mappings} is now {@code false} — and it fell through to the {@code
   * Exception} catch-all as a 500. Both exceptions are mapped to the 404 page now, and both shapes
   * are exercised here: {@code /css/typo.css} matches a tree and misses the file, the other two
   * match no pattern.
   *
   * <p>All three are {@code permitAll} in {@link SecurityConfig}, so a redirect here would mean a
   * different defect — the one {@code SecurityConfigStaticAssetPermitAllTest} guards.
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
   * Every tree still resolves its files to a content-hashed URL.
   *
   * <p>The hash is what makes the one-year {@code immutable} cache header safe, so a tree that lost
   * its {@link org.springframework.web.servlet.resource.VersionResourceResolver} is the expensive
   * mistake this configuration can make: its URLs would then be cached for a year with no way to
   * revise them. One file per tree, because the resolver is attached per registration.
   *
   * <p>Asserted against {@link ResourceUrlProvider} rather than against a rendered page, because
   * that provider is precisely what {@code ResourceUrlEncodingFilter} consults when it rewrites a
   * {@code @{...}} link — and MockMvc does not reproduce the filter-plus-interceptor pairing that
   * makes the rewrite happen in a real container, so a page-level assertion here would pass or fail
   * for reasons that have nothing to do with this configuration.
   *
   * <p>{@code /robots.txt} is absent on purpose: it is registered as an exact pattern, so no
   * wildcard remains for the version strategy to hash, and it is fetched by crawlers at its literal
   * path anyway.
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
