# Android offline romanization

| Component | Pinned source | License |
| --- | --- | --- |
| Character readings | [go-pinyin v0.21.0](https://github.com/mozillazg/go-pinyin/tree/v0.21.0) | MIT, Copyright (c) 2016 mozillazg |
| Phrase readings | [phrase-pinyin-data cee0ed6e6e4898580cafd2bd5e3723e20b214aa0](https://github.com/mozillazg/phrase-pinyin-data/tree/cee0ed6e6e4898580cafd2bd5e3723e20b214aa0) | MIT, Copyright (c) 2017 mozillazg |
| Traditional character lookup | [OpenCC 5c764a5a886f46eb365656ed91a0410d7162fac6](https://github.com/BYVoid/OpenCC/tree/5c764a5a886f46eb365656ed91a0410d7162fac6) | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| Korean rules | [koreanromanizer v1.0.0](https://github.com/misa198/koreanromanizer/tree/v1.0.0), based on koroman e529729b923f8a9d9acc62b2c9c0887b00e9bd88 | MIT, Copyright (c) 2025 Donghe Youn (Daissue) |

Korean rules are ported to Kotlin, preserving the Go implementation's unchanged
non-Hangul text. Chinese conversion follows the desktop engine. The unmodified
`pinyin.txt` and `TSCharacters.txt` came from
[Airmedy's romanization data at d859940](https://github.com/misa198/airmedy/tree/d859940c0629907e377c889fb276904b6a7a4b8c/internal/infra/romanization/data).
All three dictionaries live in `androidApp/src/main/assets/romanization`.
Gradle packages them and includes this notice and the OpenCC license.

`characters.tsv` contains hexadecimal Unicode code points and the first
tone-marked reading from go-pinyin. To regenerate it, run
`go run ./mobile/tools/romanization/export.go` from the pinned Airmedy checkout
linked above and copy the resulting sorted file into the Android assets here.
Android builds use the checked-in data. Update the Android engine version when
changing dictionaries or rules, and re-run the bundled reading fixtures.

## MIT license (character/phrase data and Korean rules)

Copyright (c) 2016, 2017 mozillazg

Copyright (c) 2025 Donghe Youn (Daissue)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
