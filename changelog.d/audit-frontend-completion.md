### Fixed
- Blueprint batch failures preserve the staged selection and return the backend error. Superseded searches cannot replace newer results; a completed save reports display-refresh failures without repeating the write.
- Session-loss fallbacks reach login consistently. Bank and audit downloads and P4K uploads use bounded streaming, with backend limits covering response completion.
- Purchasing CSV preserves delimiters, quotes and line breaks; import catalogue text columns as text in spreadsheet tools.
