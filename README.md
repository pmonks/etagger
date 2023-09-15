| | | | |
|---:|:---:|:---:|:---:|
| [**main**](https://github.com/pmonks/etagger/tree/main) | [![CI](https://github.com/pmonks/etagger/workflows/CI/badge.svg?branch=main)](https://github.com/pmonks/etagger/actions?query=workflow%3ACI+branch%3Amain) | [![Dependencies](https://github.com/pmonks/etagger/workflows/dependencies/badge.svg?branch=main)](https://github.com/pmonks/etagger/actions?query=workflow%3Adependencies+branch%3Amain) | [![Vulnerabilities](https://github.com/pmonks/etagger/workflows/vulnerabilities/badge.svg?branch=main)](https://pmonks.github.io/etagger/nvd/dependency-check-report.html) |
| [**dev**](https://github.com/pmonks/etagger/tree/dev)  | [![CI](https://github.com/pmonks/etagger/workflows/CI/badge.svg?branch=dev)](https://github.com/pmonks/etagger/actions?query=workflow%3ACI+branch%3Adev) | [![Dependencies](https://github.com/pmonks/etagger/workflows/dependencies/badge.svg?branch=dev)](https://github.com/pmonks/etagger/actions?query=workflow%3Adependencies+branch%3Adev) | [![Vulnerabilities](https://github.com/pmonks/etagger/workflows/vulnerabilities/badge.svg?branch=dev)](https://github.com/pmonks/etagger/actions?query=workflow%3Avulnerabilities+branch%3Adev) |

[![Latest Version](https://img.shields.io/clojars/v/com.github.pmonks/etagger)](https://clojars.org/com.github.pmonks/etagger/) [![Open Issues](https://img.shields.io/github/issues/pmonks/etagger.svg)](https://github.com/pmonks/etagger/issues) [![License](https://img.shields.io/github/license/pmonks/etagger.svg)](https://github.com/pmonks/etagger/blob/main/LICENSE)


<img alt="etagger logo: a street art image of a brick wall with a red tag scrawled across it that says 'etagger'" align="right" width="5%" src="https://raw.githubusercontent.com/pmonks/etagger/main/etagger-logo.png">

# etagger

A Clojure micro-library for cached (ETag based) URL downloads.  Fundamentally this library provides a single fn for reading the content of a URL, and will transparently cache that content locally on disk (in accordance with the [XDG Base Directory Specification](https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html)) and serve it out of that cache whenever possible.

Cached content is checked for staleness periodically via HTTP ETag GET requests, which are more efficient than a regular HTTP GET request in the event the cache is up to date.  This staleness checking interval is also configurable, obviating the need for any network I/O at all in some cases.  For applications that cannot tolerate , including all the way down to 0 (meaning "check for staleness on every request").

The library only has one non-core dependency - on `clojure.tools.logging`, and is compatible with JVMs 1.8 and above (it does not use the Java 11+ HTTP client).

While this is simple logic that is well understood and documented, the author thought it might be useful to centralise it to avoid the Clojure community having to reinvent (small) wheels.

## Installation

`etagger` is available as a Maven artifact from [Clojars](https://clojars.org/com.github.pmonks/etagger).

### Trying it Out

#### Clojure CLI

```shell
$ # Where #.#.# is replaced with an actual version number (see badge above)
$ clj -Sdeps '{:deps {com.github.pmonks/etagger {:mvn/version "#.#.#"}}}'
```

#### Leiningen

```shell
$ lein try com.github.pmonks/etagger
```

#### deps-try

```shell
$ deps-try com.github.pmonks/etagger
```

### API Documentation

[API documentation is available here](https://pmonks.github.io/etagger/), or [here on cljdoc](https://cljdoc.org/d/com.github.pmonks/etagger/).

## Contributor Information

[Contributing Guidelines](https://github.com/pmonks/etagger/blob/main/.github/CONTRIBUTING.md)

[Bug Tracker](https://github.com/pmonks/etagger/issues)

[Code of Conduct](https://github.com/pmonks/etagger/blob/main/.github/CODE_OF_CONDUCT.md)

### Developer Workflow

This project uses the [git-flow branching strategy](https://nvie.com/posts/a-successful-git-branching-model/), with the caveat that the permanent branches are called `main` and `dev`, and any changes to the `main` branch are considered a release and auto-deployed (JARs to Clojars, API docs to GitHub Pages, etc.).

For this reason, **all development must occur either in branch `dev`, or (preferably) in temporary branches off of `dev`.**  All PRs from forked repos must also be submitted against `dev`; the `main` branch is **only** updated from `dev` via PRs created by the core development team.  All other changes submitted to `main` will be rejected.

### Build Tasks

`etagger` uses [`tools.build`](https://clojure.org/guides/tools_build). You can get a list of available tasks by running:

```
clojure -A:deps -T:build help/doc
```

Of particular interest are:

* `clojure -T:build test` - run the unit tests
* `clojure -T:build lint` - run the linters (clj-kondo and eastwood)
* `clojure -T:build ci` - run the full CI suite (check for outdated dependencies, run the unit tests, run the linters)
* `clojure -T:build install` - build the JAR and install it locally (e.g. so you can test it with downstream code)

Please note that the `release` and `deploy` tasks are restricted to the core development team (and will not function if you run them yourself).

## License

Copyright © 2023 Peter Monks

Distributed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).

SPDX-License-Identifier: [Apache-2.0](https://spdx.org/licenses/Apache-2.0)
