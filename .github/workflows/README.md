## Why no CI

This repository has no test or build workflow, and that is deliberate: the project is maintained by
one person on their own machine, and the build needs the Android SDK with a preview platform that
the hosted runners do not set up reliably. A red CI badge that nobody looks at is worse than none.

If you open a pull request, please paste the output of `./gradlew testDebugUnitTest` in the
description. That is the same check a workflow would run.
