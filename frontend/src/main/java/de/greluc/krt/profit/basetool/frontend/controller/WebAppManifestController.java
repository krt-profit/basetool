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

package de.greluc.krt.profit.basetool.frontend.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

/**
 * Serves {@code /manifest.webmanifest}, the document that lets a browser install the Basetool as an
 * app instead of bookmarking it as a page.
 *
 * <p>It exists for iPhone and iPad. Those are the members the tool has no native client for — the
 * Android app cannot follow there, because Apple's Web Distribution (the only channel shaped like
 * that app's GitHub-Releases delivery) requires eligibility bars a fan project cannot clear, so the
 * remaining routes are the App Store or nothing. A home-screen web app is what is left, and it
 * needs exactly this file plus the {@code <head>} tags in {@code fragments/head.html}. The full
 * analysis is {@code docs/APPLE_PLATFORM_FEASIBILITY.md} in the {@code basetool-android}
 * repository; the decision is ADR-0164.
 *
 * <p><strong>A controller rather than a file under {@code static/}.</strong> Four reasons, in
 * descending order of how badly a static file would fail:
 *
 * <ol>
 *   <li><strong>i18n.</strong> {@code description} is shown to the user in Android's install
 *       dialog, and every user-visible string in this application comes from the message bundles
 *       with no exceptions, so it is resolved through {@link MessageSource} rather than frozen into
 *       a JSON file. ({@code name} and {@code short_name} happen to be identical in both bundles —
 *       the product name is a proper noun — but they come from the same place for the same reason.)
 *   <li><strong>The icon URL is build-dependent.</strong> {@code /logos/**} is served with a
 *       content hash by {@link ResourceUrlProvider} under a one-year {@code immutable} cache
 *       ({@code WebMvcConfig}), so the manifest has to resolve the fingerprinted path at request
 *       time. A hardcoded {@code /logos/basetool-appicon-512.png} would pin installed home screens
 *       to a URL whose bytes can change but which no browser will ever revalidate.
 *   <li><strong>Content type.</strong> The registered type is {@code application/manifest+json},
 *       which Spring's static-resource handler does not know: a {@code .webmanifest} file would be
 *       served as {@code application/octet-stream}, and a browser is entitled to ignore it.
 *   <li><strong>No redirect.</strong> The browser fetches this on the landing page, before anyone
 *       has signed in. Behind {@code anyRequest().authenticated()} it would answer {@code 302} into
 *       the OAuth entry point and the manifest would silently be the login page — the identical
 *       trap {@link AssetLinksController} was written for, and the reason its path sits in the same
 *       {@code SecurityConfig} allow-list as this one.
 * </ol>
 *
 * <p><strong>The locale travels in the URL, and the fetch carries no credentials.</strong> The
 * {@code <link rel="manifest">} in {@code fragments/head.html} is rendered by a server that already
 * knows the reader's locale, so it appends {@code ?locale=…} and the response becomes a pure
 * function of its URL. The obvious-looking alternative — {@code crossorigin="use-credentials"}, so
 * the manifest fetch carries {@code KRT_LOCALE} — was tried first and reverted in the same pull
 * request, because it made every fetch an <em>authenticated</em> request: the unscoped
 * {@code @ControllerAdvice} beans that build the layout model run before <em>every</em> handler,
 * {@code @RestController}s included, so one manifest fetch cost five backend round trips, and the
 * consent gate and the role-sync filter each needed a carve-out for a public document. It bought
 * nothing in return: {@code name} and {@code short_name} are identical in both bundles, Safari
 * implements neither {@code lang} nor {@code description}, and the iOS home-screen label comes from
 * {@code apple-mobile-web-app-title} on the page itself. See ADR-0164.
 *
 * <p><strong>There is deliberately no service worker.</strong> "PWA" normally implies one, and this
 * one ships without: a worker that cached navigations would put member data — bank balances,
 * mission rosters, inventory — into a second store outside every path that wipes the first one,
 * while the backend marks those reads {@code no-store} (REQ-SEC-031) precisely so they are not
 * copied. iOS needs no worker for its Add to Home Screen flow, which is the case this ships for.
 * The cost is stated rather than hidden: Chromium browsers require a fetch-handling worker before
 * they offer their own install prompt, so on Android and desktop the app stays installable only
 * through the browser menu. ADR-0164 records that trade.
 */
@RestController
@RequiredArgsConstructor
public class WebAppManifestController {

