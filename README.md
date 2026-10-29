## Community development

Public extension sources: Apache-2.0. Build and contribute without private IDE access:
see [CONTRIBUTING.md](CONTRIBUTING.md) and the pinned [SDK](sdk.lock.json).

# DevLine Gradle

Standalone IDE extension, enabled by default and distributed through the desktop extension catalog. It contributes one left sidebar panel. It is independent of Tellfair and is never an application/project dependency.

Open Gradle, link an absolute build directory if the workspace has no Gradle files, then Sync. The Tooling API discovers real projects and grouped tasks. Run a task with its play button; Stop cancels the owned Tooling operation. Output uses bounded UTF-8 streaming with cursors. Project and task pages can be loaded incrementally; project/search filters run on the full imported task model.

Opening the panel only detects build/wrapper files. Sync and Run configure/execute the selected build and can download dependencies. The project's Gradle wrapper takes precedence; wrapperless builds use Gradle 9.7.1 from the normal Gradle cache, downloaded if needed. Only the Tooling API client is bundled. No JavaScript package, global Gradle install, shell output parser or fake task list is used.

Linked roots are stored atomically in the IDE profile (devline.gradle/settings.json). Disable, project close and owner changes cancel owned operations. Hidden panels stop polling; an explicitly started build can continue. No project build files are rewritten. Composite builds and dependency browsing are not provided by this first version.

Community build: `python ci/public_build.py`. Product-integrated Gradle tasks below require the owning Studio checkout.

Check: ./gradlew :devline-gradle:gradleCheck. Supply -PgradleFixtureInstallation=/absolute/gradle/path for the real offline Tooling fixture (multiproject sync, task output/file creation, cancellation). Without that property the pure state/output/ownership checks run.

Requires API 6, DevLine >= 0.1.0 and ui.webviews, ui.sidebar, project.context. Older hosts reject the package before activation. The archive contains the provider, Gson, official Gradle Tooling API and SLF4J API; SDK classes are compile-only.
