# Unicode segmentation inputs

These files are from the Unicode 18.0.0 Character Database:
<https://www.unicode.org/Public/18.0.0/ucd/>.

The six break property/test files come from `auxiliary/`, `emoji-data.txt` from
`emoji/`, and `DerivedGeneralCategory.txt` from `extracted/`.
`DerivedCoreProperties.txt` supplies Indic_Conjunct_Break. Emoji properties supply
Extended_Pictographic; general categories supply word-like classification.

`SHA256SUMS` pins the original downloaded bytes. The generator verifies the hashes
and release headers before consuming each file. Regenerate all three property
tables and all three conformance fixtures with
`./gradlew :codegen:generateSegmentationData`. No CLDR checkout is required.

Rules follow UAX #29 revision 49:
<https://www.unicode.org/reports/tr29/tr29-49.html>.
The Unicode 18 GB9c rule joins a linker followed by zero or more InCB extenders
to a consonant without requiring an earlier consonant. The public segmentation
backend owns these rules independently of the legacy person-name helper, whose
Unicode 15.1 data remains tied to the CLDR bundle. Collation has a separate pin.

Unicode data is subject to the Unicode terms of use:
<https://www.unicode.org/license.txt>.
