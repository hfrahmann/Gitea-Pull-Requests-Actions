#!/usr/bin/env bash
# Creates the release v<version> for the current commit and attaches the plugin zip – through the REST API, so it works
# on GitHub and on Gitea Actions alike (GITEA_ACTIONS=true; the API differs only in the asset upload).
#
#   RELEASE_TOKEN=… .github/scripts/create-release.sh <version> <notes file> <zip> [draft] [prerelease]
#
# Repository, commit and API URL come from the runner (GITHUB_REPOSITORY, GITHUB_SHA, GITHUB_API_URL). Fails when the
# release already exists.
set -euo pipefail

version=$1
notes_file=$2
zip=$3
draft=${4:-false}
prerelease=${5:-false}

tag="v${version}"
api="${GITHUB_API_URL}/repos/${GITHUB_REPOSITORY}"
auth="Authorization: token ${RELEASE_TOKEN}"

status=$(curl -sS -o /dev/null -w '%{http_code}' -H "$auth" "${api}/releases/tags/${tag}")
if [ "$status" = "200" ]; then
  echo "Release ${tag} already exists" >&2
  exit 1
fi

payload=$(jq -n \
  --arg tag "$tag" \
  --arg sha "$GITHUB_SHA" \
  --arg name "$version" \
  --rawfile body "$notes_file" \
  --argjson draft "$draft" \
  --argjson prerelease "$prerelease" \
  '{tag_name: $tag, target_commitish: $sha, name: $name, body: $body, draft: $draft, prerelease: $prerelease}')

release=$(curl -sS --fail-with-body -X POST -H "$auth" -H "Content-Type: application/json" -d "$payload" "${api}/releases")
id=$(jq -r '.id' <<<"$release")
echo "Created release ${tag} (id ${id})"

name=$(basename "$zip")
if [ "${GITEA_ACTIONS:-}" = "true" ]; then
  # Gitea: multipart form field "attachment"
  curl -sS --fail-with-body -X POST -H "$auth" -F "attachment=@${zip};type=application/zip" \
    "${api}/releases/${id}/assets?name=${name}" >/dev/null
else
  # GitHub: raw body to the uploads host from upload_url (".../assets{?name,label}")
  upload_url=$(jq -r '.upload_url' <<<"$release" | sed 's/{.*}//')
  curl -sS --fail-with-body -X POST -H "$auth" -H "Content-Type: application/zip" --data-binary "@${zip}" \
    "${upload_url}?name=${name}" >/dev/null
fi
echo "Attached ${name}"
echo "$(jq -r '.html_url' <<<"$release")"
