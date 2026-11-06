# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.3] - 2026-11-06

### Added

- Errors: exceptions and freezes caused by the plugin can be reported to the developer through JetBrains Marketplace, from
  the IDE's "Internal Errors" dialog.

### Fixed

- Login: a wrong token on a working Gitea server said "No Gitea API found" instead of saying that the token is invalid. The
  "Test Connection" button and the accounts list had the same problem, and the accounts list now offers to log in again.
- IntelliJ IDEA 2026.3: the plugin failed with "NoClassDefFoundError: com/fasterxml/jackson/databind/json/JsonMapper" at
  startup and could not load accounts or pull requests.
- Login via browser from the settings (IntelliJ IDEA 2026.2 and later): the new account disappeared when you returned
  from the browser, so the login seemed to do nothing.
- Login: a login that fails now always says why. Before, it ended without any message when the browser login timed out
  or when the plugin hit an error of its own, and a refused connection only showed "ConnectException".

## [1.0.2] - 2026-11-04

### Added

- Pull request list: a "Draft" filter hides draft (WIP) pull requests or shows only them.
- Pull request files: "Open in Browser" in the context menu opens the selected file in the diff on Gitea, of the pull
  request or the selected commit.

### Fixed

- Review: "Request changes" without review text failed with "review event REQUEST_CHANGES requires a body", also with
  inline comments (unlike the Gitea web UI, the API needs a text). "Comment" without text and inline comments failed
  with "review requires a body or a comment". The submit popup now asks for the text instead.

## [1.0.1] - 2026-11-01

### Added

- Pull requests: a dot marks open pull requests you authored, review or are assigned to that changed since you last
  opened them, in the list and on the tool window icon (like GitHub). The list is also refreshed in the background.

### Changed

- Pull request timeline: texts are limited to a readable width again, like in the GitHub and GitLab plugins.

### Fixed

- Pull request details: a pull request without commits (e.g. closed after its branch was merged elsewhere) showed
  "Could not load the changes – List is empty." It now shows "No changes between `branch` and `base`", like GitHub and
  GitLab.

## [1.0.0] - 2026-10-28

### Added

- Accounts: several Gitea servers, login with OAuth (browser) or access token, git HTTPS authentication with the
  account's token.
- Clone from Gitea, share a project on Gitea, open and copy links to files, lines, commits and annotations.
- Pull requests tool window: list with filters and search, details, commits and changes with viewed state, timeline
  with replies, edits and reactions.
- Code review in the diff and in the editor: inline comments, suggested changes, pending reviews submitted as approve,
  request changes or comment.
- Create, check out, merge (merge, rebase, squash, fast-forward, auto-merge), close, reopen, draft and update pull
  requests; branch widget and push notifications for the current branch's pull request.
- Gitea Actions tool window: runs of the current branch, the workflows and pull requests; jobs, steps and live logs;
  re-run, cancel, artifacts and run logs download; status bar widget and finish notifications.
- Run workflows on Gitea (`workflow_dispatch`) or locally with `gitea-runner exec`, from the tool window or the
  workflow file in the editor.
- Repository secrets and variables.
- Gitea issues as tasks.
- Gitea logo on workflow files and on `.gitea` folders in the project view.
