# Hidden Refactoring (PhpStorm Plugin)

Attach IDE-level comments and human-friendly aliases to PHP code — with zero changes to the source.

Ever skimmed a teammate's code, left a mental note, and didn't want to litter the repo with WIP commits or noisy diffs? Hidden Refactoring lets you annotate and rename in your IDE only. Keep your flow, keep your repo clean.

P.S. Made during some vibe-coding sessions. Thanks, ChatGPT, for rubber-ducking at 3 a.m. ¯\\_(ツ)_/¯

## Features
- __Inline comments (Code Vision)__ over classes/methods/functions/files
  - First line preview with `💬`, click to view/edit/delete
  - Nicely formatted popup with timestamp
- __Aliases where you read code__
  - Inline aliases for variables, parameters, methods, functions, and classes
  - Color-matched to original identifiers (incl. method/function usages)
  - PHP 8 named arguments supported: alias shows right after `param:` label
- __Non-invasive & persistent__
  - Lives in project files under `.idea/` only; nothing touches your PHP sources
  - Keys survive typical refactorings

## Requirements
- PhpStorm 2024.3+ (build 243.*)
- PHP plugin enabled: `com.jetbrains.php`
- JDK 17+ for building

## Installation
Recommended: download the prebuilt ZIP from GitHub Releases and install from disk.

1) Download: https://github.com/underwear/hidden-refactoring/releases
2) PhpStorm → Settings → Plugins → Gear → Install Plugin from Disk…
3) Choose the downloaded ZIP and restart PhpStorm.

Alternatively, build from source:

```bash
./gradlew buildPlugin
```
Then install the ZIP from `build/distributions/` as above.

## Usage
- Place caret on a class/method/function (or in a file for a file-level note) and run: "Hidden Refactoring: Add Comment".
- Aliases: click an inline alias to edit, or use the action "Hidden Refactoring: Rename Alias" on the symbol.
- Comments show as `💬` hints; click to view/edit/delete.

## Development
- Update version in `gradle.properties` → `pluginVersion`
- Build: `./gradlew buildPlugin`
- Run sandbox: `./gradlew runIde`
- Tag releases as `v<version>` and push tags

## Issues & ideas
- Bugs, ideas, requests — please open an Issue.
- Have a wild feature idea for the next vibe-coding session? Drop it in Issues; best ones get built first.

## Privacy
- All data stays locally in the project: `.idea/hidden-refactoring-comments.xml`
- No network calls; intended for solo developer workflow
