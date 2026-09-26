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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
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
import org.jetbrains.annotations.NotNull;
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
 * Controller for the admin system-settings page ({@code /admin/settings}): job-order age
 * thresholds, refinery rounding mode and the in-game transfer-fee rate, each with its own
 * optimistic-lock version.
 *
 * <p>The transfer fee is stored as a fraction ({@code 0.005}) and shown as a percentage ({@code
 * 0.5}).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/settings")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminSettingsPageController {

  /** Decimal scale used when converting between DB fraction and form percentage. */
  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  /**
   * Display default for the transfer-fee rate (percent) used when the backend lookup fails. Kept in
   * sync with {@code OperationService.DEFAULT_TRANSFER_FEE_RATE} (0.005 = 0.5%) so the form never
   * renders blank.
   */
  private static final BigDecimal DEFAULT_TRANSFER_FEE_PERCENT = new BigDecimal("0.5");

  /** Response type for the active-squadron list backing the promotion-toggle section. */
  private static final ParameterizedTypeReference<PageResponse<SquadronDto>> SQUADRON_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;
  private final MessageSource messageSource;

  /**
   * Loads the system settings as value and version pairs for the form. A missing setting falls back
   * to its default (30/90 days, rounding {@code UP}, 0.5% transfer fee).
   *
   * @param model Thymeleaf model populated with the value and version pairs
   * @return the {@code admin-settings} view name
   */
  @NotNull
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

    model.addAttribute("catalogTruncated", squadronCatalog.truncated());

    return "admin-settings";
  }

  /**
   * Loads every active squadron, sorted by name, for the per-squadron promotion toggle
   * (REQ-ADMIN-001). A backend failure yields an empty catalogue.
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
   * Persists the four settings from one form submit, each through its own versioned PUT.
   *
   * <p>Cross-field rules ({@code yellow < red}, both non-negative, transfer fee in {@code [0, 100)}
   * percent) are checked before any PUT; a violation or failure surfaces as a toast.
   *
   * @param ageYellowDaysStr yellow-aging threshold in days
   * @param ageYellowVersion optimistic-lock version for the yellow setting
   * @param ageRedDaysStr red-aging threshold in days
   * @param ageRedVersion optimistic-lock version for the red setting
   * @param refineryRoundingMode rounding mode ({@code UP}, {@code DOWN}, {@code HALF_UP}, ...)
   * @param refineryRoundingVersion optimistic-lock version for the rounding setting
   * @param transferFeePercentStr in-game transfer fee in percent (e.g. {@code 0.5})
   * @param transferFeeVersion optimistic-lock version for the transfer-fee setting
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/settings}
   */
  @NotNull
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
      } finally {
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
   * AJAX twin of {@link #updateSettings}: applies the same rules and PUTs and returns the new
   * versions as JSON. Validation failures answer {@code 422 problem+json} with a localized {@code
   * detail}; a backend conflict is relayed with its {@code OPTIMISTIC_LOCK} code.
   *
   * @param request the settings values and per-setting versions
   * @param locale the request locale used to resolve validation messages
   * @return {@code 200} with the new versions, {@code 422} on a validation failure, the relayed
   *     backend status on a conflict or failure, {@code 500} on an unexpected error
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ageYellowVersion", yellow.version());
        result.put("ageRedVersion", red.version());
        result.put("refineryRoundingVersion", rounding.version());
        result.put("transferFeeVersion", fee.version());
        result.put("transferFeePercent", transferFeePercent.stripTrailingZeros().toPlainString());
        return ResponseEntity.ok(result);
      } finally {
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
   * JSON payload of {@link #updateSettingsAjax}; the numeric fields stay strings so they are parsed
   * and validated server-side like the form parameters.
   *
   * @param ageYellowDays yellow-aging threshold in days
   * @param ageYellowVersion optimistic-lock version for the yellow setting
   * @param ageRedDays red-aging threshold in days
   * @param ageRedVersion optimistic-lock version for the red setting
   * @param refineryRoundingMode rounding mode ({@code UP} / {@code DOWN})
   * @param refineryRoundingVersion optimistic-lock version for the rounding setting
   * @param transferFeePercent in-game transfer fee in percent (e.g. {@code 0.5})
   * @param transferFeeVersion optimistic-lock version for the transfer-fee setting
   */
  public record SettingsAjaxRequest(
      String ageYellowDays,
      Long ageYellowVersion,
      String ageRedDays,
      Long ageRedVersion,
      String refineryRoundingMode,
      Long refineryRoundingVersion,
      String transferFeePercent,
      Long transferFeeVersion) {}
}
