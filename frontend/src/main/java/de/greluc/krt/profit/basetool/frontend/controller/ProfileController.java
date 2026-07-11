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

import de.greluc.krt.profit.basetool.frontend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfileBlueprintSharingForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfileDescriptionForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfilePayoutPreferenceForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.support.MapPayloadValues;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the user profile page ({@code /profile}).
 *
 * <p>Renders the current user's profile data and exposes the editable fields (description, display
 * name, and the default payout preference — the latter persisted through its own lightweight
 * endpoint so the central {@code UserDto} contract stays untouched). The page layers two data
 * sources: the OIDC token claims (used as the immediate default) and the backend {@code
 * /api/v1/users/me} payload (used to overwrite the token values with the authoritative DB state,
 * including the optimistic-locking {@code version} needed for subsequent updates). When the backend
 * is unreachable, the token-only view still renders so the user always sees something.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ProfileController {

  /** Shared response type for the raw {@code Map<String, Object>} backend payloads on this page. */
  private static final ParameterizedTypeReference<Map<String, Object>> STRING_OBJECT_MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;
  private final MessageSource messageSource;
  private final FrontendAuthHelperService authHelper;

  @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
  private String issuerUri;

  /**
   * Renders the profile page. Unauthenticated users are redirected to the home page. For
   * authenticated users the controller seeds the model from token claims, then overlays the backend
   * {@code /me} record where available and parses the {@code joinDate} into a {@code LocalDate}
   * plus the derived months-in-squadron counter. A fresh {@link ProfileDescriptionForm} is added to
   * the model unless one is already there (preserves user input across a failed submit).
   *
   * @param model Thymeleaf model populated with the layered profile data and the description form
   * @param principal authenticated OIDC user
   * @return the {@code profile} view name, or {@code redirect:/} for guests
   */
  @GetMapping("/profile")
  public String profile(Model model, @AuthenticationPrincipal OidcUser principal) {
    if (authHelper.isAnonymous()) {
      return "redirect:/";
    }

    model.addAttribute("username", principal.getPreferredUsername());
    model.addAttribute("email", principal.getEmail());

    // Default from Token
    model.addAttribute("rank", getSingleClaim(principal, "rank"));
    model.addAttribute("description", getSingleClaim(principal, "description"));
    model.addAttribute("displayName", getSingleClaim(principal, "displayName"));

    // Fetch from Backend to get latest DB state
    try {
      Map<String, Object> user = backendApiClient.get("/api/v1/users/me", STRING_OBJECT_MAP_TYPE);

      if (user != null) {
        if (user.get("rank") != null) {
          model.addAttribute("rank", user.get("rank"));
        }
        // Always overwrite description from DB, even if null (to allow clearing)
        // But if DB is null and Token has it? Prefer DB (it might have been deleted locally).
        // Actually, if DB has null, we might want to show empty.
        if (user.containsKey("description")) {
          model.addAttribute("description", user.get("description"));
        }
        if (user.containsKey("displayName")) {
          model.addAttribute("displayName", user.get("displayName"));
        }
        if (user.containsKey("version")) {
          model.addAttribute("version", MapPayloadValues.longOrZero(user.get("version")));
        }
        if (user.containsKey("joinDate") && user.get("joinDate") != null) {
          try {
            LocalDate joinDate = LocalDate.parse(String.valueOf(user.get("joinDate")));
            model.addAttribute("joinDate", joinDate);
            model.addAttribute(
                "monthsInSquadron", ChronoUnit.MONTHS.between(joinDate, LocalDate.now()));
          } catch (Exception ignored) {
            // joinDate missing or malformed — leave attribute unset
          }
        }
        // REQ-ORG-017: surface the user's FULL Staffel membership set (up to two) so the identity
        // band shows every Staffel affiliation, not just the single active-context pin. Each
        // element
        // is the serialised SquadronReferenceDto ({name, shorthand}).
        if (user.get("squadrons") instanceof java.util.List<?> squadrons) {
          model.addAttribute("profileSquadrons", squadrons);
        }
      }
    } catch (Exception e) {
      // Backend unavailable? Keep Token data.
    }

    // Default payout preference, fetched from its own lightweight endpoint so the central UserDto
    // contract stays untouched. Resilient: a backend hiccup or an unexpected value leaves the
    // selector at PAYOUT rather than failing the whole page.
    PayoutPreference defaultPayoutPreference = PayoutPreference.PAYOUT;
    try {
      Map<String, Object> pref =
          backendApiClient.get("/api/v1/users/me/payout-preference", STRING_OBJECT_MAP_TYPE);
      if (pref != null && pref.get("defaultPayoutPreference") != null) {
        defaultPayoutPreference =
            PayoutPreference.valueOf(String.valueOf(pref.get("defaultPayoutPreference")));
      }
    } catch (Exception e) {
      // Backend unavailable or unrecognised value — keep the PAYOUT default. Logged at debug
      // (not error) because this is an optional sub-fetch on a page that still renders fine; a
      // persistently failing endpoint then leaves a breadcrumb instead of silently hiding the
      // user's saved preference. No PII is logged.
      log.debug(
          "Could not load the default payout preference; defaulting the selector to PAYOUT", e);
    }
    model.addAttribute("defaultPayoutPreference", defaultPayoutPreference);

    // Global blueprint-sharing opt-in, fetched from its own lightweight endpoint (same isolation
    // from the central UserDto as the payout preference). Resilient: a backend hiccup leaves the
    // toggle off rather than failing the whole page.
    boolean shareBlueprintsGlobally = false;
    try {
      Map<String, Object> sharing =
          backendApiClient.get("/api/v1/users/me/blueprint-sharing", STRING_OBJECT_MAP_TYPE);
      if (sharing != null && sharing.get("shareBlueprintsGlobally") != null) {
        shareBlueprintsGlobally =
            Boolean.parseBoolean(String.valueOf(sharing.get("shareBlueprintsGlobally")));
      }
    } catch (Exception e) {
      // Backend unavailable — keep the off default. Logged at debug (not error) because this is an
      // optional sub-fetch on a page that still renders fine. No PII is logged.
      log.debug(
          "Could not load the global blueprint-sharing flag; defaulting the toggle to off", e);
    }
    model.addAttribute("shareBlueprintsGlobally", shareBlueprintsGlobally);

    model.addAttribute("keycloakAccountUrl", issuerUri + "/account");

    // Identity-tile initials for the read-only identity block (Variante A profile redesign).
    // Derived from the already-resolved display name (token, then DB overlay) with a username
    // fallback — no new avatar/upload feature, just the squared monogram the design calls for.
    model.addAttribute(
        "initials",
        computeInitials(
            (String) model.getAttribute("displayName"), (String) model.getAttribute("username")));

    if (!model.containsAttribute("profileDescriptionForm")) {
      String currentDesc = (String) model.getAttribute("description");
      String currentDisplayName = (String) model.getAttribute("displayName");
      Long version = (Long) model.getAttribute("version");
      model.addAttribute(
          "profileDescriptionForm",
          new ProfileDescriptionForm(
              currentDesc, currentDisplayName, version != null ? version : 0L));
    }

    if (!model.containsAttribute("profilePayoutPreferenceForm")) {
      Long version = (Long) model.getAttribute("version");
      model.addAttribute(
          "profilePayoutPreferenceForm",
          new ProfilePayoutPreferenceForm(defaultPayoutPreference, version != null ? version : 0L));
    }

    if (!model.containsAttribute("profileBlueprintSharingForm")) {
      Long version = (Long) model.getAttribute("version");
      model.addAttribute(
          "profileBlueprintSharingForm",
          new ProfileBlueprintSharingForm(shareBlueprintsGlobally, version != null ? version : 0L));
    }

    return "profile";
  }

  /**
   * Handles the description + display-name update form post.
   *
   * <p>Validation errors render the profile view inline (no redirect) so the {@link BindingResult}
   * stays request-scoped and never serializes through a Redis FlashMap (see {@code
   * RedisSessionConfig}). A 409 with problem type {@code concurrency-conflict} surfaces as a
   * dedicated optimistic-lock toast so the user knows to refresh and retry; any other failure lands
   * as a generic update-failed toast. {@code null} form fields are sent as the empty string — the
   * backend's {@link de.greluc.krt.profit.basetool.backend.config.NormalizedStringDeserializer}
   * maps that back to a blank, which is the intended "clear this field" semantics.
   *
   * @param form validated form payload
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used when re-rendering inline
   * @param principal authenticated OIDC user
   * @param redirectAttributes flash attributes carrier for the result toast
   * @return inline {@code profile} view on validation failure, otherwise redirect to {@code
   *     /profile}
   */
  @PostMapping("/profile/description")
  public String updateDescription(
      @Valid @ModelAttribute("profileDescriptionForm") ProfileDescriptionForm form,
      BindingResult bindingResult,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      // Render the profile view directly; the BindingResult stays request-scoped so it
      // never goes through a Redis-serialised FlashMap (see RedisSessionConfig).
      return profile(model, principal);
    }
    try {
      backendApiClient.put(
          "/api/v1/users/me/description",
          Map.of(
              "description", form.description() == null ? "" : form.description(),
              "displayName", form.displayName() == null ? "" : form.displayName(),
              "version", form.version()),
          Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Update failed", e);
      if (e.getStatusCode() == 409 && "concurrency-conflict".equals(e.getProblemType())) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
      } else {
        redirectAttributes.addFlashAttribute("errorToast", "error.profile.update.failed");
      }
      return "redirect:/profile";
    } catch (Exception e) {
      log.error("Update failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.profile.update.failed");
      return "redirect:/profile";
    }
    return "redirect:/profile";
  }

  /**
   * AJAX variant of {@link #updateDescription}: performs the same update but answers with JSON
   * instead of a redirect, so the profile page saves in place (epic #571, REQ-FE-001). It is
   * selected over the form handler only for the {@code krtFetch.write} call, which sends {@code
   * X-Requested-With: XMLHttpRequest} with a JSON body; a script-disabled browser still posts the
   * HTML form and lands on {@link #updateDescription}, so the redirect path stays the no-JS
   * fallback.
   *
   * <p>On success the user row's bumped {@code version} is read back from {@code /me} and returned
   * so the client can write it into every {@code version} field on the page — the description form
   * and the payout-preference form share one row version, so without this the next save would 409.
   * A backend 409 with problem type {@code concurrency-conflict} is mapped to an {@code
   * OPTIMISTIC_LOCK}-coded 409 so {@code krtFetch} shows the reload-confirm; validation errors map
   * to 400 and any other failure to a 5xx, each carrying a localized {@code detail}.
   *
   * @param form validated JSON payload (description, displayName, version)
   * @param bindingResult validation-errors carrier for the bound JSON body
   * @param principal authenticated OIDC user
   * @return 200 with {@code {version, description, displayName}} on success; otherwise a 4xx/5xx
   *     body carrying a localized {@code detail} (plus a {@code code} for the optimistic-lock case)
   */
  @PostMapping(
      value = "/profile/description",
      headers = "X-Requested-With=XMLHttpRequest",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> updateDescriptionAjax(
      @Valid @RequestBody ProfileDescriptionForm form,
      BindingResult bindingResult,
      @AuthenticationPrincipal OidcUser principal) {
    if (authHelper.isAnonymous()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("detail", msg("error.profile.update.failed")));
    }
    if (bindingResult.hasErrors()) {
      return ResponseEntity.badRequest().body(Map.of("detail", firstFieldError(bindingResult)));
    }
    try {
      backendApiClient.put(
          "/api/v1/users/me/description",
          Map.of(
              "description", form.description() == null ? "" : form.description(),
              "displayName", form.displayName() == null ? "" : form.displayName(),
              "version", form.version() == null ? 0L : form.version()),
          Void.class);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("AJAX profile description update failed", e);
      if (e.getStatusCode() == 409 && "concurrency-conflict".equals(e.getProblemType())) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("code", "OPTIMISTIC_LOCK", "detail", msg("error.concurrency.conflict")));
      }
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(Map.of("detail", msg("error.profile.update.failed")));
    } catch (Exception e) {
      log.error("AJAX profile description update failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("detail", msg("error.profile.update.failed")));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", refreshedUserVersion(form.version()));
    body.put("description", form.description() == null ? "" : form.description());
    body.put("displayName", form.displayName() == null ? "" : form.displayName());
    return ResponseEntity.ok(body);
  }

  /**
   * Handles the default-payout-preference selector post. Kept as a separate form (and backend
   * endpoint) from the description update so the central {@code UserDto} contract stays untouched;
   * both forms echo the same user-row {@code version}, so the post-redirect-GET refresh after
   * either save re-renders the other with the bumped version (no stale-version 409 on the next
   * click). A 409 with problem type {@code concurrency-conflict} surfaces as the optimistic-lock
   * toast; any other failure as the generic update-failed toast.
   *
   * @param form validated selector payload (preference + version)
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used when re-rendering inline
   * @param principal authenticated OIDC user
   * @param redirectAttributes flash attributes carrier for the result toast
   * @return inline {@code profile} view on validation failure, otherwise redirect to {@code
   *     /profile}
   */
  @PostMapping("/profile/payout-preference")
  public String updatePayoutPreference(
      @Valid @ModelAttribute("profilePayoutPreferenceForm") ProfilePayoutPreferenceForm form,
      BindingResult bindingResult,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      // Render inline so the BindingResult stays request-scoped (never a Redis FlashMap).
      return profile(model, principal);
    }
    try {
      backendApiClient.put(
          "/api/v1/users/me/payout-preference",
          Map.of(
              "preference",
              form.defaultPayoutPreference() == null
                  ? PayoutPreference.PAYOUT.name()
                  : form.defaultPayoutPreference().name(),
              "version",
              form.version()),
          Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Update failed", e);
      if (e.getStatusCode() == 409 && "concurrency-conflict".equals(e.getProblemType())) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
      } else {
        redirectAttributes.addFlashAttribute("errorToast", "error.profile.update.failed");
      }
      return "redirect:/profile";
    } catch (Exception e) {
      log.error("Update failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.profile.update.failed");
      return "redirect:/profile";
    }
    return "redirect:/profile";
  }

  /**
   * AJAX variant of {@link #updatePayoutPreference}: performs the same update but answers with JSON
   * instead of a redirect, so the profile page saves the payout preference in place (epic #571,
   * REQ-FE-001). Selected over the form handler only for the {@code krtFetch.write} call (which
   * sends {@code X-Requested-With: XMLHttpRequest} with a JSON body); a script-disabled browser
   * still posts the HTML form and lands on {@link #updatePayoutPreference}, so the redirect path
   * stays the no-JS fallback.
   *
   * <p>On success the user row's bumped {@code version} is read back from {@code /me} and returned
   * so the client can write it into every {@code version} field on the page — the description form
   * and the payout-preference form share one row version, so without this the next save would 409.
   * A backend 409 with problem type {@code concurrency-conflict} maps to an {@code OPTIMISTIC_LOCK}
   * 409 so {@code krtFetch} shows the reload-confirm; validation errors map to 400.
   *
   * @param form validated JSON payload (defaultPayoutPreference, version)
   * @param bindingResult validation-errors carrier for the bound JSON body
   * @param principal authenticated OIDC user
   * @return 200 with {@code {version, defaultPayoutPreference}} on success; otherwise a 4xx/5xx
   *     body carrying a localized {@code detail} (plus a {@code code} for the optimistic-lock case)
   */
  @PostMapping(
      value = "/profile/payout-preference",
      headers = "X-Requested-With=XMLHttpRequest",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> updatePayoutPreferenceAjax(
      @Valid @RequestBody ProfilePayoutPreferenceForm form,
      BindingResult bindingResult,
      @AuthenticationPrincipal OidcUser principal) {
    if (authHelper.isAnonymous()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("detail", msg("error.profile.update.failed")));
    }
    if (bindingResult.hasErrors()) {
      return ResponseEntity.badRequest().body(Map.of("detail", firstFieldError(bindingResult)));
    }
    String preference =
        form.defaultPayoutPreference() == null
            ? PayoutPreference.PAYOUT.name()
            : form.defaultPayoutPreference().name();
    try {
      backendApiClient.put(
          "/api/v1/users/me/payout-preference",
          Map.of("preference", preference, "version", form.version() == null ? 0L : form.version()),
          Void.class);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("AJAX payout-preference update failed", e);
      if (e.getStatusCode() == 409 && "concurrency-conflict".equals(e.getProblemType())) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("code", "OPTIMISTIC_LOCK", "detail", msg("error.concurrency.conflict")));
      }
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(Map.of("detail", msg("error.profile.update.failed")));
    } catch (Exception e) {
      log.error("AJAX payout-preference update failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("detail", msg("error.profile.update.failed")));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", refreshedUserVersion(form.version()));
    body.put("defaultPayoutPreference", preference);
    return ResponseEntity.ok(body);
  }

  /**
   * Handles the global blueprint-sharing toggle post (no-JS fallback). Kept as a separate form (and
   * backend endpoint) from the description update so the central {@code UserDto} contract stays
   * untouched; all profile forms echo the same user-row {@code version}, so the post-redirect-GET
   * refresh re-renders the others with the bumped version. A 409 with problem type {@code
   * concurrency-conflict} surfaces as the optimistic-lock toast; any other failure as the generic
   * update-failed toast.
   *
   * @param form validated toggle payload (flag + version)
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used when re-rendering inline
   * @param principal authenticated OIDC user
   * @param redirectAttributes flash attributes carrier for the result toast
   * @return inline {@code profile} view on validation failure, otherwise redirect to {@code
   *     /profile}
   */
  @PostMapping("/profile/blueprint-sharing")
  public String updateBlueprintSharing(
      @Valid @ModelAttribute("profileBlueprintSharingForm") ProfileBlueprintSharingForm form,
      BindingResult bindingResult,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      // Render inline so the BindingResult stays request-scoped (never a Redis FlashMap).
      return profile(model, principal);
    }
    try {
      backendApiClient.put(
          "/api/v1/users/me/blueprint-sharing",
          Map.of(
              "shareBlueprintsGlobally",
              form.shareBlueprintsGlobally(),
              "version",
              form.version() == null ? 0L : form.version()),
          Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Update failed", e);
      if (e.getStatusCode() == 409 && "concurrency-conflict".equals(e.getProblemType())) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
      } else {
        redirectAttributes.addFlashAttribute("errorToast", "error.profile.update.failed");
      }
      return "redirect:/profile";
    } catch (Exception e) {
      log.error("Update failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.profile.update.failed");
      return "redirect:/profile";
    }
    return "redirect:/profile";
  }

  /**
   * AJAX variant of {@link #updateBlueprintSharing}: same update, JSON response, in-place save
   * (epic #571, REQ-FE-001). Selected over the form handler only for the {@code krtFetch.write}
   * call (which sends {@code X-Requested-With: XMLHttpRequest} with a JSON body); a script-disabled
   * browser still posts the HTML form and lands on {@link #updateBlueprintSharing}.
   *
   * <p>On success the user row's bumped {@code version} is read back from {@code /me} and returned
   * so the client can sync it into every {@code version} field on the page (all profile forms share
   * one row version). A backend 409 with problem type {@code concurrency-conflict} maps to an
   * {@code OPTIMISTIC_LOCK} 409 so {@code krtFetch} shows the reload-confirm; validation errors map
   * to 400.
   *
   * @param form validated JSON payload (shareBlueprintsGlobally, version)
   * @param bindingResult validation-errors carrier for the bound JSON body
   * @param principal authenticated OIDC user
   * @return 200 with {@code {version, shareBlueprintsGlobally}} on success; otherwise a 4xx/5xx
   *     body carrying a localized {@code detail} (plus a {@code code} for the optimistic-lock case)
   */
  @PostMapping(
      value = "/profile/blueprint-sharing",
      headers = "X-Requested-With=XMLHttpRequest",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> updateBlueprintSharingAjax(
      @Valid @RequestBody ProfileBlueprintSharingForm form,
      BindingResult bindingResult,
      @AuthenticationPrincipal OidcUser principal) {
    if (authHelper.isAnonymous()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("detail", msg("error.profile.update.failed")));
    }
    if (bindingResult.hasErrors()) {
      return ResponseEntity.badRequest().body(Map.of("detail", firstFieldError(bindingResult)));
    }
    try {
      backendApiClient.put(
          "/api/v1/users/me/blueprint-sharing",
          Map.of(
              "shareBlueprintsGlobally",
              form.shareBlueprintsGlobally(),
              "version",
              form.version() == null ? 0L : form.version()),
          Void.class);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("AJAX blueprint-sharing update failed", e);
      if (e.getStatusCode() == 409 && "concurrency-conflict".equals(e.getProblemType())) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("code", "OPTIMISTIC_LOCK", "detail", msg("error.concurrency.conflict")));
      }
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(Map.of("detail", msg("error.profile.update.failed")));
    } catch (Exception e) {
      log.error("AJAX blueprint-sharing update failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("detail", msg("error.profile.update.failed")));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", refreshedUserVersion(form.version()));
    body.put("shareBlueprintsGlobally", form.shareBlueprintsGlobally());
    return ResponseEntity.ok(body);
  }

  /**
   * Reads the user row's current {@code version} back from {@code /api/v1/users/me} after a write
   * so the client can sync the freshly bumped value into the page's {@code version} fields. Falls
   * back to {@code priorVersion + 1} when the re-fetch is unavailable — the description update
   * bumps the version by exactly one, so this still prevents a spurious 409 on the next save if the
   * backend round-trip hiccups.
   *
   * @param priorVersion the version the client submitted (may be {@code null})
   * @return the current user-row version, or a best-effort {@code priorVersion + 1}
   */
  private Long refreshedUserVersion(Long priorVersion) {
    try {
      Map<String, Object> me = backendApiClient.get("/api/v1/users/me", STRING_OBJECT_MAP_TYPE);
      if (me != null && me.get("version") != null) {
        return MapPayloadValues.longOrZero(me.get("version"));
      }
    } catch (Exception ignored) {
      // Re-fetch failed — fall through to the best-effort increment below.
    }
    return (priorVersion == null ? 0L : priorVersion) + 1;
  }

  /**
   * Resolves a message-bundle key against the current request locale, returning the key itself when
   * unmapped so a missing translation degrades visibly rather than throwing.
   *
   * @param key the {@code messages.properties} key
   * @return the localized message, or {@code key} if no translation exists
   */
  private String msg(String key) {
    return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
  }

  /**
   * Returns the first field error's localized default message for a failed JSON bind, or the
   * generic update-failed message when none carries text.
   *
   * @param bindingResult the validation result carrying at least one error
   * @return a localized, user-presentable validation message
   */
  private String firstFieldError(BindingResult bindingResult) {
    return bindingResult.getFieldErrors().stream()
        .map(org.springframework.validation.FieldError::getDefaultMessage)
        .filter(message -> message != null && !message.isBlank())
        .findFirst()
        .orElseGet(() -> msg("error.profile.update.failed"));
  }

  /**
   * Derives the 1–2 letter identity-tile monogram shown in the profile's identity block. The source
   * is the user's display name, falling back to the username when no display name is set. A name
   * with two or more whitespace-separated tokens yields the first letter of its first two tokens
   * ({@code "John Doe" -> "JD"}); a single token yields its first two characters ({@code "Valk" ->
   * "VA"}). The result is upper-cased via {@link Locale#ROOT}; an absent or blank source yields the
   * empty string so the tile renders empty rather than throwing.
   *
   * @param displayName the user's chosen display name; may be {@code null} or blank
   * @param username the Keycloak username used as the fallback source; may be {@code null}
   * @return the upper-cased initials (0–2 characters); never {@code null}
   */
  private static String computeInitials(String displayName, String username) {
    String base =
        displayName != null && !displayName.isBlank()
            ? displayName.trim()
            : (username != null ? username.trim() : "");
    if (base.isEmpty()) {
      return "";
    }
    String[] tokens = base.split("\\s+");
    String initials =
        tokens.length >= 2
            ? String.valueOf(tokens[0].charAt(0)) + tokens[1].charAt(0)
            : base.substring(0, Math.min(2, base.length()));
    return initials.toUpperCase(Locale.ROOT);
  }

  private Object getSingleClaim(OidcUser principal, String claim) {
    Object value = principal.getAttribute(claim);
    if (value instanceof java.util.List<?> list && !list.isEmpty()) {
      return list.get(0);
    }
    return value;
  }
}
