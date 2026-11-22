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

/** The attribute that marks an option standing only for an entry's current value. */
export const CURRENT_ONLY_ATTRIBUTE = 'data-current-only';

/**
 * Builds the option an edit dialog shows for an entry's current value when the select offers no
 * option for it, so saving keeps a catalogue value the pickers no longer offer (REQ-P4K-080).
 *
 * @param {Iterable<HTMLOptionElement>} options the options the select offers
 * @param {string} value the entry's current value, empty for none
 * @param {string} label the display name of that value
 * @returns {HTMLOptionElement | null} the marked option, or null when the value is empty or offered
 */
export function currentOnlyOption(options, value, label) {
    if (!value || Array.from(options).some((option) => option.value === value)) {
        return null;
    }
    const option = new Option(label || value, value);
    option.setAttribute(CURRENT_ONLY_ATTRIBUTE, 'true');
    return option;
}

/**
 * Puts an entry's current value into a select: drops the option an earlier entry left, adds one
 * for a value the select does not offer, and selects the value.
 *
 * @param {HTMLSelectElement} select the select
 * @param {string} value the entry's current value, empty for none
 * @param {string} label the display name of that value
 */
export function selectCurrentValue(select, value, label) {
    select
        .querySelectorAll(`option[${CURRENT_ONLY_ATTRIBUTE}]`)
        .forEach((option) => option.remove());
    const current = currentOnlyOption(select.options, value, label);
    if (current) {
        select.add(current, select.options.length > 0 ? 1 : undefined);
    }
    select.value = value;
}
