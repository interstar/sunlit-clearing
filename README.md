# Sunlit Clearing

A bright place to see into a code project. Point it at a repo root, browse the
tree, open files with syntax colouring, and attach editable sidecar annotations
(markdown + fenced plugin blocks) that **do not** modify source files.

This is a greenfield Clojure / ClojureScript (Leiningen) prototype. The earlier
Python LLM analyzer lives in `legacy/`. Design notes for the broader product are
in `next.md`.

Internal Clojure namespaces still use the historical `codestory.*` prefix; the
product name is **Sunlit Clearing**, and install artifacts use `clearing` /
`sunlit`.

## V1 features

- Collapsible repository tree on the home page (zoom in / back out)
- File view with highlight.js colouring (Clojure, Python, C++, Haxe, JS/TS, Java, …)
- Annotations stored under `.sunlit/annotations/<path>.md` inside the watched repo
- Wiki-style links between files: `[[src/foo.clj]]` or `[[label|src/foo.clj]]`
- Links to locations inside files: `[[src/foo.clj#draw-circle]]` (symbol),
  `[[src/foo.clj#42]]` (line), `[[src/foo.clj#40-60]]` (range). The file view has
  a line-number gutter: click for a line permalink, shift-click for a range
- Plugin fences in annotations:

````markdown
```plugin leiningen
{}
```
````

