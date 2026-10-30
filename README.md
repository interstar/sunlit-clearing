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
- Plugin fences in annotations:

````markdown
```plugin leiningen
{}
```
````

- Built-in **leiningen** plugin (auto panel on `project.clj`, also via fence)
- Built-in **malli** / **instaparse** plugins (scan `src/` for schemas & grammars)
- Optional user plugins as SCI scripts in `.sunlit/plugins/*.clj`
- **Plugins** page listing built-in and repo plugins (with descriptions)
- **Conventions** page: check the repo against a declared layout template
  (directories, files, structured-document formats) + a lightweight issues list
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
 :run (fn [{:keys [path params repo-root]}] {:html "…"})}
```

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

Python analysis tools live in `.sunlit/venv` (created on first Python test/coverage run). Dev requirements: `pip install -r requirements-dev.txt`.

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
