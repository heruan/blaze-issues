# blaze-issues

Minimal, self-contained reproducers for [Blaze Persistence](https://github.com/Blazebit/blaze-persistence) bugs and edge cases that we hit while building real applications.

## How this repo is used

1. We open a **pull request** that adds a reproducer for a bug we are tracking. The PR contains a JUnit 5 test that **deliberately fails** while the upstream bug is unfixed.
2. CI runs on every PR; the failing test keeps the PR red.
3. When the relevant Blaze release lands, we bump the dependency version. The same test re-runs on the new release and turns green; **the PR is merged** as soon as it does.

This way the merge log shows exactly which upstream change resolved which bug, with a runnable verification attached.

## Layout

```
src/main/java/to/lova/blaze/issues/
    <issue-slug>/                — JPA entities + Blaze CTE entities used by the repro
src/test/java/to/lova/blaze/issues/
    <issue-slug>/ReproTest.java  — JUnit 5 test that triggers the bug (or asserts the fix)
src/test/resources/META-INF/
    persistence.xml              — single shared persistence unit, auto-discovers @Entity classes
```

Each reproducer lives in its own sub-package under `to.lova.blaze.issues.<issue-slug>` so a stack trace tells you which repro is firing.

## Versions

- Blaze Persistence `1.6.18`
- Hibernate ORM `7.2.12.Final` (`blaze-persistence-integration-hibernate-7.2`)
- Jakarta Persistence `3.2`
- PostgreSQL 17 (Testcontainers)
- Java toolchain `25`

## Running

```sh
./gradlew test
./gradlew test --tests 'to.lova.blaze.issues.<issue-slug>.ReproTest'
```

Requires Docker (Testcontainers will pull `postgres:17-alpine`).
