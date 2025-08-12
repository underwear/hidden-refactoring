# Hidden Refactoring (PhpStorm Plugin)

Attach IDE-level comments to PHP classes, methods, functions, and files without modifying source code.

Status: actively developed. Latest: v0.1.18

## Features
- __Native Code Vision inline hints__ above PHP declarations
  - Shows first line of the comment only, cropped to 150 chars, prefixed with `💬`
  - Updates instantly after add/edit/delete (no need to modify the file)
  - Clickable: opens a popup with the full comment
- __Comment popup__
  - Title shows timestamp (created at)
  - Full text preview (read-only)
  - Inline actions: Edit, Delete
  - Comfortable UI: paddings and a wider resizable dialog
- __Add/Edit via textarea__
  - Multiline input dialog
  - Single comment per element: new adds replace the existing one
- __Gutter icon__ on element names for commented items
- __Comments Tool Window__
  - Searchable list of commented elements with details pane
  - Inline actions: Edit, Delete; toolbar: Refresh
- __Robust storage__
  - Per-project, IDE-level (no changes to source)
  - Survives refactorings via name/signature-based keys

## Requirements
- PhpStorm 2024.3+ (build 243.*)
- PHP plugin enabled: `com.jetbrains.php`
- JDK 17+ for building

## Installation
The easiest way: download the prebuilt ZIP from GitHub Releases and install it from disk:

1) Download: https://github.com/underwear/hidden-refactoring/releases
2) PhpStorm → Settings → Plugins → Gear → Install Plugin from Disk…
3) Choose the downloaded ZIP and restart PhpStorm.

Alternatively, build from source:

```bash
./gradlew buildPlugin
```
Then install the ZIP from `build/distributions/` as above.

## Usage
- Open a PHP file and place caret on a class/method/function (or in file for file-level comment)
- Run action: "Hidden Refactoring: Add Comment"
- Inline hint with `💬` appears above the declaration
- Click the hint to view the full comment, edit, or delete

## Development
- Update version in `gradle.properties` → `pluginVersion`
- Build: `./gradlew buildPlugin`
- Run sandbox: `./gradlew runIde`
- Tag releases as `v<version>` (e.g., `v0.1.18`) and push tags

## Privacy
- All data stays locally in the project: `.idea/hidden-refactoring-comments.xml`
- No network calls; intended for solo developer workflow
