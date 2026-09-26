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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfileBlueprintSharingForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfileDescriptionForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfilePayoutPreferenceForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfileRsiHandleForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.MapPayloadValues;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Controller of the user profile page ({@code /profile}) and its editable fields: description,
 * display name, default payout preference and blueprint sharing.
 *
 * <p>Renders from token claims overlaid with {@code /api/v1/users/me}, and still renders from the
 * token alone when the backend is unreachable.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class ProfileController {

  /** Shared response type for the raw {@code Map<String, Object>} backend payloads on this page. */
  private static final ParameterizedTypeReference<Map<String, Object>> STRING_OBJECT_MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;
  private final MessageSource messageSource;

  @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
  private String issuerUri;

  /**
   * Renders the profile page from token claims overlaid with the backend {@code /me} record, adding
   * a fresh {@link ProfileDescriptionForm} unless one is already present.
   *
   * @param model model populated with the profile data and the description form
   * @param principal authenticated OIDC user
   * @return the {@code profile} view name
   */
  @NotNull
  @GetMapping("/profile")
  public String profile(
      @NotNull Model model, @NotNull @AuthenticationPrincipal OidcUser principal) {
    model.addAttribute("username", principal.getPreferredUsername());
    model.addAttribute("email", principal.getEmail());

    model.addAttribute("rank", getSingleClaim(principal, "rank"));
    model.addAttribute("description", getSingleClaim(principal, "description"));
    model.addAttribute("displayName", getSingleClaim(principal, "displayName"));

    try {
      Map<String, Object> user = backendApiClient.get("/api/v1/users/me", STRING_OBJECT_MAP_TYPE);

      if (user != null) {
        if (user.get("rank") != null) {
          model.addAttribute("rank", user.get("rank"));
        }
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
          }
        }
        if (user.get("squadrons") instanceof java.util.List<?> squadrons) {
          model.addAttribute("profileSquadrons", squadrons);
        }
      }
    } catch (Exception ignored) {
    }

    PayoutPreference defaultPayoutPreference = PayoutPreference.PAYOUT;
    try {
      Map<String, Object> pref =
          backendApiClient.get("/api/v1/users/me/payout-preference", STRING_OBJECT_MAP_TYPE);
      if (pref != null && pref.get("defaultPayoutPreference") != null) {
        defaultPayoutPreference =
            PayoutPreference.valueOf(String.valueOf(pref.get("defaultPayoutPreference")));
      }
    } catch (Exception e) {
      log.debug(
          "Could not load the default payout preference; defaulting the selector to PAYOUT", e);
    }
    model.addAttribute("defaultPayoutPreference", defaultPayoutPreference);

    boolean shareBlueprintsGlobally = false;
    try {
      Map<String, Object> sharing =
          backendApiClient.get("/api/v1/users/me/blueprint-sharing", STRING_OBJECT_MAP_TYPE);
      if (sharing != null && sharing.get("shareBlueprintsGlobally") != null) {
        shareBlueprintsGlobally =
            Boolean.parseBoolean(String.valueOf(sharing.get("shareBlueprintsGlobally")));
      }
    } catch (Exception e) {
      log.debug(
          "Could not load the global blueprint-sharing flag; defaulting the toggle to off", e);
    }
    model.addAttribute("shareBlueprintsGlobally", shareBlueprintsGlobally);

    String rsiHandle = null;
    try {
      Map<String, Object> handle =
          backendApiClient.get("/api/v1/users/me/rsi-handle", STRING_OBJECT_MAP_TYPE);
      if (handle != null && handle.get("rsiHandle") != null) {
        rsiHandle = String.valueOf(handle.get("rsiHandle"));
      }
    } catch (Exception e) {
      log.debug("Could not load the RSI handle; the field starts empty", e);
    }

    model.addAttribute("keycloakAccountUrl", issuerUri + "/account");

    Map<String, Object> deletionRequest = null;
    boolean deletionRequestUnavailable = false;
    try {
      deletionRequest =
          backendApiClient.get("/api/v1/users/me/deletion-request", STRING_OBJECT_MAP_TYPE);
    } catch (Exception e) {
      log.debug("Could not load the member's deletion request; the card says so", e);
      deletionRequestUnavailable = true;
    }
    model.addAttribute("deletionRequest", deletionRequest);
    model.addAttribute("deletionRequestUnavailable", deletionRequestUnavailable);

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

    if (!model.containsAttribute("profileRsiHandleForm")) {
      Long version = (Long) model.getAttribute("version");
      model.addAttribute(
          "profileRsiHandleForm",
          new ProfileRsiHandleForm(rsiHandle, version != null ? version : 0L));
    }

    return "profile";
  }

  /**
   * Handles the description and display-name form post.
   *
   * <p>Validation errors re-render inline; a {@code concurrency-conflict} 409 shows the
   * optimistic-lock toast. {@code null} fields are sent as empty strings, which clears them.
   *
   * @param form validated form payload
   * @param bindingResult validation errors carrier
   * @param model model used when re-rendering inline
   * @param principal authenticated OIDC user
   * @param redirectAttributes flash attributes carrier for the result toast
   * @return the inline {@code profile} view on validation failure, otherwise a redirect to {@code
   *     /profile}
   */
  @NotNull
  @PostMapping("/profile/description")
  public String updateDescription(
      @Valid @ModelAttribute("profileDescriptionForm") ProfileDescriptionForm form,
      BindingResult bindingResult,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
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
   * AJAX variant of {@link #updateDescription} that answers with JSON (REQ-FE-001).
   *
   * <p>Returns the user row's new {@code version}, shared by all profile forms. A backend conflict
   * maps to an {@code OPTIMISTIC_LOCK} 409, validation errors to 400.
   *
   * @param form validated JSON payload
   * @param bindingResult validation errors of the JSON body
   * @param principal authenticated OIDC user
   * @return 200 with {@code {version, description, displayName}}, otherwise an error body with a
   *     localized {@code detail}
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
   * Handles the default-payout-preference form post; a {@code concurrency-conflict} 409 shows the
   * optimistic-lock toast.
   *
   * @param form validated selector payload
   * @param bindingResult validation errors carrier
   * @param model model used when re-rendering inline
   * @param principal authenticated OIDC user
   * @param redirectAttributes flash attributes carrier for the result toast
   * @return the inline {@code profile} view on validation failure, otherwise a redirect to {@code
   *     /profile}
   */
  @NotNull
  @PostMapping("/profile/payout-preference")
  public String updatePayoutPreference(
      @Valid @ModelAttribute("profilePayoutPreferenceForm") ProfilePayoutPreferenceForm form,
      BindingResult bindingResult,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
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
   * AJAX variant of {@link #updatePayoutPreference} that answers with JSON (REQ-FE-001).
   *
   * <p>Returns the user row's new {@code version}. A backend conflict maps to an {@code
   * OPTIMISTIC_LOCK} 409, validation errors to 400.
   *
   * @param form validated JSON payload
   * @param bindingResult validation errors of the JSON body
   * @param principal authenticated OIDC user
   * @return 200 with {@code {version, defaultPayoutPreference}}, otherwise an error body with a
   *     localized {@code detail}
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
   * Handles the global blueprint-sharing toggle form post; a {@code concurrency-conflict} 409 shows
   * the optimistic-lock toast.
   *
   * @param form validated toggle payload
   * @param bindingResult validation errors carrier
   * @param model model used when re-rendering inline
   * @param principal authenticated OIDC user
   * @param redirectAttributes flash attributes carrier for the result toast
   * @return the inline {@code profile} view on validation failure, otherwise a redirect to {@code
   *     /profile}
   */
  @NotNull
  @PostMapping("/profile/blueprint-sharing")
  public String updateBlueprintSharing(
      @Valid @ModelAttribute("profileBlueprintSharingForm") ProfileBlueprintSharingForm form,
      BindingResult bindingResult,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
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
   * AJAX variant of {@link #updateBlueprintSharing} that answers with JSON (REQ-FE-001).
   *
   * <p>Returns the user row's new {@code version}. A backend conflict maps to an {@code
   * OPTIMISTIC_LOCK} 409, validation errors to 400.
   *
   * @param form validated JSON payload
   * @param bindingResult validation errors of the JSON body
   * @param principal authenticated OIDC user
   * @return 200 with {@code {version, shareBlueprintsGlobally}}, otherwise an error body with a
   *     localized {@code detail}
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
   * Handles the RSI-handle form post without JavaScript (REQ-SEC-072); the page's script sends the
   * same form to {@link ProfileRsiHandleProxyController} instead.
   *
   * @param form validated handle payload
   * @param bindingResult validation errors carrier
   * @param model model used when re-rendering inline
   * @param principal authenticated OIDC user
   * @param redirectAttributes flash attributes carrier for the result toast
   * @return the inline {@code profile} view on validation failure, otherwise a redirect to {@code
   *     /profile}
   */
  @NotNull
  @PostMapping("/profile/rsi-handle")
  public String updateRsiHandle(
      @Valid @ModelAttribute("profileRsiHandleForm") ProfileRsiHandleForm form,
      BindingResult bindingResult,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      return profile(model, principal);
    }
    try {
      backendApiClient.put(
          "/api/v1/users/me/rsi-handle",
          Map.of(
              "rsiHandle",
              form.rsiHandle() == null ? "" : form.rsiHandle().trim(),
              "version",
              form.version() == null ? 0L : form.version()),
          Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("RSI handle update failed", e);
      if (e.getStatusCode() == 409 && "concurrency-conflict".equals(e.getProblemType())) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
      } else if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "profile.rsiHandle.taken");
      } else {
        redirectAttributes.addFlashAttribute("errorToast", "error.profile.update.failed");
      }
    } catch (Exception e) {
      log.error("RSI handle update failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.profile.update.failed");
    }
    return "redirect:/profile";
  }

  /**
   * Reads the user row's current {@code version} from {@code /api/v1/users/me} after a write.
   *
   * @param priorVersion the submitted version; may be {@code null}
   * @return the current version, or {@code priorVersion + 1} when the lookup fails
   */
  private Long refreshedUserVersion(Long priorVersion) {
    try {
      Map<String, Object> me = backendApiClient.get("/api/v1/users/me", STRING_OBJECT_MAP_TYPE);
      if (me != null && me.get("version") != null) {
        return MapPayloadValues.longOrZero(me.get("version"));
      }
    } catch (Exception ignored) {
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
  private String firstFieldError(@NotNull BindingResult bindingResult) {
    return bindingResult.getFieldErrors().stream()
        .map(org.springframework.validation.FieldError::getDefaultMessage)
        .filter(message -> message != null && !message.isBlank())
        .findFirst()
        .orElseGet(() -> msg("error.profile.update.failed"));
  }

  /**
   * Derives the 1–2 letter monogram of the profile's identity tile from the display name, else the
   * username.
   *
   * <p>Two or more tokens yield their first letters ({@code "John Doe" -> "JD"}), one token its
   * first two characters; the result is upper-cased.
   *
   * @param displayName the display name; may be {@code null} or blank
   * @param username the fallback username; may be {@code null}
   * @return the initials, 0–2 characters; empty when no source is set
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

  private Object getSingleClaim(@NotNull OidcUser principal, String claim) {
    Object value = principal.getAttribute(claim);
    if (value instanceof java.util.List<?> list && !list.isEmpty()) {
      return list.get(0);
    }
    return value;
  }
}
