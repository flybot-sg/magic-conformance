# magic-conformance

<div align="center">
    <a href="https://github.com/flybot-sg/magic-conformance/actions/workflows/conformance.yml"><img src="https://github.com/flybot-sg/magic-conformance/actions/workflows/conformance.yml/badge.svg" alt="MAGIC conformance"></a>
</div>

<br>

Does a Clojure library still build and test under [MAGIC](https://github.com/flybot-sg/magic) (Clojure→.NET for Unity/IL2CPP)? MAGIC ships Clojure 1.10 and [ClojureCLR](https://github.com/clojure/clojure-clr) (`cljr`) is ahead of it, so passing on one CLR compiler says nothing about the other.

This runner clones each library in a manifest and tests it on both, inside the [`ci-clj-clr`](https://github.com/flybot-sg/ci-clj-clr) image:

- `nos build` and `nos test` (MAGIC), always.
- `cljr -X:test` (ClojureCLR), when the library's `deps-clr.edn` has a runnable `:test` alias.

The runner scans a clone that carries no CLR config one level deep. Each directory that carries some runs as its own component.

Results are cached, so only changed libraries re-run.

`libs.edn` here lists the public libraries we have ported. `scripts/conformance.clj` is reusable: point it at your own manifest.

## Use it in your project

Add the runner to your `bb.edn`:

```clojure
{:deps {io.github.flybot-sg/magic-conformance {:git/tag "v0.1.0" :git/sha "..."}}  ; pin a tag/sha
 :tasks
 {:requires ([conformance :as c])
  check     {:task (c/check! (first *command-line-args*))}
  check-all {:task (c/check-all! (boolean (some #{"--force"} *command-line-args*)))}}}
```

Write a `libs.edn`, one entry per library:

```clojure
{my-org/my-lib
 {:git/url  "https://github.com/my-org/my-lib.git"  ; https or ssh
  :git/ref  "master"                                ; branch, tag, or sha
  :magic    {:build {:exclude [my.lib.jvm-only]}}   ; optional: written as magic.edn if the repo ships none
  :deps-clr {:paths ["src"]}                        ; optional: written as deps-clr.edn if the repo ships none
  :tasks    [build test]                            ; optional: auto-detected from magic.edn / dotnet.clj
  :cljr     false}}                                  ; optional: skip the ClojureCLR lane
```

A monorepo keeps its CLR config per component. The runner finds those directories and runs each one. Name a component to give it config of its own. The four keys above mean the same inside `:components`.

```clojure
{my-org/my-monorepo
 {:git/url    "https://github.com/my-org/my-monorepo.git"
  :git/ref    "main"
  :components {"core" {:magic {:test {:exclude [my.core.jvm-only]}}
                       :tasks [["bb" "gen-clr-rct"] build test]}
               "web"  {:tasks [build test]}}}}
```

Optional `conformance.edn` overrides the defaults:

```clojure
{:default-ref "master" :magic-version "v0.12.0"}
```

Run inside the `ci-clj-clr` image, or with `nos` and `cljr` on your PATH:

```bash
bb check my-lib        # one library
bb check-all           # all, incremental (writes results.edn)
bb check-all --force   # ignore the cache
bb rct                 # test the runner's helpers
bb clean               # drop work/ clones
```

## How it works

```mermaid
flowchart LR
  L[libs.edn] --> R[bb check-all]
  R -->|per library| S{ls-remote: ref SHA +<br/>MAGIC + config same?}
  S -->|yes| K[reuse cached result]
  S -->|no| A[clone] --> D[discover component dirs,<br/>else the clone root] --> B[inject magic.edn / deps-clr.edn<br/>if the dir ships none] --> C[nos build + test<br/>+ cljr -X:test when available]
  C --> W[(results.edn)]
  K --> W
```

A result is keyed on **(ref SHA, MAGIC version, config spec)**. `results.edn` is generated locally with `bb check-all` and committed; CI only reads it as the cache baseline. Bump `:magic-version` when the `ci-clj-clr` image tag bumps: it invalidates the cache, so every library re-runs under the new compiler.

## Files

```
libs.edn                 the manifest (the public libs we have ported)
conformance.edn          MAGIC version the results are keyed on
scripts/conformance.clj  the runner
deps.edn                 exposes the runner as a dependency
results.edn              committed run snapshot + cache baseline
```