- Built-in **leiningen** plugin (auto panel on `project.clj`, also via fence)
- Built-in **image** plugin (auto panel for PNG/JPEG/GIF/WebP/SVG/BMP/ICO/AVIF files, also via fence)
- Built-in **gallery** plugin (auto thumbnail grid when a directory contains images, also via fence)
- Built-in **malli** / **instaparse** plugins (scan `src/` for schemas & grammars)
- Optional user plugins as SCI scripts in `.sunlit/plugins/*.clj`
- Built-in **matrix** plugin: a grid for matrix-shaped code (types × operations,
  apps × targets × concerns) — see [Matrix plugin](#matrix-plugin)
- **Plugins** page listing built-in and repo plugins (with descriptions)
- **Conventions** page: check the repo against a declared layout template
  (directories, files, .gitignore rules, structured-document formats) + a lightweight issues list
- Agent **sessions** list/detail via eyeball_kid (`scripts/sessions_bridge.py`)

## Quick start (dev)

```bash
# compile CLJS once, then serve this repo (or pass another path)
./go.sh
# or:
lein cljs
lein run -m codestory.core -- --port 4567 /path/to/repo
```

Open http://localhost:4567/

### Figwheel (interactive CLJS)

```bash
lein fig
# in another terminal:
lein run -m codestory.core -- --port 4567 .
```

## Install (uberjar)

Rebuild from scratch and install locally:

```bash
./scripts/install-local
```

That produces:

```text
~/bin/clearing.jar
~/bin/sunlit
~/.local/lib/mtc/vendor/clearing.jar   # for Mind Traffic Control / Basket
```

Then (with `~/bin` on your `PATH`):

```bash
sunlit --port 4567 /path/to/repo
```

Use `./scripts/install-local --skip-clean` for a faster incremental rebuild.

## Plugins

**Built-in** — one namespace/file each under `src/clj/codestory/plugins/`,
registered from `codestory.plugins`. Each exports a `plugin` map:

```clojure
{:name "leiningen"
 :description "…"
 :run (fn [{:keys [path params repo-root]}]
        {:html "…"        ; human panel (primary)
         :data {…}        ; structured facts for /agents/ (optional)
         :text "…"})}     ; plain/markdown for agents (optional)
```

A `:run` result uses these keys:

- `:html` — human-readable HTML panel (the primary output).
- `:markdown` — optional; the host renders it to HTML when `:html` is absent.
- `:text` — optional; plain text or markdown for the `/agents/` endpoints.
- `:data` — optional; structured, JSON-safe EDN (maps/vectors of strings,
  numbers, booleans, nil) for agents and the `/agents/` endpoints.
- `:ok false` — mark the run as failed.

**Per-repo** — SCI scripts in `<repo>/.sunlit/plugins/*.clj`. Prefer returning
the same map shape (see `examples/plugins/hello.clj`).

Long-running builtins:

| Plugin | Default command | Report |
|--------|-----------------|--------|
| `unittests` | `lein test` / `pytest` | log cache |
| `cloverage` | `lein cloverage` | iframe from output path |
| `pycov` | `pytest --cov … --cov-report=html` | `htmlcov/index.html` |
| `malli` | scan `src/` for Malli schemas | HTML panel |
| `instaparse` | scan `src/` for Instaparse grammars | HTML panel |
| `structure` / `structure-*` | deterministic architecture extract | HTML panel |
| `mystyle-clj` | `bb scripts/mystyle-clj.clj <repo>` | findings panel |
| `mystyle-py` | `python3 scripts/mystyle-py.py <repo>` | findings panel |
| `mystyle-js` | `python3 scripts/mystyle-js.py <repo>` (needs tree-sitter) | findings panel |

Python analysis tools live in `.sunlit/venv` (created on first Python test/coverage run). Dev requirements: `pip install -r requirements-dev.txt`.

The `mystyle-*` linter scripts are vendored into `scripts/` (dev) and
`resources/scripts/` (bundled into the uberjar) from the author's local Codex
skills; `mystyle-js` additionally needs `tree-sitter`,
`tree-sitter-javascript`, and `tree-sitter-typescript` installed for the
interpreter in use.

## Locations

A location is `path#anchor`, where the anchor is a line (`42`, `L42`), a range
(`40-60`) or a symbol. Symbols resolve heuristically per language
(`codestory.locate`, no parsing): the least-indented matching definition wins,
then the first whole-word mention, then the first literal mention. Qualified
names (`Shape.area`, `Shape::area`) look inside the container's block; free
text such as `defmethod area :circle` works through the literal fallback.
Client routes: `#/file/<path>?line=40-60` and `#/file/<path>?sym=name`.

## Matrix plugin

For code whose shape is a product of dimensions, which a file tree can only
group one way. Put a fence on any page or annotation:

````markdown
```plugin matrix
{:title "Shapes"
 :dims {:type ["circle" "square" "triangle"]
        :operation ["area" "draw" "serialize"]}
 :pattern ["handlers/{type}/{operation}.clj"
           "handlers/default/{operation}.clj"]
 :manual [["circle" "draw" "src/render.clj#draw-circle"]
          ["triangle" "serialize" :n/a]]}
```
````

- `:dims`: dimension → values, in order. `:auto` derives the values from files
  matching the pattern.
- `:pattern`: one string or several, tried in order. `{dim}` binds a dimension,
  `*`/`**` are globs, and `#anchor` (which can use `{dim}` too, e.g.
  `"src/shapes.clj#{operation}-{type}"`) targets a symbol inside the file. A
  pattern that binds only some of the dims marks its cells *inherited* (a
  shared default).
- `:check`: user code for criteria that one path can't express (a
  combination satisfied by several things across several files). It is
  either an inline `(fn [coords] …)` or a path to a file under `.sunlit/`
  (e.g. `"checks/ui.clj"`) whose last form is the fn. `coords` looks like
  `{:app "editor" :target "js"}`. Return `{:status :ok}` or
  `{:status :missing :warn "…"}`, optionally with `:links ["path#anchor" …]`
  and `:note` (`true`/`false`/`nil` also work). `:check` overrides
  `:pattern`, and `:manual` overrides both.
- `:manual`: `[coord… value]` per cell, with coords in `:dims` order. The value
  is a link, a status (`:done`, `:todo`, `:n/a`), or `{:link :status :note}`.
  Manual entries override pattern matches.
- `:rows` / `:cols`: the axes. Any remaining dims become slice tabs, plus a
  Summary tab.

### Checklist mode

