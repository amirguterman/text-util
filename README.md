# Text Util

An offline Android notepad with typed search expressions. Create, edit, and delete multiple local notes; the first line names each note. Notes save automatically in app private storage. Search the current note and tap a result to select its text in the editor.

## Query syntax

Plain text is matched literally and case sensitively. A query searches one line at a time. Placeholders are embedded between literal text:

| Query | Meaning |
| --- | --- |
| `some text {int.range(1, 4)}` | Inclusive integer interval |
| `some text {int.max()}` | Largest matching integer in the current note; ties all appear |
| `some text {int.lt(5)}` | Integer less than 5 |
| `some text {type.decimal}: {number.in([1, 4, 5.06])}` | Decimal followed by one of these numeric values |
| `some text {oneof([2, 2.05, "aaa", js((o) => o > 5)])}` | A numeric or string value, or numeric value above 5 |
| `regex:foo\\s+\\d+` | Raw Java regular expression |

`int` accepts optional signs; `type.decimal` requires a decimal point. Numeric membership compares numeric values (`5.060` equals `5.06`); quoted membership compares exact text. A numeric token must end before another digit or decimal point. In `oneof`, the `js` spelling is a restricted predicate notation: only `(o) => o <, <=, >, >=, ==, or === number` is accepted. It never executes JavaScript or accesses device data. Unknown or incomplete queries show an error under the search field.

The examples in the request containing `number.in([1, 4, 5.06]}` and `js((o) => o > 5)}` were missing closing `)` or `]`; the table shows their completed syntax.

## Build

Open the repository in Android Studio with JDK 17 and Android SDK 36, then run the `app` configuration. Alternatively, with Gradle 8.13 installed, run `gradle :app:assembleDebug`. The APK is at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions also builds and uploads a debug APK on pushes and pull requests.

The matcher can be checked independently with `javac -d /tmp/text-util-tests app/src/main/java/com/amirguterman/textutil/SearchEngine.java app/src/test/java/com/amirguterman/textutil/SearchEngineSmokeTest.java && java -cp /tmp/text-util-tests com.amirguterman.textutil.SearchEngineSmokeTest`.
