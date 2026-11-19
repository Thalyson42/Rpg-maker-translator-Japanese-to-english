# RPG Maker Translator

Android app that translates **Japanese RPG Maker games into English** directly on your phone — dialogue, choices,
names, items, skills, menus and system terms — while keeping the game working.

Language: **English** · [Português](README.pt-BR.md)

## What it does and what it is for
Many Japanese RPG Maker games have no English version. This app reads the game's data files, translates the text
and writes a translated copy of the data, leaving the original files untouched. You then copy the translated files
into the game (or a copy of it) and play it in English.

**Supported engines**

| Engine | Files | Status |
|---|---|---|
| RPG Maker MV / MZ | `data/*.json` | supported |
| RPG Maker VX Ace | `.rvdata2` (also inside `.rgss3a`) | supported |
| RPG Maker VX | `.rvdata` (also inside `.rgss2a`) | supported |
| RPG Maker XP | `.rxdata` (also inside `.rgssad`) | supported |
| RPG Maker 2000 / 2003 | `.ldb` / `.lmu` | not supported yet |
| Wolf RPG Editor | `.wolf` / `.dat` | not supported yet |

**Features**
- Three translators: **ML Kit** (free, offline after a one-time ~30 MB model download, no key), **DeepL** and
  **Google Cloud** (API key required). If a paid quota runs out, the app can switch to another translator or save a
  partial result and let you continue later.
- **Keeps the game from breaking:** control codes (`\C[2]`, `\V[1]`, `\N[1]`, `%1`...) are protected, and a translation
  that alters them is discarded. Switches, variables, image/audio names, scripts, comments and plugin commands are never
  touched.
- **Text fits the windows:** dialogue is re-wrapped for English (width taken from the game's screen size and font),
  item/skill descriptions are fitted to the 2-line help window, and long battle messages shrink the font instead of
  being cut off.
- **Plugin-aware:** names that plugins reference from note tags (for example `<コマンド:スキル/魔術,...>`) are translated
  once and the same translation is written into the tags, so options such as battle-menu commands do not disappear.
- **Plugin menu text:** if you choose the game's root folder, text parameters in `js/plugins.js` (for example extra
  battle-menu buttons such as a log or equipment button) are translated too, and button labels written inside note
  tags are translated. Anything a plugin compares or references (identifiers, event names, file names, scripts,
  numbers, switches) is skipped.
- Translation **cache** shared by all translators, so the same text is never translated (or billed) twice.

## Install and update
1. Create a GitHub repository and upload `RpgTranslator.zip` (Add file → Upload files).
2. Add file → Create new file named `.github/workflows/build.yml` and paste the contents of `build.yml`. Commit.
3. Open the **Actions** tab and wait for the build (about 3–5 minutes).
4. Download `RPG-Maker-Translator.apk` from **Releases** (or from Actions → Artifacts) and install it.

**Updating:** every build is signed with the same key and has a higher version number, so a new APK installs
**over** the old one — no uninstall, and your settings are kept. (Only if you previously installed an older build
signed with a different key, uninstall it once.) The current version is shown under the app title.

> The signing key (`app/rpgtl.jks`) is included in the project so that updates work out of the box. If you publish the
> repository, create your own key and change the passwords in `app/build.gradle.kts`.

## How to use
1. **Back up the game.** Work on a copy.
2. Open the app and tap **1. Choose game folder**. Prefer the game's **root folder** (the app finds `data`,
   `www/data`, `Data` or an `.rgss3a` archive by itself, and can also translate plugin text from `js/plugins.js`).
   Picking only the `data` folder works, but plugin menu text is then left in Japanese.
3. Tap **2. Choose output folder**. The app creates a `data_en` folder there.
4. Pick the **main translator**. ML Kit needs no setup (internet is required the first time to download the model).
   For DeepL or Google, paste your API key. Keep *"If the quota runs out, continue with the others"* checked if you
   want automatic fallback.
5. Keep **"Also translate plugin menu text"** checked to translate plugin parameters (needs the root folder). Only
   parameters that are display text are translated; identifiers, meta-tag names, list/template names, event names and
   anything the plugin code compares are left alone. The log lists how many texts were translated per plugin.
   Uncheck it if a plugin misbehaves after translation.
   Set **Characters per dialogue line** (default 50). The app never exceeds what fits the game's screen.
6. Tap **3. Translate** and keep the screen on until it finishes. The log shows progress and warnings.
7. When it says **Translation complete**, copy the contents of `data_en` over the game's data folder (same names).
   If the app also created `js_en/plugins.js`, copy it over the game's `js/plugins.js` (keep a backup of the original).
   For games packed in `.rgss3a`/`.rgss2a`/`.rgssad`, create a `Data` folder next to `Game.exe`, put the files there
   and keep the archive as a backup (if the game still shows Japanese, move the archive away).
8. Start the game.

**Cache:** while translating, finished texts are saved so you can stop and resume. When a translation **completes**,
the cache is cleared automatically. If it stops early (quota, cancel), the cache is kept so you can continue.
The **Clear translation cache** button deletes it manually.

## Limitations
- Text inside images is **not** translated.
- Japanese text written directly in a plugin's code (not in its parameters) is not translated; the log lists the
  plugins that contain it, and it needs a manual edit.
- Japanese words inside note tags that are not button labels are left alone (the log lists examples).
- Very long names (for example skill types in the narrow battle command window) can still be cut; the log warns you.
- Machine translation can be wrong or awkward. DeepL usually gives the best result.
- RPG Maker 2000/2003 and Wolf RPG are not supported yet.

## Troubleshooting
- **An option disappeared from a menu:** a plugin probably matches it by its Japanese name. Check the log for
  "Names referenced by note tags" and "Warning: note tags contain Japanese text" and, if needed, send the plugin
  file for analysis.
- **A menu button is still in Japanese:** it is probably a plugin parameter (choose the game's root folder and copy
  `js_en/plugins.js` into `js/`) or text hard-coded in a plugin (see the log note about plugins with Japanese in their code).
- **Text is cut off:** lower *Characters per dialogue line*, or translate again.
- **"Stopped by a quota limit":** run again later or switch translator; the cache keeps what was already translated.
