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

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SystemSettingUpdateDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages.CompleteCatalog;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the admin system-settings page ({@code /admin/settings}).
 *
 * <p>The page edits four independent system settings, each carrying its own optimistic-lock
 * version: the yellow/red age thresholds for job-order aging colors, the refinery rounding mode and
 * the in-game banking transfer-fee rate applied to per-participant operation payouts. Every load
 * fetches each setting individually so a single backend hiccup degrades to a default value and a
 * logged warning rather than blanking the entire page; the persisted version fields are passed back
 * through the form so the next save can use them.
 *
 * <p>The transfer-fee rate is stored in the DB as a decimal fraction ({@code 0.005} = 0.5%) so the
 * consumer ({@code OperationService}) can multiply directly. For the form we convert it to a
 * human-friendly percentage ({@code 0.5}) on load and back to a fraction on save — admins shouldn't
 * have to count leading zeros.
 */
@Controller
@RequestMapping("/admin/settings")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminSettingsPageController {

  /** Decimal scale used when converting between DB fraction and form percentage. */
  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  /**
   * System-setting key holding the UUID of the designated intake Spezialkommando that anonymous /
   * guest Job-Order creations are routed to. Seeded empty by Flyway V128; an admin picks the SK
   * here.
   */
  private static final String INTAKE_SK_SETTING_KEY = "job_order.intake_special_command_id";

  /**
   * Display default for the transfer-fee rate (percent) used when the backend lookup fails. Kept in
   * sync with {@code OperationService.DEFAULT_TRANSFER_FEE_RATE} (0.005 = 0.5%) so the form never
   * renders blank.
   */
  private static final BigDecimal DEFAULT_TRANSFER_FEE_PERCENT = new BigDecimal("0.5");

  /** Response type for the Spezialkommando list backing the intake-SK dropdown. */
  private static final ParameterizedTypeReference<PageResponse<SpecialCommandDto>>
      SPECIAL_COMMAND_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  /** Response type for the active-squadron list backing the promotion-toggle section. */
  private static final ParameterizedTypeReference<PageResponse<SquadronDto>> SQUADRON_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;
  private final MessageSource messageSource;

  /**
   * Loads all admin-tunable system settings and exposes value+version pairs to the form template.
   * Missing settings fall back to documented defaults (30/90 days, rounding mode {@code UP}, 0.5%
   * transfer fee) so the page never renders an empty input. The transfer-fee rate is converted from
   * DB-side decimal fraction to display-side percent so the admin sees {@code 0.5} instead of
   * {@code 0.005}.
   *
   * @param model Thymeleaf model populated with the value+version pairs
   * @return the {@code admin-settings} view name
   */
  @GetMapping
  public String viewSettings(Model model) {
    int yellowDays = 30;
    int redDays = 90;
    Long yellowVersion = 0L;
    Long redVersion = 0L;
    String refineryRoundingMode = "UP";
    Long refineryRoundingVersion = 0L;

    try {
      SystemSettingDto yellowSetting =
          backendApiClient.get(
              "/api/v1/settings/job_order.age_yellow_days", SystemSettingDto.class);
      yellowDays = Integer.parseInt(yellowSetting.value());
      yellowVersion = yellowSetting.version();
    } catch (BackendServiceException e) {
      log.debug("Could not fetch yellow days setting", e);
    } catch (Exception e) {
      log.warn("Could not fetch yellow days setting", e);
    }

    try {
      SystemSettingDto redSetting =
          backendApiClient.get("/api/v1/settings/job_order.age_red_days", SystemSettingDto.class);
      redDays = Integer.parseInt(redSetting.value());
      redVersion = redSetting.version();
    } catch (BackendServiceException e) {
      log.debug("Could not fetch red days setting", e);
    } catch (Exception e) {
      log.warn("Could not fetch red days setting", e);
    }

    try {
      SystemSettingDto roundingSetting =
          backendApiClient.get("/api/v1/settings/refinery.rounding.mode", SystemSettingDto.class);
      refineryRoundingMode = roundingSetting.value();
      refineryRoundingVersion = roundingSetting.version();
    } catch (BackendServiceException e) {
      log.debug("Could not fetch refinery rounding mode setting", e);
    } catch (Exception e) {
      log.warn("Could not fetch refinery rounding mode setting", e);
    }

    BigDecimal transferFeePercent = DEFAULT_TRANSFER_FEE_PERCENT;
    Long transferFeeVersion = 0L;
    try {
      SystemSettingDto feeSetting =
          backendApiClient.get(
              "/api/v1/settings/operation.transfer_fee_rate", SystemSettingDto.class);
      // Convert DB fraction (e.g. "0.005") to display percent (e.g. "0.5"). Strip trailing
      // zeros so "0.50" doesn't render as "0.5000" in the input.
      transferFeePercent =
          new BigDecimal(feeSetting.value()).multiply(ONE_HUNDRED).stripTrailingZeros();
      if (transferFeePercent.scale() < 0) {
        transferFeePercent = transferFeePercent.setScale(0, RoundingMode.UNNECESSARY);
      }
      transferFeeVersion = feeSetting.version();
    } catch (BackendServiceException e) {
      log.debug("Could not fetch operation transfer fee rate setting", e);
    } catch (Exception e) {
      log.warn("Could not fetch operation transfer fee rate setting", e);
    }

    model.addAttribute("ageYellowDays", yellowDays);
    model.addAttribute("ageYellowVersion", yellowVersion);
    model.addAttribute("ageRedDays", redDays);
    model.addAttribute("ageRedVersion", redVersion);
    model.addAttribute("refineryRoundingMode", refineryRoundingMode);
    model.addAttribute("refineryRoundingVersion", refineryRoundingVersion);
    model.addAttribute("transferFeePercent", transferFeePercent.toPlainString());
    model.addAttribute("transferFeeVersion", transferFeeVersion);
    CompleteCatalog<SquadronDto> squadronCatalog = fetchSquadronsForPromotionToggle();
    model.addAttribute("squadrons", squadronCatalog.items());

    String intakeSpecialCommandId = "";
    Long intakeSpecialCommandVersion = 0L;
    try {
      SystemSettingDto intakeSetting =
          backendApiClient.get("/api/v1/settings/" + INTAKE_SK_SETTING_KEY, SystemSettingDto.class);
      intakeSpecialCommandId = intakeSetting.value() == null ? "" : intakeSetting.value();
      intakeSpecialCommandVersion = intakeSetting.version();
    } catch (BackendServiceException e) {
      log.debug("Could not fetch job-order intake special-command setting", e);
    } catch (Exception e) {
      log.warn("Could not fetch job-order intake special-command setting", e);
    }
    model.addAttribute("intakeSpecialCommandId", intakeSpecialCommandId);
    model.addAttribute("intakeSpecialCommandVersion", intakeSpecialCommandVersion);
    CompleteCatalog<SpecialCommandDto> specialCommandCatalog = fetchSpecialCommands();
    model.addAttribute("specialCommands", specialCommandCatalog.items());
    model.addAttribute(
        "catalogTruncated", squadronCatalog.truncated() || specialCommandCatalog.truncated());

    return "admin-settings";
  }

  /**
   * Loads <em>every</em> Spezialkommando (alphabetical, all pages — REQ-ADMIN-001, ADR-0102) for
   * the job-order intake-SK dropdown on the admin-settings page. A backend failure degrades to an
   * empty catalogue with a logged warning so the rest of the page still renders; a page walk that
   * hits its safety cap is flagged for the page-level warning banner (REQ-ADMIN-002).
   *
   * @return Spezialkommandos sorted by name plus the truncation flag, never {@code null}.
   */
  private CompleteCatalog<SpecialCommandDto> fetchSpecialCommands() {
    try {
      CompleteCatalog<SpecialCommandDto> catalog =
          CatalogPages.fetchAll(
              page ->
                  backendApiClient.get(
                      "/api/v1/special-commands?size=1000&sort=name,asc&page=" + page,
                      SPECIAL_COMMAND_PAGE_TYPE));
      List<SpecialCommandDto> sorted =
          catalog.items().stream()
              .sorted(
                  Comparator.comparing(
                      s -> s.name() == null ? "" : s.name(), String.CASE_INSENSITIVE_ORDER))
              .toList();
      return new CompleteCatalog<>(sorted, catalog.totalElements(), catalog.truncated());
    } catch (Exception e) {
      log.warn(
          "Could not fetch special commands for admin-settings intake picker: {}", e.getMessage());
      return CompleteCatalog.empty();
    }
  }

  /**
   * Loads <em>every</em> active squadron (alphabetical, all pages — REQ-ADMIN-001, ADR-0102) for
   * the "Beförderungssystem pro Staffel" toggle section on the admin-settings page. Inactive
   * (soft-deleted) squadrons are filtered out — the admin re-activates them through the existing
   * squadron CRUD before toggling features. A backend failure degrades to an empty catalogue with a
   * logged warning so the rest of the page still renders; a page walk that hits its safety cap is
   * flagged for the page-level warning banner (REQ-ADMIN-002).
   *
   * @return active squadrons sorted by name plus the truncation flag, never {@code null}.
   */
  private CompleteCatalog<SquadronDto> fetchSquadronsForPromotionToggle() {
    try {
      CompleteCatalog<SquadronDto> catalog =
          CatalogPages.fetchAll(
              page ->
                  backendApiClient.get(
                      "/api/v1/squadrons?size=1000&sort=name,asc&page=" + page,
                      SQUADRON_PAGE_TYPE));
      List<SquadronDto> sorted =
          catalog.items().stream()
              .sorted(
                  Comparator.comparing(
                      s -> s.name() == null ? "" : s.name(), String.CASE_INSENSITIVE_ORDER))
              .toList();
      return new CompleteCatalog<>(sorted, catalog.totalElements(), catalog.truncated());
    } catch (Exception e) {
      log.warn("Could not fetch squadrons for admin-settings promotion toggle: {}", e.getMessage());
      return CompleteCatalog.empty();
    }
  }

  /**
   * Persists the four settings in one form submit.
   *
   * <p>Validates the relationship invariants ({@code yellow < red}, both non-negative; transfer fee
   * in {@code [0, 100)} as percent) before issuing any PUT — a violation short-circuits with a
   * flash toast so the user sees the error immediately and no partial update reaches the backend.
   * Each setting is updated via its own PUT carrying the form-supplied version (optimistic
   * locking); a number-format error or any other failure surfaces as a localized toast. The
   * transfer-fee field is converted from the human percent input to the DB-side decimal fraction
   * before posting.
   *
   * @param ageYellowDaysStr yellow-aging threshold (parsed as int)
   * @param ageYellowVersion optimistic-lock version for the yellow setting
   * @param ageRedDaysStr red-aging threshold (parsed as int)
   * @param ageRedVersion optimistic-lock version for the red setting
   * @param refineryRoundingMode rounding mode (one of {@code UP}/{@code DOWN}/{@code HALF_UP}/…)
   * @param refineryRoundingVersion optimistic-lock version for the rounding setting
   * @param transferFeePercentStr in-game banking transfer fee as a percentage (e.g. {@code 0.5})
   * @param transferFeeVersion optimistic-lock version for the transfer-fee setting
   * @param intakeSpecialCommandId UUID of the job-order intake Spezialkommando; blank leaves the
   *     current value untouched (the value cannot be cleared back to blank via this form)
   * @param intakeSpecialCommandVersion optimistic-lock version for the intake-SK setting
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/settings}
   */
  @PostMapping
  public String updateSettings(
      @RequestParam("ageYellowDays") String ageYellowDaysStr,
      @RequestParam("ageYellowVersion") Long ageYellowVersion,
      @RequestParam("ageRedDays") String ageRedDaysStr,
      @RequestParam("ageRedVersion") Long ageRedVersion,
      @RequestParam("refineryRoundingMode") String refineryRoundingMode,
      @RequestParam("refineryRoundingVersion") Long refineryRoundingVersion,
      @RequestParam("transferFeePercent") String transferFeePercentStr,
      @RequestParam("transferFeeVersion") Long transferFeeVersion,
      @RequestParam(name = "intakeSpecialCommandId", required = false, defaultValue = "")
          String intakeSpecialCommandId,
      @RequestParam(name = "intakeSpecialCommandVersion", required = false, defaultValue = "0")
          Long intakeSpecialCommandVersion,
      RedirectAttributes redirectAttributes) {
    try {
      int yellowDays = Integer.parseInt(ageYellowDaysStr);
      int redDays = Integer.parseInt(ageRedDaysStr);

      if (yellowDays < 0 || redDays < 0 || yellowDays >= redDays) {
        redirectAttributes.addFlashAttribute("errorToast", "error.settings.invalid.values");
        return "redirect:/admin/settings";
      }

      BigDecimal transferFeePercent = new BigDecimal(transferFeePercentStr.trim());
      if (transferFeePercent.signum() < 0 || transferFeePercent.compareTo(ONE_HUNDRED) >= 0) {
        redirectAttributes.addFlashAttribute("errorToast", "error.settings.invalid.values");
        return "redirect:/admin/settings";
      }
      // Convert human-friendly percent to DB-side decimal fraction (0.5% -> 0.005).
      BigDecimal transferFeeRate = transferFeePercent.divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP);

      try {
        backendApiClient.put(
            "/api/v1/settings/job_order.age_yellow_days",
            new SystemSettingUpdateDto(String.valueOf(yellowDays), ageYellowVersion),
            SystemSettingDto.class);
        backendApiClient.put(
            "/api/v1/settings/job_order.age_red_days",
            new SystemSettingUpdateDto(String.valueOf(redDays), ageRedVersion),
            SystemSettingDto.class);
        backendApiClient.put(
            "/api/v1/settings/refinery.rounding.mode",
            new SystemSettingUpdateDto(refineryRoundingMode, refineryRoundingVersion),
            SystemSettingDto.class);
        backendApiClient.put(
            "/api/v1/settings/operation.transfer_fee_rate",
            new SystemSettingUpdateDto(
                transferFeeRate.stripTrailingZeros().toPlainString(), transferFeeVersion),
            SystemSettingDto.class);

        // Only persist the intake SK when an SK is actually selected. The backend setting is
        // @NotBlank, so a blank submit (no SK chosen yet) is treated as "leave unchanged" rather
        // than an attempt to clear it.
        if (intakeSpecialCommandId != null && !intakeSpecialCommandId.isBlank()) {
          backendApiClient.put(
              "/api/v1/settings/" + INTAKE_SK_SETTING_KEY,
              new SystemSettingUpdateDto(
                  intakeSpecialCommandId.trim(), intakeSpecialCommandVersion),
              SystemSettingDto.class);
        }
      } finally {
        // The job-order age thresholds are read via getCached on the orders pages, so evict in a
        // finally: even a partial save (an early PUT lands, a later one throws) still drops the
        // cache so the persisted value shows on the next render instead of waiting out the TTL.
        // Clearing the whole static cache is safe — entries just reload on the next read.
        backendApiClient.clearStaticDataCache();
      }

      redirectAttributes.addFlashAttribute("successToast", "success.settings.update");
    } catch (NumberFormatException e) {
      redirectAttributes.addFlashAttribute("errorToast", "error.settings.invalid.format");
    } catch (Exception e) {
      log.error("Failed to update settings", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.settings.update.failed");
    }
    return "redirect:/admin/settings";
  }

  /**
   * In-place (AJAX) twin of {@link #updateSettings} — routed here ahead of the classic handler by
   * the {@code X-Requested-With} header so the no-JS form keeps its redirect fallback. Applies the
   * same cross-field invariants and per-setting PUTs, but returns the bumped optimistic-lock
   * versions as JSON so the page can write them back into the hidden version inputs (the next save
   * would otherwise 409). Validation failures are returned as {@code application/problem+json} with
   * a localized {@code detail} so the shared {@code krtFetch} client toasts the exact reason; a
   * backend conflict on any setting is relayed with its {@code OPTIMISTIC_LOCK} code so the client
   * offers the reload-confirm rather than reloading.
   *
   * @param request the JSON-bound settings payload (values + per-setting versions)
   * @param locale the request locale used to resolve validation messages
   * @return {@code 200} with the fresh versions on success, {@code 422 problem+json} on a
   *     validation failure, the relayed backend status on conflict/failure, {@code 500} on an
   *     unexpected error
   */
  @ResponseBody
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateSettingsAjax(
      @RequestBody SettingsAjaxRequest request, Locale locale) {
    try {
      int yellowDays = Integer.parseInt(request.ageYellowDays());
      int redDays = Integer.parseInt(request.ageRedDays());
      if (yellowDays < 0 || redDays < 0 || yellowDays >= redDays) {
        return validationProblem("error.settings.invalid.values", locale);
      }

      BigDecimal transferFeePercent = new BigDecimal(request.transferFeePercent().trim());
      if (transferFeePercent.signum() < 0 || transferFeePercent.compareTo(ONE_HUNDRED) >= 0) {
        return validationProblem("error.settings.invalid.values", locale);
      }
      BigDecimal transferFeeRate = transferFeePercent.divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP);

      try {
        final SystemSettingDto yellow =
            backendApiClient.put(
                "/api/v1/settings/job_order.age_yellow_days",
                new SystemSettingUpdateDto(String.valueOf(yellowDays), request.ageYellowVersion()),
                SystemSettingDto.class);
        final SystemSettingDto red =
            backendApiClient.put(
                "/api/v1/settings/job_order.age_red_days",
                new SystemSettingUpdateDto(String.valueOf(redDays), request.ageRedVersion()),
                SystemSettingDto.class);
        final SystemSettingDto rounding =
            backendApiClient.put(
                "/api/v1/settings/refinery.rounding.mode",
                new SystemSettingUpdateDto(
                    request.refineryRoundingMode(), request.refineryRoundingVersion()),
                SystemSettingDto.class);
        final SystemSettingDto fee =
            backendApiClient.put(
                "/api/v1/settings/operation.transfer_fee_rate",
                new SystemSettingUpdateDto(
                    transferFeeRate.stripTrailingZeros().toPlainString(),
                    request.transferFeeVersion()),
                SystemSettingDto.class);

        Long intakeVersion =
            request.intakeSpecialCommandVersion() != null
                ? request.intakeSpecialCommandVersion()
                : 0L;
        String intakeId = request.intakeSpecialCommandId();
        if (intakeId != null && !intakeId.isBlank()) {
          SystemSettingDto intake =
              backendApiClient.put(
                  "/api/v1/settings/" + INTAKE_SK_SETTING_KEY,
                  new SystemSettingUpdateDto(intakeId.trim(), intakeVersion),
                  SystemSettingDto.class);
          intakeVersion = intake.version();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ageYellowVersion", yellow.version());
        result.put("ageRedVersion", red.version());
        result.put("refineryRoundingVersion", rounding.version());
        result.put("transferFeeVersion", fee.version());
        result.put("intakeSpecialCommandVersion", intakeVersion);
        result.put("transferFeePercent", transferFeePercent.stripTrailingZeros().toPlainString());
        return ResponseEntity.ok(result);
      } finally {
        // The job-order age thresholds are read via getCached on the orders pages, so evict in a
        // finally: even a partial save (an early PUT lands, a later one throws) still drops the
        // cache so the persisted value shows on the next render instead of waiting out the TTL.
        // Clearing the whole static cache is safe — entries just reload on the next read.
        backendApiClient.clearStaticDataCache();
      }
    } catch (NumberFormatException e) {
      return validationProblem("error.settings.invalid.format", locale);
    } catch (BackendServiceException e) {
      log.debug("Failed to update settings (ajax)", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to update settings (ajax)", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Builds a {@code 422 application/problem+json} response whose {@code detail} is the localized
   * message for {@code messageKey}, so the client toasts the exact validation reason without
   * client-side key mapping.
   *
   * @param messageKey the message bundle key to resolve
   * @param locale the request locale
   * @return a 422 problem+json {@link ResponseEntity}
   */
  private ResponseEntity<Object> validationProblem(String messageKey, Locale locale) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", 422);
    body.put("code", "VALIDATION_FAILED");
    body.put("detail", messageSource.getMessage(messageKey, null, messageKey, locale));
    return ResponseEntity.status(422).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  /**
   * JSON payload for the in-place settings save ({@link #updateSettingsAjax}). Mirrors the classic
   * form's request parameters; the day/percent fields stay strings so they are parsed and
   * range-validated server-side exactly as the redirect handler does.
   *
   * @param ageYellowDays yellow-aging threshold (parsed as int)
   * @param ageYellowVersion optimistic-lock version for the yellow setting
   * @param ageRedDays red-aging threshold (parsed as int)
   * @param ageRedVersion optimistic-lock version for the red setting
   * @param refineryRoundingMode rounding mode value ({@code UP} / {@code DOWN})
   * @param refineryRoundingVersion optimistic-lock version for the rounding setting
   * @param transferFeePercent in-game transfer fee as a percent string (e.g. {@code 0.5})
   * @param transferFeeVersion optimistic-lock version for the transfer-fee setting
   * @param intakeSpecialCommandId UUID string of the intake Spezialkommando, or blank to leave it
   *     unchanged
   * @param intakeSpecialCommandVersion optimistic-lock version for the intake-SK setting
   */
  public record SettingsAjaxRequest(
      String ageYellowDays,
      Long ageYellowVersion,
      String ageRedDays,
      Long ageRedVersion,
      String refineryRoundingMode,
      Long refineryRoundingVersion,
      String transferFeePercent,
      Long transferFeeVersion,
      String intakeSpecialCommandId,
      Long intakeSpecialCommandVersion) {}
}
