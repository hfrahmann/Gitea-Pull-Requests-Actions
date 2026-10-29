# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.1] - 2026-10-29

### Changed

- Pull request timeline: texts are limited to a readable width again, like in the GitHub and GitLab plugins.

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
