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
 * Serves {@code /manifest.webmanifest}, which lets a browser install the Basetool as a home-screen
 * web app (ADR-0164).
 *
 * <p>Always answers {@code 200} anonymously, as {@code application/manifest+json}, with localized
 * strings from {@link MessageSource} and a content-hashed icon URL from {@link
 * ResourceUrlProvider}. The locale comes from the {@code ?locale=} parameter, so the response is a
 * pure function of its URL. Compare {@link AssetLinksController}.
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
   * The locales the manifest can be rendered in, keyed by primary language subtag, so the strings
   * and the declared {@code lang} always agree.
   */
  private static final Map<String, Locale> SUPPORTED_LOCALES =
      Map.of("de", Locale.GERMAN, "en", Locale.ENGLISH);

  /**
   * Fallback name used only if a {@code pwa.*} key is missing from every bundle, so the endpoint
   * never fails.
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
  private static final String DEFAULT_DESCRIPTION =
      "Einsätze, Aufträge, Lager und Raffinerie der Organisation.";

  /**
   * Locale used when the request names none or an unsupported one; must match the default of the
   * application's locale resolver.
   */
  private static final Locale DEFAULT_LOCALE = Locale.GERMAN;

  /** Resolves the three user-visible strings for the requested locale. */
  private final MessageSource messageSource;

  /**
   * Resolves {@link #ICON_LOOKUP_PATH} to the content-hashed URL the resource chain serves; the
   * same instance Thymeleaf uses.
   */
  private final ResourceUrlProvider resourceUrlProvider;

  /**
   * Returns the web app manifest for the locale named in the query string.
   *
   * <p>The body depends only on the URL, so it is cacheable {@code public}. The content type is set
   * on the response rather than via {@code produces}, so every {@code Accept} header gets the
   * manifest.
   *
   * @param locale primary language subtag from {@code ?locale=…}, or {@code null}; anything not in
   *     {@link #SUPPORTED_LOCALES} falls back to {@link #DEFAULT_LOCALE}
   * @param request the current request, read for its context path
   * @return the manifest as {@code application/manifest+json}, always {@code 200} and never a
   *     redirect
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
   * Maps a requested language subtag onto one of the shipped bundles, comparing only the primary
   * subtag case-insensitively.
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
   * Resolves the home-screen icon to its content-hashed URL, falling back to the plain
   * context-prefixed path when the provider cannot resolve it.
   *
   * @param request the current request, for its context path
   * @return an absolute path on this origin, fingerprinted where the resource chain allows it
   */
  private @NotNull String iconUrl(@NotNull HttpServletRequest request) {
    final String versioned = resourceUrlProvider.getForLookupPath(ICON_LOOKUP_PATH);
    return request.getContextPath() + (versioned != null ? versioned : ICON_LOOKUP_PATH);
  }

  /**
   * The web app manifest document, with the member names the specification requires.
   *
   * @param id stable app identity, so a changed {@code start_url} updates the existing installation
   * @param name full name, shown in the install dialog.
   * @param shortName short name, shown under the home-screen icon.
   * @param description one sentence, shown by richer install dialogs.
   * @param lang BCP 47 tag of the strings, always a locale the bundles cover
   * @param dir writing direction; both shipped locales are left-to-right.
   * @param startUrl what the icon opens: the application root
   * @param scope the application root, which includes the same-origin sign-in flow
   * @param display {@code standalone}, without a browser address bar.
   * @param backgroundColor see {@link #BACKGROUND_COLOR}.
   * @param themeColor see {@link #THEME_COLOR}.
   * @param icons the single opaque 512 px app icon (REQ-UI-019)
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
   *     ResourceUrlProvider}
   * @param sizes pixel dimensions, {@code WxH}.
   * @param type media type, so a browser can skip a format it cannot decode.
   * @param purpose {@code any} only; the artwork is not drawn for {@code maskable}
   */
  public record ManifestIcon(
      @JsonProperty("src") String src,
      @JsonProperty("sizes") String sizes,
      @JsonProperty("type") String type,
      @JsonProperty("purpose") String purpose) {}
}
