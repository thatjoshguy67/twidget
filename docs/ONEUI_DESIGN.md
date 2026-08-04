# Local oneui-design development

Twidget uses [tribalfs/oneui-design](https://github.com/tribalfs/oneui-design) (One UI 8 / SESL8), **not** the older [OneUIProject/oneui-design](https://github.com/OneUIProject/oneui-design) (One UI 4). Fork **tribalfs**, not OneUIProject, if you want a editable copy that matches what Twidget ships with.

## Why a local copy?

Runtime theme hacks (`applySurfaces`, avoiding drawer `setTheme`) work around library limits. A fork lets you change theme tokens, `DrawerNavigationView`, preference ripples, and accent handling **inside** the library.

## One-time setup

### 1. Fork on GitHub

This project uses [KingOwen2006/oneui-design](https://github.com/KingOwen2006/oneui-design) (fork of [tribalfs/oneui-design](https://github.com/tribalfs/oneui-design)).

### 2. Add as a git submodule

From the Twidget repo root:

```bash
git submodule add https://github.com/KingOwen2006/oneui-design.git libs/oneui-design
git submodule update --init --recursive
```

### 3. Credentials

The library build pulls SESL dependencies from Tribalfs GitHub Packages. Twidget copies `~/.config/twidget/github.properties` into `libs/oneui-design/github.properties` automatically when the composite build runs (that copy is gitignored).

## Daily workflow

When `libs/oneui-design/` exists, Gradle **substitutes** the published Maven artifact with the local `:oneui-design` project — edits in `libs/oneui-design/lib/src/` rebuild into the app on the next `./gradlew assembleDebug`.

```powershell
# Windows
$env:JAVA_HOME = "C:\Program Files\Android\Studio\jbr"  # or your JDK 21+
.\gradlew :app:assembleDebug
```

Theme-related paths to know:

| Area | Path |
|------|------|
| Base One UI theme | `libs/oneui-design/lib/src/main/res/values/themes.xml` |
| Drawer / navigation | `libs/oneui-design/lib/src/main/java/dev/oneuiproject/oneui/navigation/` |
| Layout widgets | `libs/oneui-design/lib/src/main/java/dev/oneuiproject/oneui/layout/` |
| Design colors | `libs/oneui-design/lib/src/main/res/values/colors.xml` |

Push changes on your fork; Twidget’s submodule pointer can be updated to pin a commit.

## Use published package instead

CI and contributors without a submodule checkout use the Tribalfs GitHub Packages artifact:

```powershell
.\gradlew :app:assembleDebug -PusePublishedOneUiDesign=true
```

Remove or rename `libs/oneui-design/` to fall back to the published `0.9.13+oneui8` dependency.

## Requirements

Local composite build needs:

- **Gradle 8.13+** (see `gradle/wrapper/gradle-wrapper.properties`)
- **AGP 8.13** (see root `build.gradle.kts`)
- **JDK 21** recommended (oneui-design targets JVM 21; Android Studio JBR works)

## Upstreaming

Consider opening PRs against [tribalfs/oneui-design](https://github.com/tribalfs/oneui-design) for fixes that benefit all SESL8 apps, and keep Twidget-specific branding in the app module (`app/src/main/res/values/themes_accent.xml`, `TwidgetTheme.kt`).
