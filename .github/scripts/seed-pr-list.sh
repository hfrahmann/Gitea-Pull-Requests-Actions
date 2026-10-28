#!/usr/bin/env bash
# Seeds intellij-dev/pr-list-playground with many pull requests for the PR list: paging and filters.
# No workflows in this repo, so no Actions runs are triggered. Re-running only adds the missing PRs.
# Usage: .github/scripts/seed-pr-list.sh [count]   (default 220)
set -euo pipefail

cd "$(dirname "$0")"
set -a; . "${GITEA_TEST_ENV:-../../gitea-test.local.env}"; set +a  # default: repository root; GITEA_TEST_ENV: absolute path (CI)

API="$GITEA_URL/api/v1"
OWNER=intellij-dev
REPO=pr-list-playground
COUNT=${1:-220}

dev()      { curl -sS -H "Authorization: token $INTELLIJ_DEV_TOKEN"      -H 'Content-Type: application/json' "$@"; }
reviewer() { curl -sS -H "Authorization: token $INTELLIJ_REVIEWER_TOKEN" -H 'Content-Type: application/json' "$@"; }
b64()      { base64 | tr -d '\n'; }
field()    { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

echo "== repo, collaborator, labels, milestone"
dev -o /dev/null -w 'repo %{http_code}\n' -X POST "$API/user/repos" \
  -d "{\"name\":\"$REPO\",\"auto_init\":true,\"default_branch\":\"main\",\"private\":true,\"description\":\"Many pull requests for the PR list\"}"
dev -o /dev/null -w 'collaborator %{http_code}\n' -X PUT "$API/repos/$OWNER/$REPO/collaborators/intellij-reviewer" -d '{"permission":"write"}'
# Gitea allows duplicate label/milestone names, so only create what's missing
existing_labels=$(dev "$API/repos/$OWNER/$REPO/labels" | field '" ".join(l["name"] for l in d)')
for l in "bug:#ee0701" "feature:#0e8a16" "docs:#1d76db"; do
  [[ " $existing_labels " == *" ${l%%:*} "* ]] && continue
  dev -o /dev/null -X POST "$API/repos/$OWNER/$REPO/labels" -d "{\"name\":\"${l%%:*}\",\"color\":\"${l#*:}\"}"
done
if [ "$(dev "$API/repos/$OWNER/$REPO/milestones?state=all" | field 'len(d)')" = 0 ]; then
  dev -o /dev/null -X POST "$API/repos/$OWNER/$REPO/milestones" -d '{"title":"v1.0"}'
fi
label_id() { dev "$API/repos/$OWNER/$REPO/labels" | field "min(l['id'] for l in d if l['name'] == '$1')"; }
BUG=$(label_id bug); FEATURE=$(label_id feature); DOCS=$(label_id docs)
MILESTONE=$(dev "$API/repos/$OWNER/$REPO/milestones?state=all" | field 'min(m["id"] for m in d)')

EXISTING=$(dev -o /dev/null -D - "$API/repos/$OWNER/$REPO/pulls?state=all&limit=1" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-total-count"{print $2}')
EXISTING=${EXISTING:-0}
echo "== pull requests: $EXISTING exist, target $COUNT"

WORDS=(parser cache login avatar timeline search settings toolbar merge review)
for ((i = EXISTING + 1; i <= COUNT; i++)); do
  word=${WORDS[$((i % ${#WORDS[@]}))]}
  if ((i % 3 == 0)); then as=reviewer; other=intellij-dev; else as=dev; other=intellij-reviewer; fi
  branch="change/$i-$word"
  content=$(printf 'change %s for %s\n' "$i" "$word" | b64)
  $as -o /dev/null -X POST "$API/repos/$OWNER/$REPO/contents/changes/$i.txt" \
    -d "{\"branch\":\"main\",\"new_branch\":\"$branch\",\"message\":\"Change $i\",\"content\":\"$content\"}"

  title="Improve $word handling ($i)"
  ((i % 10 == 0)) && title="WIP: $title"
  labels=()
  ((i % 4 == 1)) && labels+=("$BUG")
  ((i % 4 == 2)) && labels+=("$FEATURE")
  ((i % 8 == 3)) && labels+=("$DOCS")
  assignees=()
  ((i % 5 == 0)) && assignees+=("\"intellij-reviewer\"")
  ((i % 7 == 0)) && assignees+=("\"intellij-dev\"")
  reviewers=()
  ((i % 6 == 0 || i % 6 == 1)) && reviewers+=("\"$other\"")
  milestone=""; ((i % 9 == 0)) && milestone=",\"milestone\":$MILESTONE"
  body=$(IFS=,; echo "{\"title\":\"$title\",\"head\":\"$branch\",\"base\":\"main\",\"body\":\"Seeded PR $i about $word.\",\"labels\":[${labels[*]:-}],\"assignees\":[${assignees[*]:-}],\"reviewers\":[${reviewers[*]:-}]$milestone}")
  number=$($as -X POST "$API/repos/$OWNER/$REPO/pulls" -d "$body" | field 'd["number"]')

  if ((i % 13 == 0)); then
    for _ in 1 2 3 4 5; do
      code=$(dev -o /dev/null -w '%{http_code}' -X POST "$API/repos/$OWNER/$REPO/pulls/$number/merge" -d '{"Do":"squash"}')
      [ "$code" = 200 ] && break; python3 -c 'import time; time.sleep(1)'
    done
  elif ((i % 11 == 0)); then
    dev -o /dev/null -X PATCH "$API/repos/$OWNER/$REPO/pulls/$number" -d '{"state":"closed"}'
  fi
  ((i % 20 == 0)) && echo "  $i"
done

echo "Done: $GITEA_URL/$OWNER/$REPO/pulls"
