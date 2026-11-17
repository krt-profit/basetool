(function () {
    'use strict';

    const styles = getComputedStyle(document.documentElement);

    /**
     * Reads one design token from the stylesheet's custom properties.
     *
     * @param {string} name the custom property, including its leading dashes
     * @returns {string} the token's value, trimmed
     */
    function token(name) {
        return styles.getPropertyValue(name).trim();
    }

    /**
     * Builds the Redoc theme from the DAS KARTELL tokens the page's stylesheet defines.
     *
     * @returns {object} a Redoc `theme` option
     */
    function krtTheme() {
        const font = token('--font-body');
        const primary = token('--color-primary');
        const text = token('--color-gray-1');
        const muted = token('--color-gray-2-text');
        const surface = token('--color-bg-dark-gray');
        const input = token('--color-surface-input');
        const hairline = token('--color-gray-3');
        return {
            spacing: { unit: 5, sectionHorizontal: 40, sectionVertical: 40 },
            breakpoints: { small: '50rem', medium: '75rem', large: '105rem' },
            colors: {
                tonalOffset: 0.2,
                primary: {
                    main: primary,
                    light: token('--color-accent-light'),
                    dark: token('--color-accent-dark'),
                    contrastText: token('--color-bg-black'),
                },
                success: { main: token('--color-success-text') },
                warning: { main: token('--color-warning') },
                error: { main: token('--color-danger-text') },
                gray: { 50: input, 100: surface },
                text: { primary: text, secondary: muted },
                border: { dark: hairline, light: hairline },
                responses: {
                    success: {
                        color: token('--color-success-text'),
                        backgroundColor: 'rgba(35, 158, 51, 0.12)',
                        tabTextColor: token('--color-success-text'),
                    },
                    error: {
                        color: token('--color-danger-text'),
                        backgroundColor: 'rgba(163, 0, 10, 0.18)',
                        tabTextColor: token('--color-danger-text'),
                    },
                    redirect: {
                        color: token('--color-warning'),
                        backgroundColor: 'rgba(255, 210, 63, 0.12)',
                        tabTextColor: token('--color-warning'),
                    },
                    info: {
                        color: token('--color-info-text'),
                        backgroundColor: 'rgba(53, 93, 220, 0.15)',
                        tabTextColor: token('--color-info-text'),
                    },
                },
                http: {
                    get: token('--color-info'),
                    post: token('--color-success'),
                    put: token('--color-accent-dark'),
                    patch: token('--color-accent-dark'),
                    delete: token('--color-danger'),
                    options: token('--color-gray-2'),
                    head: token('--color-gray-2'),
                    basic: token('--color-gray-2'),
                    link: token('--color-gray-2'),
                },
            },
            schema: {
                linesColor: hairline,
                typeNameColor: muted,
                typeTitleColor: text,
                requireLabelColor: token('--color-danger-text'),
                nestedBackground: surface,
                arrow: { color: muted },
            },
            typography: {
                fontSize: '15px',
                lineHeight: '1.6',
                fontWeightLight: '300',
                fontWeightRegular: '300',
                fontWeightBold: '700',
                fontFamily: font,
                smoothing: 'antialiased',
                headings: { fontFamily: font, fontWeight: '700', lineHeight: '1.3' },
                code: {
                    fontSize: '14px',
                    fontFamily: token('--font-code'),
                    fontWeight: '400',
                    color: token('--color-white'),
                    backgroundColor: input,
                    wrap: true,
                },
                links: { color: primary, visited: primary, hover: token('--color-accent-light') },
            },
            sidebar: {
                width: '280px',
                backgroundColor: surface,
                textColor: text,
                activeTextColor: primary,
                groupItems: {
                    activeBackgroundColor: input,
                    activeTextColor: primary,
                    textTransform: 'uppercase',
                },
                level1Items: {
                    activeBackgroundColor: input,
                    activeTextColor: primary,
                    textTransform: 'uppercase',
                },
                arrow: { color: muted },
            },
            logo: { gutter: '0' },
            rightPanel: {
                backgroundColor: surface,
                textColor: text,
                width: '40%',
                servers: {
                    overlay: { backgroundColor: input, textColor: text },
                    url: { backgroundColor: token('--color-bg-black') },
                },
            },
            codeBlock: { backgroundColor: token('--color-bg-black') },
            fab: { backgroundColor: primary, color: token('--color-bg-black') },
        };
    }

    const container = document.getElementById('redoc');
    if (!container || typeof Redoc === 'undefined') {
        return;
    }
    Redoc.init(
        container.dataset.spec,
        {
            theme: krtTheme(),
            scrollYOffset: '.site-header',
            hideDownloadButton: false,
            expandResponses: '200,201',
            pathInMiddlePanel: false,
            nativeScrollbars: true,
        },
        container,
    );
})();
