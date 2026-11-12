# Project: HyperGesture Maintenance & Hardening

## Architecture
HyperGesture is an Android accessibility service and gesture automation application built with Kotlin, Jetpack Compose, Shizuku integration, and Gradle.
- **UI Module**: `com.h3nc4.hypergesture.ui` (Compose activities, view models, settings screens).
- **Service Module**: `com.h3nc4.hypergesture.service` (Accessibility service, overlay managers, app switching, recents provider, touch replay engines).
- **Gesture Engine**: `com.h3nc4.hypergesture.gesture` (Gesture detection, stroke tracking, edge filters).
- **Navigation & Recovery**: `com.h3nc4.hypergesture.navigation` (Shell runner, navigation bar recovery).
- **Build & CI Infrastructure**: Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.21, Java 21, Configuration Cache, Spotless / Ktlint.

## Feature Inventory
Every feature and requirement identified in the user request and survey:
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Branch Cleanup (R1) | Safely remove 8 clean external worktrees and delete 16 stale/merged local branches using `git branch -d` | M1 | Survey 1 (R1) |
| 2 | Compose Autoboxing Fix (R2.1) | Replace `mutableStateOf(0)` with `mutableIntStateOf(0)` in `MainActivity.kt` | M2 | Survey 2 (R2) |
| 3 | Android 12+ Backup Rules (R2.2) | Create `res/xml/data_extraction_rules.xml` and add `android:dataExtractionRules` attribute in `AndroidManifest.xml` | M2 | Survey 2 (R2) |
| 4 | Monochrome Adaptive Icon (R2.3) | Consolidate `mipmap-anydpi-v26` to `mipmap-anydpi` and add `<monochrome>` tag in `ic_launcher.xml` | M2 | Survey 2 (R2) |
| 5 | Resource Shrinking (R2.4) | Set `isShrinkResources = true` in `app/build.gradle.kts` release build block | M2 | Survey 2 (R2) |
| 6 | Gradle Formatting Tooling (R3) | Integrate Spotless plugin (ktlint 1.5.0) providing `spotlessCheck` and `spotlessApply` | M3 | Survey 2 (R3) |
| 7 | Service Decoupled State Testing (R4.1) | Add comprehensive unit tests for `RecentTasksSource` using `ShellRunner` abstraction and fake runners | M4 | Survey 3 (R4) |
| 8 | Touch Replay & Action Planning Tests (R4.2) | Add unit tests for touch replay calculations, stroke clamping, timing and `GestureActionPerformer` | M4 | Survey 3 (R4) |
| 9 | AppSwitcher & Geometry Tests (R4.3) | Add unit tests for `AppSwitcher` stepping/chaining logic, edge math, and recents parsing | M4 | Survey 3 (R4) |
| 10 | Comprehensive Test & Lint Gate (R4.4) | Validate zero test failures in `testDebugUnitTest` and zero lint errors in `lintDebug` | M5 | Survey 3 (R4) |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Stale Branch Cleanup | Remove 8 worktrees; delete 16 local branches; verify `git branch` shows only `* main` | none | DONE |
| M2 | Android Lint & Configuration Hygiene | Fix Compose autoboxing, backup rules, adaptive icon, and release resource shrinking | none | DONE |
| M3 | Code Formatting Tooling | Integrate Spotless Gradle plugin, format Kotlin code, pass `spotlessCheck` | M2 | DONE |
| M4 | Service Unit Testing & Coverage | Decouple service state machines and implement comprehensive unit test suites | M2 | DONE |
| M5 | Full Suite Validation & Forensic Audit | Run all unit tests, lint checks, formatting checks, release build, and independent forensic audit | M1, M3, M4 | DONE |

## Code Layout & Ownership Boundaries
- **M1 (Branch Cleanup)**: Git repository metadata only (`.git/`, worktrees, branch references). No source code touched.
- **M2 (Lint & Config)**:
  - `app/src/main/kotlin/com/h3nc4/hypergesture/ui/MainActivity.kt`
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/res/xml/data_extraction_rules.xml` (new)
  - `app/src/main/res/mipmap-anydpi/ic_launcher.xml` (renamed from `mipmap-anydpi-v26`)
  - `app/build.gradle.kts`
- **M3 (Formatting Tooling)**:
  - `build.gradle.kts` (root)
  - `app/build.gradle.kts`
  - All `.kt` files formatted via `spotlessApply`
- **M4 (Service Unit Testing)**:
  - `app/src/main/kotlin/com/h3nc4/hypergesture/service/RecentTasks.kt` (interface widening to `ShellRunner`)
  - `app/src/main/kotlin/com/h3nc4/hypergesture/service/AppSwitcher.kt`
  - `app/src/main/kotlin/com/h3nc4/hypergesture/service/HyperGestureAccessibilityService.kt`
  - `app/src/test/kotlin/com/h3nc4/hypergesture/service/` (new test files: `RecentTasksSourceTest.kt`, `TouchReplayPlannerTest.kt`, `AppSwitchEngineTest.kt`, `ScreenGeometryTest.kt`, `GestureActionPerformerTest.kt`, etc.)
- **M5 (Validation & Audit)**: Read-only verification across entire project.

## Interface Contracts
### RecentTasksSource ↔ ShellRunner
```kotlin
// In app/src/main/kotlin/com/h3nc4/hypergesture/service/RecentTasks.kt:
internal class RecentTasksSource(
    private val shell: ShellRunner = ShizukuShellRunner(),
    private val gateway: ShizukuGateway = SystemShizukuGateway,
    private val isAppSwitchEnabled: () -> Boolean = { true },
)
```
- `ShellRunner` is already defined in `com.h3nc4.hypergesture.navigation.ShellRunner`.
- `ShizukuShellRunner` implements `ShellRunner`.
- Default parameter maintains 100% binary and source compatibility for production callers.

### TouchReplayPlanner
- Deterministic extraction of replay math (tap-vs-drag slop, stroke duration clamping, path point generation, timeout calculations).
- Pure Kotlin, no `android.*` framework references.

### AppSwitchEngine
- Pure Kotlin stepping and boundary calculation for recent tasks navigation and 5-second chaining window.
