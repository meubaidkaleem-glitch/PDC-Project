# Contributing to DistriCompute-Core

## Workflow

1. **Issues.** Open an issue for any significant piece of work, such as a new algorithm, a contract change or a
   new execution mode.
2. **Branches.** Branch from `main`, using `feature/<topic>`, `fix/<topic>` or `docs/<topic>`. Core
   development happens on `feature/core-engine` and its sub-branches.
3. **Commits.** Use [Conventional Commits](https://www.conventionalcommits.org/) with a scope, for example:
   * `feat(task): add universal task model`
   * `feat(matrix): implement block multiplication`
   * `test(partition): validate partition coverage`
   * `fix(matrix): reject incompatible dimensions`

   Every commit must contain real, working changes. Do not inflate or squash history artificially, and do not
   backdate commits.
4. **Pull requests.** Open a pull request for every substantial change, and have another contributor review
   it. CI must pass before merging.

## Definition of done

A feature is complete only when all of the following hold:

* It works and is exercised by automated tests (unit tests, plus concurrency tests where threads are involved).
* Its public interfaces have Javadoc, and the relevant `docs/` page is up to date.
* Its inputs are validated, and its errors use the shared error model (`ErrorCode`).
* Its limitations are written down.
* `./mvnw verify` passes locally and in CI.

## Shared contracts

The data contracts in [docs/integration-contract.md](docs/integration-contract.md) are shared with the
Distributed and Application repositories. **Never change them silently.** A breaking change needs agreement
from all three contributors, a `schemaVersion` bump, and coordinated updates in every affected repository.

## Code style

* Java 21. The compiler runs with `-Xlint:all -Werror`.
* Checkstyle ([config/checkstyle/checkstyle.xml](config/checkstyle/checkstyle.xml)) and SpotBugs run during
  `./mvnw verify`. Fix every finding. If you suppress one, add a comment that justifies it.
* Prefer immutable value types (records), small cohesive classes and explicit validation.
* Performance claims must come from recorded measurements (see `docs/performance-methodology.md` once it
  exists). Tests assert correctness, never timing.
