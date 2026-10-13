/**
 * A suggestion: a plain string, or an object whose `label` is shown and `value` is filled in.
 *
 * @typedef {string | {label?: string, value?: string}} AutocompleteItem
 */

/**
 * Initialize a custom autocomplete on an input element.
 * @param {HTMLInputElement} inp - The input element
 * @param {AutocompleteItem[] | ((query: string) => Promise<AutocompleteItem[]>)} dataSource - Either an array of suggestions, or a function that takes a query and returns a Promise resolving to an array of suggestions.
 * @param {{debounceTime?: number, onSelect?: (item: AutocompleteItem) => void}} [options] - Configuration options
 */
export function krtAutocomplete(inp, dataSource, options = {}) {
    /** @type {number} */
    let currentFocus;
    /** @type {ReturnType<typeof setTimeout> | undefined} */
    let debounceTimer;

    const a = document.createElement('DIV');
    a.setAttribute('id', `${inp.id}-autocomplete-list`);
    a.setAttribute('class', 'autocomplete-items');

    const parent = /** @type {HTMLElement} */ (inp.parentNode);
    if (window.getComputedStyle(parent).position === 'static') {
        parent.style.position = 'relative';
    }
    parent.appendChild(a);

    inp.addEventListener('input', function () {
        const val = this.value;
        closeAllLists();
        if (!val) {
            return false;
        }
        currentFocus = -1;

        if (Array.isArray(dataSource)) {
            const results = dataSource.filter((item) => {
                const itemStr = typeof item === 'string' ? item : item.label || item.value || '';
                return itemStr.toUpperCase().includes(val.toUpperCase());
            });
            renderResults(results, val);
        } else if (typeof dataSource === 'function') {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(async () => {
                try {
                    const results = await dataSource(val);
                    renderResults(results, val);
                } catch (e) {
                    console.error('Autocomplete fetch error', e);
                }
            }, options.debounceTime || 300);
        }
    });

    inp.addEventListener('keydown', (e) => {
        const list = document.getElementById(`${inp.id}-autocomplete-list`);
        const x = list ? list.getElementsByTagName('div') : null;
        if (e.keyCode === 40) {
            currentFocus++;
            addActive(x);
        } else if (e.keyCode === 38) {
            currentFocus--;
            addActive(x);
        } else if (e.keyCode === 13) {
            if (currentFocus > -1) {
                if (x && x.length > 0) e.preventDefault();
                if (x && x[currentFocus]) x[currentFocus].click();
            }
        }
    });

    /**
     * Renders up to 15 suggestions with the typed text emphasised.
     *
     * @param {AutocompleteItem[] | null | undefined} arr the suggestions
     * @param {string} val the typed text
     */
    function renderResults(arr, val) {
        closeAllLists();
        if (!arr || !arr.length) return;

        arr.slice(0, 15).forEach((item) => {
            const itemStr = typeof item === 'string' ? item : item.label || item.value || '';
            const itemVal = typeof item === 'string' ? item : item.value || '';

            const b = document.createElement('DIV');
            const escapedVal = val.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            const regex = new RegExp(`(${escapedVal})`, 'gi');
            const parts = itemStr.split(regex);
            parts.forEach((part, i) => {
                if (i % 2 === 1) {
                    const strong = document.createElement('strong');
                    strong.textContent = part;
                    b.appendChild(strong);
                } else if (part) {
                    b.appendChild(document.createTextNode(part));
                }
            });
            const hidden = document.createElement('input');
            hidden.type = 'hidden';
            hidden.value = itemVal;
            b.appendChild(hidden);
            b.addEventListener('click', function () {
                inp.value = this.getElementsByTagName('input')[0].value;
                if (options.onSelect) {
                    options.onSelect(item);
                } else {
                    inp.dispatchEvent(new Event('change'));
                    inp.dispatchEvent(new Event('keyup'));
                }
                closeAllLists();
            });
            a.appendChild(b);
        });
    }

    /**
     * Highlights the suggestion at `currentFocus`, wrapping around at both ends.
     *
     * @param {HTMLCollectionOf<HTMLDivElement> | null} x the rendered suggestions
     */
    function addActive(x) {
        if (!x) return false;
        removeActive(x);
        if (currentFocus >= x.length) currentFocus = 0;
        if (currentFocus < 0) currentFocus = x.length - 1;
        x[currentFocus].classList.add('autocomplete-active');
    }

    /**
     * Removes the active highlight from every suggestion.
     *
     * @param {HTMLCollectionOf<HTMLDivElement>} x the rendered suggestions
     */
    function removeActive(x) {
        for (let i = 0; i < x.length; i++) {
            x[i].classList.remove('autocomplete-active');
        }
    }

    /**
     * Empties this input's suggestion list unless the element is the list or the input itself.
     *
     * @param {EventTarget | null} [elmnt] the clicked element to keep open
     */
    function closeAllLists(elmnt) {
        const x = document.getElementsByClassName('autocomplete-items');
        for (let i = 0; i < x.length; i++) {
            if (elmnt !== x[i] && elmnt !== inp && x[i].id === `${inp.id}-autocomplete-list`) {
                x[i].replaceChildren();
            }
        }
    }

    document.addEventListener('click', (e) => {
        closeAllLists(e.target);
    });
}