  /**
   * The {@code Content-Type} for a web app manifest, per the W3C registration.
   *
   * <p>Spelled out rather than taken from {@code MediaType}, which has no constant for it.
   */
  private static final String MANIFEST_JSON = "application/manifest+json";

  /**
   * Splash-screen and install-dialog background: the page background, flat black.
   *
   * <p>Mirrors {@code --color-bg-black} in {@code styles.css} (REQ-UI-003). A mismatch here is
   * visible as a flash of the wrong colour while the app starts.
   */
  private static final String BACKGROUND_COLOR = "#000000";

  /**
   * Browser-UI colour: the page header, not the page background.
   *
   * <p>Mirrors {@code --color-bg-dark-gray}, the {@code header} fill. The status bar sits directly
   * above that header on a phone, so matching the header — not the black body — is what makes the
   * seam disappear.
   */
  private static final String THEME_COLOR = "#141414";

  /**
   * Unversioned classpath path of the home-screen icon, as it exists under {@code
   * META-INF/resources}.
   *
   * <p>Never emitted as-is: it is the lookup key handed to {@link ResourceUrlProvider}, which
   * answers with the content-hashed path the manifest actually ships.
   */
  private static final String ICON_LOOKUP_PATH = "/logos/basetool-appicon-512.png";

  /**
   * The locales this manifest can be rendered in, keyed by primary language subtag.
   *
   * <p>An allow-list rather than a pass-through, because the strings and the declared {@code lang}
   * have to agree. {@code spring.messages.fallback-to-system-locale} is {@code false} and the base
   * bundle holds <em>German</em> copy, so handing an arbitrary tag straight to {@link
   * MessageSource} produced a manifest that declared, say, {@code "lang": "fr"} over German text —
   * and an empty {@code lang} for a cookie value like {@code _DE}, which the specification does not
   * permit.
   */
  private static final Map<String, Locale> SUPPORTED_LOCALES =
      Map.of("de", Locale.GERMAN, "en", Locale.ENGLISH);

  /**
   * Last-resort copy, used only if a {@code pwa.*} key is missing from every bundle.
   *
   * <p>Not a translation and not a second source of truth: the bundles are authoritative and {@code
   * MessageKeyParityTest} is what keeps them complete. These exist so that a missing key degrades
   * to a plain manifest instead of a 500, because this endpoint's contract is "always 200, never a
   * redirect" and a browser reads it while installing. The product name is a proper noun and
   * byte-identical in every bundle, so the fallback for it is exact rather than approximate.
   */
  private static final String DEFAULT_NAME = "Profit Basetool";

  /**
   * Fallback short name.
   *
   * @see #DEFAULT_NAME
   */
  private static final String DEFAULT_SHORT_NAME = "Basetool";

  /**
   * Fallback description.
   *
   * @see #DEFAULT_NAME
   */
  /*
   * German, because DEFAULT_LOCALE is German.
   *
   * These three defaults only appear when a bundle key is missing, and the response then carries
   * `"lang": "de"` with English text, cached for an hour by the Cache-Control this controller sets.
   * A fallback that contradicts the locale it is served under is worse than the key being absent.
   *
   * <p>The value is `messages.properties`' own `pwa.description` — the German one, since that
   * file is the fallback bundle and German is the primary locale. `pwa.name` and
   * `pwa.short_name` need no such treatment: they are the product name and are byte-identical in
   * all three bundles.
   */
  private static final String DEFAULT_DESCRIPTION =
      "Einsätze, Aufträge, Lager und Raffinerie der Organisation.";

  /**
   * Locale used when the request names none, or names one that is not shipped.
   *
   * <p>German, and it must stay the same German {@code LocaleConfig} gives {@code
   * CookieLocaleResolver}. Nothing in the type system ties the two together, so {@link
   * WebAppManifestControllerTest#theDefaultLocaleMatchesTheResolvers()} does: change the resolver
   * alone and the manifest would keep emitting {@code "lang": "de"} with German {@code pwa.*} while
   * every page rendered English — cached {@code public, max-age=1h} and read by installers, so
   * every home screen added from then on would carry the wrong name with no error anywhere.
   */
  private static final Locale DEFAULT_LOCALE = Locale.GERMAN;

  /** Resolves the three user-visible strings for the requested locale. */
  private final MessageSource messageSource;

