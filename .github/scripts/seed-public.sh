#!/usr/bin/env bash
# Seeds the public repo intellij-dev/public-playground for anonymous read-only access:
# one PR with a general comment, an inline review comment and an image attachment. No workflows.
# Re-running is safe for repo/branch/PR, but adds another comment and review each time.
# Usage: .github/scripts/seed-public.sh
set -euo pipefail

cd "$(dirname "$0")"
set -a; . "${GITEA_TEST_ENV:-../../gitea-test.local.env}"; set +a  # default: repository root; GITEA_TEST_ENV: absolute path (CI)

API="$GITEA_URL/api/v1"
OWNER=intellij-dev
REPO=public-playground

dev()      { curl -sS -H "Authorization: token $INTELLIJ_DEV_TOKEN"      -H 'Content-Type: application/json' "$@"; }
reviewer() { curl -sS -H "Authorization: token $INTELLIJ_REVIEWER_TOKEN" -H 'Content-Type: application/json' "$@"; }
b64()      { base64 | tr -d '\n'; }
field()    { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

echo "== repo"
dev -o /dev/null -w 'repo %{http_code}\n' -X POST "$API/user/repos" \
  -d "{\"name\":\"$REPO\",\"auto_init\":true,\"default_branch\":\"main\",\"private\":false,\"description\":\"Public repo for anonymous access\"}"

echo "== branch and PR"
content=$(printf 'fun main() {\n  println("Hello, public Gitea")\n}\n' | b64)
dev -o /dev/null -w 'file %{http_code}\n' -X POST "$API/repos/$OWNER/$REPO/contents/src/Main.kt" \
  -d "{\"branch\":\"main\",\"new_branch\":\"feature/public\",\"message\":\"Add Main.kt\",\"content\":\"$content\"}"
number=$(dev "$API/repos/$OWNER/$REPO/pulls?state=open" | field 'd[0]["number"] if d else ""')
if [ -z "$number" ]; then
  number=$(dev -X POST "$API/repos/$OWNER/$REPO/pulls" \
    -d '{"title":"Add a public main","head":"feature/public","base":"main","body":"Readable without an account."}' | field 'd["number"]')
fi
echo "PR #$number"

echo "== comment with image, inline review"
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDATx\x9cc\xf8\xcf\xc0\xf0\x1f\x00\x05\x00\x01\xff\x89\x99=\x1d\x00\x00\x00\x00IEND\xaeB`\x82' > /tmp/gitea-public-pixel.png
url=$(curl -sS -H "Authorization: token $INTELLIJ_DEV_TOKEN" -F "attachment=@/tmp/gitea-public-pixel.png" \
  "$API/repos/$OWNER/$REPO/issues/$number/assets" | field 'd["browser_download_url"]')
rm -f /tmp/gitea-public-pixel.png
dev -o /dev/null -w 'comment %{http_code}\n' -X POST "$API/repos/$OWNER/$REPO/issues/$number/comments" \
  -d "{\"body\":\"A comment with an image: ![pixel]($url)\"}"
reviewer -o /dev/null -w 'review %{http_code}\n' -X POST "$API/repos/$OWNER/$REPO/pulls/$number/reviews" \
  -d '{"event":"COMMENT","body":"Looks fine.","comments":[{"path":"src/Main.kt","new_position":2,"body":"Maybe a constant?"}]}'

echo "Done: $GITEA_URL/$OWNER/$REPO/pulls/$number"
