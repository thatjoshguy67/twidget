# Localisation guide

Thanks for wanting to bring Twidget to your language. Translations are plain
Android XML files edited in a fork and submitted through GitHub; there is no
hosted translation platform. Twidget currently ships in **English** (the
default) and **German** (`values-de`, contributed by Aaron the Techie).

## Quick start

```bash
# 1. Create the language folder from the English files (qualifier examples: fr, pt-rBR, b+sr+Latn)
scripts/translations.sh init fr

# 2. Translate the text between the tags in app/src/main/res/values-fr/*.xml

# 3. Check what is still missing or untranslated
scripts/translations.sh status fr
```

Then read [Step 3](#step-3-register-the-language-in-the-app) for the few code
lines that add your language to the in-app pickers, and open a pull request.
If you are not comfortable editing Kotlin, submit the strings and say so; a
maintainer will do the wiring.

## Before you start

- Search open issues and pull requests for your language so effort is not
  duplicated. If nobody has claimed it, open an issue saying which language
  (and regional variant, if any) you intend to translate.
- You should be a fluent speaker of the target language. Machine translation
  is fine as a starting point, but every string must be reviewed by a person
  who will notice wrong terminology, gender, formality, and truncation.
- One language per pull request.

## Step 1: create the language folder

`scripts/translations.sh init <qualifier>` creates
`app/src/main/res/values-<qualifier>/` containing copies of the four English
files that hold user-facing text, with non-translatable strings (URLs, brand
names, and format-only templates) already stripped out:

| File | What it covers |
| --- | --- |
| `strings.xml` | Almost everything: dashboard, settings, widgets, Brief, scheduling, About |
| `milestone_strings.xml` | Follower milestone cards and notifications |
| `streak_splash_strings.xml` | Streak splash screen |
| `top_followers_browser_strings.xml` | Top Followers browser |

The qualifier follows Android resource naming, which differs from the
BCP-47 language tag used in code and in `locales_config.xml`:

| Language | Resource qualifier (folder) | BCP-47 tag (code) |
| --- | --- | --- |
| French | `values-fr` | `fr` |
| Brazilian Portuguese | `values-pt-rBR` | `pt-BR` |
| Simplified Chinese | `values-zh-rCN` | `zh-CN` |
| Serbian (Latin script) | `values-b+sr+Latn` | `sr-Latn` |

Prefer a plain language code unless regional differences are significant
enough that speakers of the other variant would object; a plain code serves
every region of that language.

The script needs Bash (macOS, Linux, Git Bash, or WSL on Windows). Without
it, copy the four files by hand and delete every line containing
`translatable="false"`.

## Step 2: translate the strings

Only the text between the tags changes. Work through the files, then run
`scripts/translations.sh status <qualifier>`, which lists strings that are
missing, strings that should not be there, and strings still identical to
English.

### Rules that keep the build green

- **Never change `name="..."` attributes.** The name is how code finds the
  string.
- **Keep every placeholder**: `%1$s`, `%2$d`, `%d`, `%s`. You may reorder
  numbered placeholders (`%2$s … %1$s`) to suit your grammar, but the set must
  match the English string. Lint fails the build on mismatches.
- **Keep `\n`** line breaks where the English string has them.
- **Escape** apostrophes and quotes as `\'` and `\"`, and write `&` as
  `&amp;` and `<` as `&lt;`.
- **Plurals** use CLDR quantity keys: `zero`, `one`, `two`, `few`, `many`,
  `other`. Add the keys your language needs and drop the ones it does not;
  `other` is always required. English only uses `one` and `other`, so
  languages such as Polish, Russian, or Arabic will need more items than the
  source shows.
- **Do not add strings that are not in the English files.** Anything the
  `status` command lists under *Extra* must be deleted; lint rejects it.

### Style

- The English source uses British spelling and a friendly, direct tone.
  Match the register your language uses for consumer apps (for example,
  informal *du* in German rather than *Sie*).
- Keep product names untranslated: **Twidget**, **X**, **Twitter**,
  **Buffer**, **FxTwitter**, **FxEmbed**, **Rettiwt**, **TwitterAPIs**,
  **Gemini Nano**, **One UI**, **Cloudinary**.
- Translate Twidget's own feature names consistently and keep a list while
  you work: *Your Brief*, *Top Followers*, *milestone*, *streak*, *bridge*,
  *self-hosted bridge*, *shared history*, *data source*, *Awaiting
  confirmation*.
- **Widgets have very little room.** Strings whose names start with
  `widget_`, `lockscreen_`, or `brief_widget_`, plus short labels such as
  `followers`, should be as short as the English text or shorter.
- Apart from the two picker labels in Step 3, do not edit the English files
  in a translation pull request. If you find a typo or unclear English
  string, open a separate issue or pull request.

The helper also includes `settings_strings.xml`, which contains the Settings
category and navigation labels.

### Brief template copy

The Brief engine writes its card titles, bodies, summary, and provider notes
from resources too. Their names start with `brief_summary_`, `brief_short_`,
`brief_followers_`, `brief_card_`, `brief_goal_`, `brief_guide_`,
`brief_window_`, `brief_provider_note_`, `tweet_performance_`, and
`milestone_noun_`. A few things to know when translating them:

- Several are `<plurals>`; keep the `%1$s` placeholder that carries the
  already-formatted number.
- `brief_window_*` are inserted into `brief_guide_window_title` and
  `brief_guide_window_body`, so make the pieces read naturally together
  (German uses *am Vormittag*, for example).
- `milestone_noun_*` are inserted into goal copy such as
  `brief_goal_remaining_body`, `brief_goal_heading`, and the
  `milestone_goal_*` strings, so check those sentences with each noun.
- The Brief is generated once and cached. When the app language changes, the
  next refresh rewrites the template copy and asks the AI (if enabled) to
  write in the new language. Widgets immediately use a localised factual
  summary when the stored summary is in another language.

### What the resource files do not cover

A little text is still generated in Kotlin without access to resources, so it
stays English in every language. Translators are not expected to fix this;
it is tracked as maintainer work. Known areas:

- **Validation and failure messages** raised from data code, such as the
  scheduling checks in `schedule/ScheduleModels.kt`, analytics import checks
  in `data/TwidgetStore.kt`, and reminder scheduling failures in
  `schedule/ScheduleCoordinator.kt`.
- **Debug-only text** in Brief diagnostics and the bridge log viewer.

Everything else, including screens, dialogs, notifications, widgets, and the
Brief, should come from the resource files. If you see English elsewhere
after switching language, mention it in your pull request so a maintainer can
move the string into resources.

## Step 3: register the language in the app

Android already uses your folder when the device language matches. Twidget
also offers explicit language pickers, so four small edits make it selectable.
The German localisation ([#18](https://github.com/thatjoshguy67/twidget/pull/18),
[#19](https://github.com/thatjoshguy67/twidget/pull/19)) is a worked example.
Use the BCP-47 tag from the Step 1 table, not the folder qualifier.

1. `app/src/main/res/xml/locales_config.xml` — add
   `<locale android:name="fr" />` (or `pt-BR`, `sr-Latn`). This lists the
   language in Android 13+ per-app language settings and tells Play which
   languages the app supports.
2. `app/src/main/java/com/tjg/twidget/core/AppLocales.kt` — add a branch to
   `resolve()`. It lowercases the stored tag before matching, so the match
   key must be lowercase even when the tag has a region or script:

   ```kotlin
   "fr" -> Locale.FRENCH
   "pt-br" -> Locale.forLanguageTag("pt-BR")
   "sr-latn" -> Locale.forLanguageTag("sr-Latn")
   ```

   Add the language to `AppLocales.supportedLocale()` as well, so Brief prompts
   and background rendering use the same supported-language fallback.

3. `app/src/main/java/com/tjg/twidget/settings/SettingsLanguage.kt`
   — add the tag (`"fr"`, `"pt-BR"`) to the legacy dialog’s `tags` array and a matching entry backed by a new `language_<name>` string.
4. `app/src/main/java/com/tjg/twidget/widget/WidgetConfigActivity.kt` — the
   same pattern in `pickLanguage()` and `languageLabel()` with the same tag
   (`"fr"`, `"pt-BR"`), backed by a new `widget_language_<tag>` string. The
   stored value is lowercased by `AppLocales.resolve()`, which is why the
   match key in step 2 is lowercase.

Add the two new label strings to `values/strings.xml` in the existing format
(`French (Français)`), and translate them in `values-de/strings.xml` and your
own folder so lint's missing-translation check passes.

### Optional language-specific behaviour

Some formatting branches on `locale.language == "de"` and otherwise falls
back to English conventions with locale-aware month and day names. Your
language works without touching these; improve them if you like:

- **Spelled-out follower counts** in the artwork widget come from
  `TwidgetWidget.followersInWords()`. Add a `<language>NumberWords()` function
  next to `germanNumberWords()` and a case in
  `app/src/test/java/com/tjg/twidget/widget/WidgetFollowerWordsTest.kt`.
- **Date and time patterns** live in `AppLocales.formatDate()`,
  `TwidgetStore`, `ScheduleComposeActivity`, and `ScheduleQueueHostActivity`.
- **Widget text fitting** in `WidgetArtworkRenderer` uses a smaller minimum
  text size and tighter line height for German because of long compound
  words.

Keep these in the same pull request only if they are small; otherwise land
the strings first and follow up.

## Step 4: test

If you have an Android build environment (see
[CONTRIBUTING.md](../CONTRIBUTING.md); it needs JDK 17+, the Android SDK, and
a `github.properties` with a `read:packages` token), run:

```bash
./gradlew :app:lintGithubDebug :app:testGithubDebugUnitTest :app:assembleGithubDebug
```

Lint catches missing translations, placeholder mismatches, malformed XML, and
extra strings. Pull requests from forks only run bridge checks in CI, so this
local run is the only Android check before review. If you cannot build, say
so in the pull request; `scripts/translations.sh status` covers the most
common lint failures and a maintainer will run the rest.

On a device or emulator, check:

- Settings → Language switches the whole app, including notifications and
  the About screen.
- Android 13+: System Settings → Apps → Twidget → Language lists your
  language.
- Widget configuration offers your language, and the follower artwork and
  lock-screen 1x1/2x1 widgets re-render in it. Brief widgets also honour their
  selected language, using localised template summaries when it differs from
  the cached AI summary. Changing one widget must not change the app cache.
- No clipped or overlapping text in widgets, buttons, dialogs, and the
  milestone and streak screens. Tablet and foldable layouts use the same
  strings.
- Right-to-left languages: `android:supportsRtl` is enabled but no RTL
  language has shipped yet, so expect some layout issues and list them in
  the pull request.

## Step 5: open the pull request

- Title it `Add <Language> translation` and fill in the pull request
  template.
- Include screenshots of a few translated screens (dashboard, settings, a
  widget) with personal data removed.
- List strings you were unsure about or had to shorten heavily so a reviewer
  who speaks the language can focus there.
- Mention the new language in [`CHANGELOG.md`](../CHANGELOG.md), following
  the German entry in 1.2.0; add an `## [Unreleased]` section at the top if
  there is none.
- If you would like to be credited in the About screen's **Thanks to**
  section, say so and include the name and link you want shown.

## Keeping a translation up to date

Strings change between releases. When new English strings land, lint fails
on `MissingTranslation` for every shipped language, so maintainers may add
interim translations to your folder to keep builds green; these deserve a
native speaker's review. Run `scripts/translations.sh status <qualifier>` on
`main` from time to time and send a follow-up pull request. Small, frequent
updates are easier to review than one large sync.

## Questions

Open an issue describing the language and what you are unsure about, or ask
in the pull request itself. Partial translations are welcome as draft pull
requests if you want early feedback on terminology.