  /**
   * Turns {@link #ICON_LOOKUP_PATH} into the content-hashed URL the resource chain serves.
   *
   * <p>Autowired rather than constructed: this is Spring MVC's own {@code mvcResourceUrlProvider},
   * the same instance {@code ResourceUrlEncodingFilter} hands to Thymeleaf's {@code @{…}}, so the
   * manifest and the {@code apple-touch-icon} in the page head cannot disagree about which file
   * they mean.
   */
  private final ResourceUrlProvider resourceUrlProvider;

  /**
   * Returns the web app manifest for the locale named in the query string.
   *
   * <p><strong>The body is a pure function of the URL.</strong> Nothing is read from the session,
   * the cookies or {@code LocaleContextHolder}, so the response can be cached {@code public} and
   * needs no {@code Vary}. That is the point of carrying the locale in the URL: a language switch
   * changes the link the page renders, which changes the cache key, instead of invalidating one
   * entry on every unrelated cookie rotation.
   *
   * <p>There is deliberately no {@code produces} on the mapping. It would make content negotiation
   * part of the match, and {@code application/json} is not compatible with {@code
   * application/manifest+json}, so a caller that asks for JSON — the shape {@code krtFetch} and a
   * blackbox probe both send — got {@code HttpMediaTypeNotAcceptableException}, which the {@code
   * Exception} catch-all in {@code GlobalExceptionHandler} turns into a {@code 500} and an {@code
   * ERROR} log line on a public endpoint. Setting the content type on the response instead makes
   * Spring skip negotiation entirely and answer every {@code Accept} with the manifest.
   *
   * @param locale primary language subtag from {@code ?locale=…}; anything not in {@link
   *     #SUPPORTED_LOCALES} falls back to {@link #DEFAULT_LOCALE}, so the strings and the declared
   *     {@code lang} always agree. {@code null} when the parameter is absent.
   * @param request the current request, read only for its context path so the manifest's URLs stay
   *     correct under a non-root {@code server.servlet.context-path}
   * @return the manifest as {@code application/manifest+json}, always {@code 200} and never a
   *     redirect, for anonymous and authenticated requests alike.
   */
  @GetMapping("/manifest.webmanifest")
  public @NotNull ResponseEntity<WebAppManifest> manifest(
      @RequestParam(name = "locale", required = false) @Nullable String locale,
      @NotNull HttpServletRequest request) {
    final Locale resolved = resolveSupported(locale);
    final String root = request.getContextPath() + "/";
    final var manifest =
        new WebAppManifest(
            root,
            // The four-arg overload, with a default — NOT the throwing one. This method's contract
            // is "always 200, never a redirect": ArchitectureTest lists it PUBLIC_BY_DESIGN,
            // AnonymousSurfaceSweepMvcTest asserts the non-redirect, and it now sits behind a
            // blackbox module pinned to `valid_status_codes: [200]` with EdgePublicSurfaceNot200
            // watching. `getMessage(code, args, locale)` throws NoSuchMessageException, so dropping
            // or renaming one of these three keys in a later bundle edit would turn every anonymous
            // fetch into a 500 — including the browser's install-time read, which would then name
            // the home-screen icon after an error page. A fallback keeps the contract; the
            // key-parity test is what catches the missing key.
            messageSource.getMessage("pwa.name", null, DEFAULT_NAME, resolved),
            messageSource.getMessage("pwa.short_name", null, DEFAULT_SHORT_NAME, resolved),
            messageSource.getMessage("pwa.description", null, DEFAULT_DESCRIPTION, resolved),
            resolved.toLanguageTag(),
            "ltr",
            root,
            root,
            "standalone",
            BACKGROUND_COLOR,
            THEME_COLOR,
            List.of(new ManifestIcon(iconUrl(request), "512x512", "image/png", "any")));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(MANIFEST_JSON))
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
        .body(manifest);
  }

  /**
   * Maps a requested language subtag onto one of the shipped bundles.
   *
   * <p>Only the primary subtag is considered, so {@code de-CH} and {@code de_AT} both resolve to
   * German rather than falling through to the default. Matching is case-insensitive because the
   * value arrives from a URL.
   *
   * @param requested the raw {@code ?locale=…} value, or {@code null} when absent
   * @return a locale the message bundles actually cover; never {@code null}
   */
  private static @NotNull Locale resolveSupported(@Nullable String requested) {
    if (requested == null || requested.isBlank()) {
      return DEFAULT_LOCALE;
    }
    final String primary = requested.split("[-_]", 2)[0].toLowerCase(Locale.ROOT).trim();
    return SUPPORTED_LOCALES.getOrDefault(primary, DEFAULT_LOCALE);
  }

  /**
   * Resolves the home-screen icon to the content-hashed URL the resource chain serves.
   *
   * <p>Falls back to the plain, context-prefixed path when the provider cannot resolve it — which
   * happens only if the asset is missing, and a manifest naming a 404 icon is still better than a
   * {@code 500} on the endpoint that decides whether the app installs at all.
   *
   * @param request the current request, for its context path
   * @return an absolute path on this origin, fingerprinted where the resource chain allows it
   */
  private @NotNull String iconUrl(@NotNull HttpServletRequest request) {
    final String versioned = resourceUrlProvider.getForLookupPath(ICON_LOOKUP_PATH);
    return request.getContextPath() + (versioned != null ? versioned : ICON_LOOKUP_PATH);
  }

  /**
   * The manifest document, shaped so Jackson emits the member names the specification requires.
   *
   * <p>Every field is a decision and the non-obvious ones are noted on the parameter. What is
   * deliberately absent: {@code orientation}, because the web UI is responsive and locking it would
   * make a tablet worse; and any {@code shortcuts} or {@code share_target} entry, because both are
   * surfaces the specs do not describe yet.
   *
   * @param id stable identity of the installed app, so a later change to {@code start_url} updates
   *     the existing installation instead of creating a second one beside it.
   * @param name full name, shown in the install dialog.
   * @param shortName short name, shown under the home-screen icon where the full one is truncated.
   * @param description one sentence, shown by the richer install dialogs.
   * @param lang BCP 47 tag for the strings above, from {@link Locale#toLanguageTag()} on a locale
   *     the bundles actually cover — never a raw client value, or the tag and the copy disagree.
   * @param dir writing direction; both shipped locales are left-to-right.
   * @param startUrl what the icon opens: the application root answers with the landing page for a
   *     visitor and the dashboard for a member, so one entry point serves both states.
   * @param scope the application root — and since ADR-0166 that really is everything, sign-in
   *     included. A scope is one URL prefix and cannot span two origins, so while Keycloak answered
   *     on a host of its own both {@code /oauth2/authorization/keycloak} and the end-session
   *     redirect fell outside it; iOS sends an out-of-scope navigation to a Safari View Controller
   *     with its own storage, and keeps OAuth in the app by heuristic rather than by rule. Keycloak
   *     now answers at {@code /auth} on this origin, so both flows stay in the window without
   *     relying on that.
   * @param display {@code standalone} — the app window carries no browser address bar.
   * @param backgroundColor see {@link #BACKGROUND_COLOR}.
   * @param themeColor see {@link #THEME_COLOR}.
   * @param icons the app icon. One entry, the opaque 512 px tile REQ-UI-019 already designates for
   *     the home screen; the transparent favicon glyph is deliberately not offered here, because an
   *     installer that picked it would composite the mark onto an unknown plate.
   */
  public record WebAppManifest(
      @JsonProperty("id") String id,
      @JsonProperty("name") String name,
      @JsonProperty("short_name") String shortName,
      @JsonProperty("description") String description,
      @JsonProperty("lang") String lang,
      @JsonProperty("dir") String dir,
      @JsonProperty("start_url") String startUrl,
      @JsonProperty("scope") String scope,
      @JsonProperty("display") String display,
      @JsonProperty("background_color") String backgroundColor,
      @JsonProperty("theme_color") String themeColor,
      @JsonProperty("icons") List<ManifestIcon> icons) {}

  /**
   * One entry of the manifest's {@code icons} array.
   *
   * @param src absolute, content-hashed path on this origin, resolved through {@link
   *     ResourceUrlProvider}. {@code /logos/**} is already public, so the icon needs no further
   *     wiring — but it is also served {@code immutable} for a year, which is why the fingerprint
   *     matters: without it a replaced icon would never reach an installed home screen.
   * @param sizes pixel dimensions, {@code WxH}.
   * @param type media type, so a browser can skip a format it cannot decode without fetching it.
   * @param purpose {@code any} only. {@code maskable} is NOT claimed: Android crops a maskable icon
   *     to its own shape and only the inner ~40 % is guaranteed visible, so declaring it for an
   *     artwork that was not drawn with that safe zone would cut into the mark. A dedicated
   *     maskable asset is a request to the design system, not something to assert here.
   */
  public record ManifestIcon(
      @JsonProperty("src") String src,
      @JsonProperty("sizes") String sizes,
      @JsonProperty("type") String type,
      @JsonProperty("purpose") String purpose) {}
}
