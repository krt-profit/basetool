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
   * Registers one resource handler per asset tree that actually exists on the classpath.
   *
   * <p><b>Why one handler per tree instead of a single catch-all.</b> {@code
   * spring.web.resources.chain.strategy.content.enabled} is on, which satisfies {@code
   * ConditionalOnEnabledResourceChain} and puts a {@code ResourceUrlEncodingFilter} in front of
   * every response. That filter routes every URL a Thymeleaf {@code @{...}} expression emits
   * through {@code ResourceUrlProvider#getForLookupPath}, which walks the resolver chain of every
   * registered handler whose pattern matches. While the pattern was a catch-all it matched
   * everything, so a {@code @{/missions}} — a controller route that can never be a file — was
   * probed against all four classpath locations on the way out. {@code CachingResourceResolver}
   * caches only non-{@code null} results, so those lookups never populate the cache and every page
   * render repeated all of them.
   *
   * <p>The cost was measured, not assumed: <b>267 us per controller-route lookup</b> with the
   * catch-all present against <b>0.25 us</b> without it, and 20 000 repeats of the same six routes
   * stayed expensive throughout, which is the missing cache showing. The shared chrome fragments
   * emit 77 such links on every page before a page's own content adds more.
   *
   * <p>Narrowing the patterns is what fixes it: a lookup path that matches no handler returns
   * {@code null} without touching the chain.
   *
   * <p><b>The pattern list is the classpath, verified rather than assumed.</b> Enumerating {@code
   * classpath*:} under all four configured locations at runtime yields exactly {@code images} and
   * {@code logos} under {@code META-INF/resources/}, and {@code css}, {@code fonts}, {@code
   * images}, {@code js} and {@code robots.txt} under {@code static/} — no dependency JAR
   * contributes anything, and {@code classpath:/resources/} and {@code classpath:/public/} do not
   * exist at all, which is why neither is listed below. {@code StaticResourceHandlerMappingTest}
   * pins that inventory, so a new asset tree that is added without a pattern here fails a test
   * rather than 404ing quietly in production.
   *
   * <p><b>Not listed, deliberately.</b> {@code /favicon.ico} and {@code /sm/**} are {@code
   * permitAll} in {@link SecurityConfig}, but nothing ships a file at either: the favicon is
   * declared by {@code <link rel="icon">} against {@code /logos/}, and {@code /sm/} is a path
   * third-party browser extensions probe (Sentry Replay), not a tree this app serves. Both answered
   * 404 before this change and answer 404 after it — those allow-list entries exist to keep the
   * probes off the OAuth entry point, not to serve them.
   *
   * <p><b>Each pattern's locations point into its own tree</b> because {@code
   * extractPathWithinPattern} hands the handler only the part of the path that the wildcard
   * matched: {@code /css/**} resolves {@code /css/styles.css} as {@code styles.css}, so the
   * location has to be {@code classpath:/static/css/} rather than {@code classpath:/static/}.
   * {@code /images/**} keeps both of its trees, in the original order, because the manufacturer
   * marks live under {@code META-INF/resources/images/} and the community badge and flags under
   * {@code static/images/}.
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
    addAssetTree(registry, "/js/**", "classpath:/static/js/");
    addAssetTree(registry, "/logos/**", "classpath:/META-INF/resources/logos/");
    addAssetTree(registry, "/robots.txt", "classpath:/static/");
  }

  /**
   * Registers one asset tree with the cache headers and resource chain every tree shares.
   *
   * <p>Spelled once because the chain below is the part that must not drift between trees: a tree
   * that lost its {@link VersionResourceResolver} would be served without a content hash and then
   * cached for a year as {@code immutable}, which is unrecoverable for that URL.
   *
   * <p>{@code setCacheControl} replaces the older {@code setCachePeriod(31536000)} so the emitted
   * header is {@code Cache-Control: max-age=31536000, public, immutable} instead of a bare {@code
   * max-age} — every static asset URL carries a content hash via the {@link
   * VersionResourceResolver} below, which means the resource at a given URL never changes and
   * {@code immutable} is the safe and correct hint to browsers ("do not even revalidate"). This
   * also lets us retire the standalone {@code StaticCacheHeaderFilter}: Spring's resource chain now
   * sets the exact same header set the filter used to inject.
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
