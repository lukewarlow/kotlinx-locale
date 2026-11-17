# Unicode casing inputs

These files are from the Unicode 15.1.0 Character Database:
<https://www.unicode.org/Public/15.1.0/ucd/>.

`UnicodeData.txt` supplies simple mappings and canonical combining classes.
`SpecialCasing.txt` supplies full mappings and conditions. `DerivedCoreProperties.txt`
supplies Cased and Case_Ignorable; `PropList.txt` supplies Soft_Dotted.

`SHA256SUMS` pins the downloaded bytes, including UnicodeData.txt, which has no
version header. The generator checks the sums and other files' version headers.
Regenerate with `./gradlew :codegen:generateCasingData`. Runtime casing uses only
the generated tables and common Kotlin.

Unicode data is subject to the Unicode terms of use:
<https://www.unicode.org/license.txt>.
