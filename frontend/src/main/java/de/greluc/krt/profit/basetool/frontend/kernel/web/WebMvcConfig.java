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

import java.time.Duration;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.CssLinkResourceTransformer;
import org.springframework.web.servlet.resource.VersionResourceResolver;

/** Spring configuration for Web Mvc. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  /**
   * The file-name part of a content-hashed script URL such as {@code bank-<32 hex>.js}: a name, a
   * dash and 32 characters. A script URL matching it is immutable; every other script URL is
   * revalidated.
   */
  static final String HASHED_FILE = "*-" + "?".repeat(32) + ".js";

  /**
   * Registers one resource handler per asset tree present on the classpath, rather than a
   * catch-all, so resource-URL lookups for controller routes skip the resolver chain.
   *
   * <p>Each pattern's locations point into its own tree, since the handler only sees the part of
   * the path matched by the wildcard.
   *
   * @param registry the registry to add the asset handlers to; never {@code null}
   */
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    addAssetTree(registry, "/css/**", "classpath:/static/css/");
    addAssetTree(registry, "/fonts/**", "classpath:/static/fonts/");
    addAssetTree(
        registry,
        "/images/**",
        "classpath:/META-INF/resources/images/",
        "classpath:/static/images/");
    addAssetTree(registry, "/js/*/" + HASHED_FILE, "classpath:/static/js/");
    registerRevalidatedTree(registry, "/js/**", "classpath:/static/js/");
    addAssetTree(registry, "/logos/**", "classpath:/META-INF/resources/logos/");
    addAssetTree(registry, "/robots.txt", "classpath:/static/");
    registerRevalidatedTree(registry, "/favicon.ico", "classpath:/static/");
  }

  /**
   * Registers a tree whose URLs are not content-hashed: the same resource chain, but {@code
   * Cache-Control: no-cache}, so a browser revalidates by {@code Last-Modified} instead of holding
   * a stale file for a year. A module reached outside the import map, or a hand-typed URL, lands
   * here (REQ-FE-035).
   *
   * @param registry the registry to add the handler to; never {@code null}
   * @param pathPattern the URL pattern to serve; never {@code null}
   * @param locations the classpath locations backing that pattern; never {@code null} or empty
   */
  private static void registerRevalidatedTree(
      @NotNull ResourceHandlerRegistry registry, String pathPattern, String... locations) {
    registry
        .addResourceHandler(pathPattern)
        .addResourceLocations(locations)
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
  }

  /**
   * Registers one asset tree with the shared resource chain: content-hash versioning via {@link
   * VersionResourceResolver} and {@code Cache-Control: max-age=31536000, public, immutable}.
   *
   * @param registry the registry to add the handler to; never {@code null}
   * @param pathPattern the URL pattern to serve, e.g. {@code /css/**}; never {@code null}
   * @param locations the classpath locations backing that pattern, in resolution order; never
   *     {@code null} and never empty
   */
  private static void addAssetTree(
      @NotNull ResourceHandlerRegistry registry, String pathPattern, String... locations) {
    registry
        .addResourceHandler(pathPattern)
        .addResourceLocations(locations)
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
        .resourceChain(true)
        .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"))
        .addTransformer(new CssLinkResourceTransformer());
  }
}
