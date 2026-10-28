#!/usr/bin/env bash
# Seeds the local test Gitea with a playground repository for plugin development.
# Requires gitea-test.local.env in the repository root (or GITEA_TEST_ENV, see ci-gitea.sh). Idempotent where the API allows.
set -euo pipefail

cd "$(dirname "$0")"
set -a; . "${GITEA_TEST_ENV:-../../gitea-test.local.env}"; set +a  # default: repository root; GITEA_TEST_ENV: absolute path (CI)

API="$GITEA_URL/api/v1"
OWNER=intellij-dev
REPO=plugin-playground

dev()      { curl -sS -H "Authorization: token $INTELLIJ_DEV_TOKEN"      -H 'Content-Type: application/json' "$@"; }
reviewer() { curl -sS -H "Authorization: token $INTELLIJ_REVIEWER_TOKEN" -H 'Content-Type: application/json' "$@"; }
b64()      { base64 | tr -d '\n'; }
json()     { python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'; }

echo "== repo"
dev -o /dev/null -w '%{http_code}\n' -X POST "$API/user/repos" \
  -d "{\"name\":\"$REPO\",\"auto_init\":true,\"default_branch\":\"main\",\"private\":true,\"description\":\"Playground for the Gitea IntelliJ plugin\"}"

echo "== collaborator"
dev -o /dev/null -w '%{http_code}\n' -X PUT "$API/repos/$OWNER/$REPO/collaborators/intellij-reviewer" -d '{"permission":"write"}'

put_file() { # branch path message content
  local sha
  sha=$(dev "$API/repos/$OWNER/$REPO/contents/$2?ref=$1" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("sha","") if isinstance(d,dict) else "")')
  local content; content=$(printf '%s' "$4" | b64)
  if [ -n "$sha" ]; then
    dev -o /dev/null -w "$2 %{http_code}\n" -X PUT "$API/repos/$OWNER/$REPO/contents/$2" \
      -d "{\"branch\":\"$1\",\"message\":\"$3\",\"content\":\"$content\",\"sha\":\"$sha\"}"
  else
    dev -o /dev/null -w "$2 %{http_code}\n" -X POST "$API/repos/$OWNER/$REPO/contents/$2" \
      -d "{\"branch\":\"$1\",\"message\":\"$3\",\"content\":\"$content\"}"
  fi
}

echo "== main files"
put_file main src/Hello.kt "Add Hello.kt" 'fun main() {
    println("Hello Gitea")
}
'
put_file main .gitea/workflows/ci.yml "Add CI workflow" 'name: CI
on: [push, pull_request, workflow_dispatch]
jobs:
  build:
    runs-on: ubuntu-latest
    container:
      image: node:20-bookworm   # JS actions (upload-artifact) need node in the job image
    steps:
      - name: Say hello
        run: echo "hello from $GITHUB_REF"
      - name: Make artifact
        run: mkdir -p out && date > out/build-info.txt
      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: build-info
          path: out/
  flaky:
    runs-on: ubuntu-latest
    steps:
      - name: Fails on feature branches
        run: |
          echo "ref=$GITHUB_REF"
          case "$GITHUB_REF" in refs/heads/main) exit 0;; *) echo "::error::intentional failure"; exit 1;; esac
'

put_file main .gitea/workflows/inputs.yml "Add workflow with all dispatch input types" 'name: Inputs
# manual only: every workflow_dispatch input type, for the "Run Workflow" dialog
on:
  workflow_dispatch:
    inputs:
      greeting:
        description: "Free text (string, optional)"
        type: string
        default: "Hello"
      target:
        description: "Required text without default"
        required: true
        type: string
      dry-run:
        description: "Checkbox (boolean)"
        type: boolean
        default: true
      log-level:
        description: "Drop-down (choice)"
        required: true
        type: choice
        default: info
        options:
          - debug
          - info
          - warning
          - error
      retries:
        description: "Number"
        type: number
        default: 3
      environment:
        description: "Environment name"
        type: environment
        default: staging
      notes:
        description: |
          Multi-line description:
          no type given, so it is a string
      sleep-seconds:
        description: "Keep the job running this long, one log line per second (live log test)"
        type: number
        default: 0
jobs:
  show:
    runs-on: ubuntu-latest
    steps:
      - name: Print inputs
        run: |
          echo "greeting=${{ inputs.greeting }}"
          echo "target=${{ inputs.target }}"
          echo "dry-run=${{ inputs.dry-run }}"
          echo "log-level=${{ inputs.log-level }}"
          echo "retries=${{ inputs.retries }}"
          echo "environment=${{ inputs.environment }}"
          echo "notes=${{ inputs.notes }}"
      - name: Count
        run: |
          for i in $(seq 1 ${{ inputs.sleep-seconds }}); do echo "tick $i"; sleep 1; done
      - name: Fail on log level error
        run: |
          if [ "${{ inputs.log-level }}" = "error" ]; then echo "::error::log-level error requested"; exit 1; fi
          echo "::notice::all inputs printed"
'

