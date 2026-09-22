/* exported krtAutocomplete */
/**
 * Initialize a custom autocomplete on an input element.
 * @param {HTMLInputElement} inp - The input element
 * @param {Array|Function} dataSource - Either an array of strings, or a function that takes a query and returns a Promise resolving to an array of strings.
 * @param {Object} options - Configuration options
 */
function krtAutocomplete(inp, dataSource, options = {}) {
    let currentFocus;
    let debounceTimer;

    // Create results container
    const a = document.createElement('DIV');
    a.setAttribute('id', inp.id + '-autocomplete-list');
    a.setAttribute('class', 'autocomplete-items');

    // Ensure parent is positioned relatively
    const parent = inp.parentNode;
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
            // Local data
            const results = dataSource.filter((item) => {
                const itemStr = typeof item === 'string' ? item : item.label || item.value || '';
                return itemStr.toUpperCase().includes(val.toUpperCase());
            });
            renderResults(results, val);
        } else if (typeof dataSource === 'function') {
            // Remote data
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

    inp.addEventListener('keydown', function (e) {
        let x = document.getElementById(inp.id + '-autocomplete-list');
        if (x) x = x.getElementsByTagName('div');
        if (e.keyCode === 40) {
            // DOWN
            currentFocus++;
            addActive(x);
        } else if (e.keyCode === 38) {
            // UP
            currentFocus--;
            addActive(x);
        } else if (e.keyCode === 13) {
            // ENTER
            if (currentFocus > -1) {
                if (x && x.length > 0) e.preventDefault(); // Only prevent default if we have items
                if (x && x[currentFocus]) x[currentFocus].click();
            }
        }
    });

    function renderResults(arr, val) {
        closeAllLists();
        if (!arr || !arr.length) return;

        arr.slice(0, 15).forEach((item) => {
            const itemStr = typeof item === 'string' ? item : item.label || item.value || '';
            const itemVal = typeof item === 'string' ? item : item.value || '';

            const b = document.createElement('DIV');
            // Build the highlighted label using DOM nodes so itemStr is treated
            // as text, not HTML. Only the surrounding <strong> wrapper is markup.
            const escapedVal = val.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            const regex = new RegExp('(' + escapedVal + ')', 'gi');
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

    function addActive(x) {
        if (!x) return false;
        removeActive(x);
        if (currentFocus >= x.length) currentFocus = 0;
        if (currentFocus < 0) currentFocus = x.length - 1;
        x[currentFocus].classList.add('autocomplete-active');
    }

    function removeActive(x) {
        for (let i = 0; i < x.length; i++) {
            x[i].classList.remove('autocomplete-active');
        }
    }

    function closeAllLists(elmnt) {
        const x = document.getElementsByClassName('autocomplete-items');
        for (let i = 0; i < x.length; i++) {
            if (elmnt !== x[i] && elmnt !== inp && x[i].id === inp.id + '-autocomplete-list') {
                x[i].innerHTML = '';
            }
        }
    }

    document.addEventListener('click', function (e) {
        closeAllLists(e.target);
    });
}