Leave out both `:pattern` and `:check` and the matrix is a plain checklist.
Open cells show as *todo* rather than *missing*, and each cell has a checkbox.
Ticking it records `:done` in `:manual`; unticking clears it again. The ✎
editor still works for links, notes and `:n/a`.

````markdown
```plugin matrix
{:title "Release checklist"
 :dims {:step ["changelog" "tag" "build" "announce"]
        :platform ["linux" "mac" "windows"]}
 :manual []}
```
````

### A full example with an inline check

````markdown
```plugin matrix
{:title "Apps × targets"
 :dims {:app ["editor" "viewer" "player"]
        :target ["js" "cpp" "hl"]}
 :rows :app
 :cols :target
 ;; Called once per (app, target) cell, e.g. {:app "editor" :target "js"}.
 ;; A cell counts as supported when the app's sources contain an
 ;; `#if <target>` block, in any file.
 :check (fn [{:keys [app target]}]
          (let [hits (grep (re-pattern (str "#if\\s+" target))
                           (str "src/" app "/**/*.hx"))]
            (if (seq hits)
              {:status :ok
               :links (map #(link (:path %) (:line %)) hits)}
              {:status :missing
               :warn (str "no #if " target " block under src/" app "/")})))
 :manual [["player" "hl" :n/a]]}
```
````

### What the check function receives

The matrix's cells are every combination of the `:dims` values (the
cartesian product, after any `:auto` dims are filled in from the pattern).
`:check` is called once per cell, with a map from each dimension name, as a
keyword, to that cell's value as a string. The example above has 3 apps × 3
targets = 9 cells, so 9 calls, the first being `{:app "editor" :target "js"}`.

- Cells where `:manual` gives a status or a link are already decided, so the
  check is not called for them. A note on its own does not count.
- There is no memory between calls, and the checks run again on every render
  of the page, so keep each one cheap. File reads and globs are cached for the
  duration of a render.
- With 3 or more dims, every cell in every slice tab is checked, not just the
  visible tab.

Cell statuses: done, inherited, todo, missing, broken (a manual link that
doesn't resolve), error (a `:check` that threw, timed out or returned
nonsense), n/a (deliberately absent). The issues list shows missing
cells with their expected path, plus *orphans*: files matching a pattern
whose coordinates are not in `:dims`.

### Check code sandbox

`:check` code runs in SCI with no Java interop and no `slurp`/`spit`. It
sees the repo only through read-only helpers (`codestory.sandbox`); the
`.sunlit/` sidecar and ignored directories are invisible to it:

| Helper | Returns |
|---|---|
| `(files)` / `(files "src/**/*.hx")` | project paths, optionally filtered by a glob |
| `(exists? path)` | boolean |
| `(slurp-file path)` / `(lines path)` | text or a vector of lines; `nil` for binary or missing files |
| `(grep pattern)` / `(grep pattern glob)` | `[{:path :line :text}]`; pattern is `#"regex"` or a literal string |
| `(find-symbol path sym)` | `{:line :end :exact}` or `nil` |
| `(link path anchor)` | `"path#anchor"` |

`clojure.string` and the rest of SCI's core are available. File reads are
cached for one render. All checks in one matrix share a 10 s budget. Cells
left when it runs out become errors; a runaway loop can't be interrupted,
so its thread is abandoned.

```clojure
;; .sunlit/checks/targets.clj
(fn [{:keys [app target]}]
  (let [hits (grep (re-pattern (str "#if\\s+" target)) (str "src/" app "/**/*.hx"))]
    (if (seq hits)
      {:status :ok :links (map #(link (:path %) (:line %)) hits)}
      {:status :missing :warn (str "no #if " target " block in " app)})))
```

Editing cells (✎ or double-click) and changing the axes in the grid UI rewrite
the fence's own EDN in the sidecar page/annotation. That is the general
rule: a plugin with an `:update (fn [params action] new-params)` may rewrite
its own fence (`POST /api/fence`, `codestory.fences`), and nothing in the
watched repository is ever modified. Only the entries that changed are
rewritten (rewrite-clj), so comments, layout and inline `:check` code
elsewhere in the fence are kept.

## Conventions

A **Conventions** page (nav item) checks the repo against a declared layout
template — required directories, required files, and structured-document
formats — and reports what matches and what doesn't. It also renders a
lightweight issue tracker from structured markdown issues.

The template lives in **`resources/template.edn`** (version-controlled and
editable). `scripts/install-local` copies it to **`~/.sunlit/template.edn`**,
the single shared standard the running app reads. The same file is bundled
into `clearing.jar` as a fallback when `~/.sunlit/template.edn` is missing.

Template precedence (first found wins):

1. `<repo>/.sunlit/template.edn` — emergency per-project override (avoid)
2. `~/.sunlit/template.edn` — the shared standard (installed by scripts/install-local)
3. `resources/template.edn` — bundled fallback on the classpath

Template shape (EDN):

```clojure
{:title "Standard project layout"
 :directories [{:path "src"    :required true  :description "Primary source"}
               {:path "issues" :required true  :description "Issue tracker"}]
 :files       [{:path "README.md" :required true :description "Readme"}]
 :gitignores  [{:pattern ".sunlit/cache/" :required true :description "Ignore plugin/session cache"}
               {:pattern ".sunlit/venv/"  :required true :description "Ignore analysis venv"}]
 :formats     [{:name "issue"
                :match "issues/*.md"      ; glob (* within a segment, ** across)
                :filename "^\\d{4}-[A-Za-z0-9._-]+\\.md$" ; optional name check
                :tracker? true            ; also render as an issue list
                :fields [{:key "status" :required true :enum ["open" "closed"]}
                         {:key "labels" :required true :multiple true}
                         {:key "created" :required true :pattern "^\\d{4}-\\d{2}-\\d{2}$"}]}]}
```

Field options: `:required` (must be present), `:enum` (value must be one of),
`:multiple` (value is a list; each item checked against `:enum` when one is
given), and `:pattern` (regex the scalar value must match). A format's
`:filename` regex checks each matched file's name; `:id`-vs-filename-number is
also cross-checked.

Labels are **free-form** by default: each project's categories are *derived*
from the issues present and shown on the Conventions page. To enforce a fixed
vocabulary for a particular project, add `:enum ["a" "b" …]` to the `labels`
field in that repo's `.sunlit/template.edn`.

Issues are `NNNN-short-slug.md` files in `issues/` with YAML front matter:

```markdown
---
id: 8
title: "if branches that are different variants of one sum type do not join"
status: open
labels: [front-end]
created: 2026-10-17
---

Description…

## Comments

### 2026-10-18 14:02

Later findings, a fix, or why it was closed.
```

`id`, `title`, `status` (`open`/`closed`), `labels`, and `created` are
required; `closed` and `git-bug` are optional. The tracker lists issues by
id with status, title, labels, and a link to the file.

## Annotation & pages

```
.sunlit/
  annotations/     # sidecar notes for repo files
  pages/           # free-titled wiki pages (_index = tree notes)
  cache/plugins/   # long-running plugin outputs
  plugins/         # optional SCI project plugins
  template.edn     # optional per-repo conventions override
  venv/            # private Python for pytest / coverage
```

### Version control

`.sunlit/` mixes authored content with generated data. Commit the authored
parts (`annotations/`, `pages/`, `plugins/`, and a per-repo `template.edn`),
and ignore the generated parts. Add these lines to the watched repo's
`.gitignore`:

```gitignore
.sunlit/cache/
.sunlit/venv/
```

The **Conventions** page checks for these two ignore rules.

Sunlit Clearing shells out to `scripts/sessions_bridge.py`, which uses
`eyeball_shared` from eyeball_kid. Prefer the shared venv:

```bash
export CODESTORY_PYTHON=~/.main_python_venv/bin/python
```

If eyeball is not installed, the Sessions page shows an error instead of failing the whole app.

## Non-goals (v1)

- Editing source files
- Git integration
- GraphQL
- Live file watching (eyeball watch) — later
- LLM analysis — later