echo "== feature branch"
dev -o /dev/null -w '%{http_code}\n' -X POST "$API/repos/$OWNER/$REPO/branches" -d '{"new_branch_name":"feature/greeting","old_branch_name":"main"}'
put_file feature/greeting src/Hello.kt "Personalise greeting" 'fun main(args: Array<String>) {
    val name = args.firstOrNull() ?: "Gitea"
    println("Hello $name")
}
'

echo "== pull request"
PR=$(dev "$API/repos/$OWNER/$REPO/pulls?state=open" | python3 -c 'import json,sys; l=[p["number"] for p in json.load(sys.stdin) if p["head"]["ref"]=="feature/greeting"]; print(l[0] if l else "")')
if [ -z "$PR" ]; then
  PR=$(dev -X POST "$API/repos/$OWNER/$REPO/pulls" \
    -d '{"title":"Personalise greeting","body":"Reads the name from the command line.\n\n- [ ] tests","head":"feature/greeting","base":"main"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["number"])')
fi
echo "PR #$PR"
dev -o /dev/null -w 'request review %{http_code}\n' -X POST "$API/repos/$OWNER/$REPO/pulls/$PR/requested_reviewers" -d '{"reviewers":["intellij-reviewer"]}'

echo "== review with inline comment (by intellij-reviewer)"
HEAD_SHA=$(dev "$API/repos/$OWNER/$REPO/pulls/$PR" | python3 -c 'import json,sys; print(json.load(sys.stdin)["head"]["sha"])')
reviewer -o /dev/null -w '%{http_code}\n' -X POST "$API/repos/$OWNER/$REPO/pulls/$PR/reviews" -d "{
  \"event\":\"REQUEST_CHANGES\",\"commit_id\":\"$HEAD_SHA\",\"body\":\"Looks good, one nit.\",
  \"comments\":[{\"path\":\"src/Hello.kt\",\"new_position\":2,\"body\":\"Maybe trim the name?\"}]}"

echo "== general comment"
dev -o /dev/null -w '%{http_code}\n' -X POST "$API/repos/$OWNER/$REPO/issues/$PR/comments" -d '{"body":"Thanks for the review!"}'

echo "== issues (Tasks integration)"
if ! dev "$API/repos/$OWNER/$REPO/labels" | grep -q '"name":"bug"'; then
  dev -o /dev/null -w 'label %{http_code}\n' -X POST "$API/repos/$OWNER/$REPO/labels" -d '{"name":"bug","color":"#ee0701"}'
fi
EXISTING=$(dev "$API/repos/$OWNER/$REPO/issues?type=issues&state=all&limit=50" | python3 -c 'import json,sys; print("\n".join(i["title"] for i in json.load(sys.stdin)))')
issue() { # title json
  if ! grep -qxF "$1" <<<"$EXISTING"; then dev -o /dev/null -w "issue %{http_code}\n" -X POST "$API/repos/$OWNER/$REPO/issues" -d "$2"; fi
}
issue "Greeting is shown twice" '{"title":"Greeting is shown twice","body":"Steps:\n1. Run the app\n2. See *two* greetings","assignees":["intellij-dev"]}'
issue "Add a farewell message" '{"title":"Add a farewell message","body":"Say goodbye on exit."}'
issue "Old typo in README" '{"title":"Old typo in README","body":"Fixed already.","closed":true}'
BUG=$(dev "$API/repos/$OWNER/$REPO/issues?type=issues&state=all&limit=50" | python3 -c 'import json,sys; l=[i["number"] for i in json.load(sys.stdin) if i["title"]=="Greeting is shown twice"]; print(l[0] if l else "")')
[ -n "$BUG" ] && dev -o /dev/null -w 'bug label %{http_code}\n' -X POST "$API/repos/$OWNER/$REPO/issues/$BUG/labels" -d '{"labels":["bug"]}'

echo "== organization (org-level secrets/variables)"
ORG=intellij-dev-org
dev -o /dev/null -w 'org %{http_code}\n' -X POST "$API/orgs" -d "{\"username\":\"$ORG\",\"visibility\":\"public\",\"description\":\"Org for org-level Actions secrets/variables\"}"
dev -o /dev/null -w 'org repo %{http_code}\n' -X POST "$API/orgs/$ORG/repos" -d '{"name":"org-playground","auto_init":true,"default_branch":"main"}'
# the reviewer is a plain member (write team): org secrets/variables must refuse them
TEAM=$(dev "$API/orgs/$ORG/teams" | python3 -c 'import json,sys; l=[t["id"] for t in json.load(sys.stdin) if t["name"]=="Developers"]; print(l[0] if l else "")')
if [ -z "$TEAM" ]; then
  TEAM=$(dev -X POST "$API/orgs/$ORG/teams" -d '{"name":"Developers","permission":"write","includes_all_repositories":true,"units":["repo.code","repo.actions","repo.pulls"]}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
fi
dev -o /dev/null -w 'team member %{http_code}\n' -X PUT "$API/teams/$TEAM/members/intellij-reviewer"

echo "== fork (PRs from forks)"
# 202 = created, 409 = exists already
reviewer -o /dev/null -w 'fork %{http_code}\n' -X POST "$API/repos/$OWNER/$REPO/forks" -d '{}'

echo "Done: $GITEA_URL/$OWNER/$REPO/pulls/$PR"
