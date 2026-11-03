---
name: sunlit
description: Use Sunlit Clearing to inspect a repository's conventions, architecture, testing, directory tree, git status, and custom project plugins through the local Sunlit web server, including the machine-readable /agents/ JSON endpoints.
---

# Sunlit Clearing

Use this skill when the user asks to inspect a repository through Sunlit Clearing, check project conventions, read the repository's Sunlit overview pages, understand its testing setup, or work with custom project plugins.

## Account-wide conventions

Before assessing whether a repository follows the common project layout, read `~/.sunlit/template.edn`. This is the account-wide standard. Treat it as the source of truth; do not edit it as part of an inspection. If a user asks to change it, first establish whether it is a copied/installed file and follow their stated ownership instructions.

Interpret its sections as follows:

- `:title` names the standard.
- `:directories` and `:files` enumerate expected paths. `:required true` means missing is a convention failure; `false` is optional. Descriptions explain the intended use.
- `:formats` defines document conventions. For each format, inspect `:match`, filename regex, and field requirements, including `:enum`, `:multiple`, and `:pattern`. For the current `issue` format, this means markdown files matching `issues/*.md`, named `NNNN-short-slug.md`, with YAML front matter fields `id`, `title`, `status`, `labels`, and `created`; status is `open` or `closed`, and dates follow `YYYY-MM-DD`. `closed` and `git-bug` are optional.

Do not hard-code the current template as permanent truth: reread the file each time and derive the expected layout and formats from its current contents. A project may have `.sunlit/template.edn` as an emergency override; if present, note it and compare it with the global standard. The global template comments say to prefer the shared standard.

## Connect to the local server

Sunlit serves the repository selected by its running instance. Never assume or guess a port number, including `7070` or any port shown as an example. If the user has not supplied the port for the instance they want inspected, ask them for it before making a connection attempt. Once they provide it, use `http://localhost:<port>/`. Do not try ports discovered in old logs, processes, or prior context as substitutes for the user's answer. Do not assume the instance serves the current working directory: verify the repository path from `/api/tree` and make sure it is the intended project before reporting results.

The browser UI is a single-page app. Routes include:

- `/#/` — repository tree (with git-status tints) and its `_index` overview.
- `/#/dir/<path>` — a directory page: the same collapsible tree widget, rooted at that directory.
- `/#/file/<path>` — a file. Markdown files open with Source / Rendered tabs.
- `/#/conventions` — Conventions report.
- `/#/page/Architecture` — Architecture wiki page (structure-scan plugin boxes are usually embedded here).
- `/#/testing` — Testing page (runs the `unittests` and `projectlint` plugins; not a wiki page).
- `/#/lint` — Lint page (shows the `mystyle-clj`, `mystyle-py`, and `mystyle-js` style-linter panels; not a wiki page).
- `/#/plugins` — available built-in and repository plugins.
- `/#/sessions` — cached agent sessions.

The `Architecture` page is repository-specific and may be missing or empty. Check the index page (`/api/pages/_index`) and then fetch the named page (`/api/pages/Architecture`). Treat what the pages actually contain as authoritative; do not invent architecture details or test outcomes.

## Read-only API workflow

Use HTTP GET requests to inspect the local server. Do not use save/create endpoints unless the user explicitly asks to edit Sunlit pages or annotations.

1. Check `GET /api/health` to confirm the server responds.
2. Check `GET /api/tree`; report the repository path and name. If it is not the intended repo, find the correct Sunlit port before continuing. Tree nodes carry `:git-status` (`:tracked` / `:untracked` / `:ignored`) when the repo is a git work tree.
3. Check `GET /api/conventions` for the evaluated report. It includes required and optional path presence, format validity, issue counts, and project metadata when available. This report is a useful rendered evaluation, but use `~/.sunlit/template.edn` to explain the actual current requirements.
4. Check `GET /api/pages/_index` for links, then `GET /api/pages/Architecture` for that page. A page response indicates whether it exists; distinguish a missing page from a page with no useful content.
5. Check `GET /api/dir?path=<relative-path>` for a directory's nested `:children`.
6. When needed, inspect `GET /api/plugins` to learn which analysis plugins the server has loaded (built-in and repo). Treat plugin names as availability, not proof that a check ran or passed.

The Testing page is not a wiki page: `/#/testing` runs the `unittests` plugin (auto-detects clojure/pytest/haxe) and the `projectlint` plugin (runs `projectlint.sh` + `project_lint/` when present) and shows their panels.

The Lint page (`/#/lint`) is likewise not a wiki page: it shows the three
`mystyle-*` style linters (`mystyle-clj`, `mystyle-py`, `mystyle-js`) as cached,
long-running plugin panels with Rerun buttons. `mystyle-js` requires
`tree-sitter`, `tree-sitter-javascript`, and `tree-sitter-typescript` installed
for the Python interpreter in use.

## Machine-readable /agents/ endpoints

Prefer these JSON endpoints over scraping the HTML UI. They return the same
data the browser renders, in machine-readable form:

- `GET /agents/` — index: repo, plugin catalog, page list, endpoint list.
- `GET /agents/tree` — tree, `_index` markdown, and its plugin results.
- `GET /agents/page/<name>` — a page's markdown plus each embedded plugin result.
- `GET /agents/file/<path>` — file content + annotation + auto plugins.
- `GET /agents/plugin/<name>` and `GET /agents/plugin/<name>/<id>` — one plugin result.
- `GET /agents/conventions` — the conventions report as JSON (no HTML).

Long-running plugins are stale-while-revalidate. Each result carries `status`,
`running`, `started_at`, `finished_at` (the datestamp of the cached value),
`age_seconds`, and `stale`. When the cache is `never`/`error` or older than the
TTL, the request also launches a background refresh; add `?refresh=1` to force
one. After a launch, re-request the same URL to read the fresh result.

Plugin results carry `data` (structured), `text`/`markdown` (plain/markdown),
and `html` (human panel) when the plugin provides them. Prefer `data` or
`text`; fall back to `html`/`stdout` when a plugin only ships those.

## Git status and generated code

When the repo is a git work tree, Sunlit colours tree rows by git status and exposes the same information to plugins:

- `:tracked` — normal.
- `:untracked` — faint amber tint.
- `:ignored` — dimmed.

The Haxe (`structure-haxe`) and C/C++ (`structure-cpp`) structure scans ignore files that are not git-tracked, on the assumption they are generated. On a repo that is not a git repository, those scans keep everything.

## Custom plugins

Sunlit loads per-project plugins from `.sunlit/plugins/*.clj` (sandboxed via SCI) at startup. See `references/custom-plugins.md` for the plugin shape, the host API, and how to add one. Plugins should return `:data` and `:text` (machine-readable) alongside `:html` so the `/agents/` endpoints are useful without parsing HTML.

## Reporting

Summarize findings in useful groups: required items missing, optional items absent, invalid format documents, and satisfied requirements. Include the server's repository path so it is clear which project was inspected. For Architecture and Testing, summarize the page or panel content and call out when a page is absent, empty, or stale/unclear. Testing results shown in a page are evidence only for the run or date stated there; do not imply tests were run just because a Testing page exists.

Do not alter repository files, the global template, or Sunlit data during an inspection. If the user asks to bring a project into convention, inspect first and make the requested changes only in that project, preserving existing content where possible.
