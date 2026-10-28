#!/usr/bin/env bash
# Copies the Plugin Verifier's HTML report of each IDE (build/reports/pluginVerifier/<IDE>/report.html) to
# build/verifier-upload/ with the IDE and its build in the file name, for the CI artifact upload.
#
#   .github/scripts/verifier-reports.sh     e.g. plugin-verifier-report-IU-263.html, plugin-verifier-report-PY-261.html
set -euo pipefail

mkdir -p build/verifier-upload
for report in build/reports/pluginVerifier/*/report.html; do
  if [ ! -f "$report" ]; then
    echo "No verifier reports"
    exit 0
  fi
  # IU-263.7307.44 → IU-263
  ide=$(basename "$(dirname "$report")" | sed -E 's/^([A-Z]+-[0-9]+)\..*/\1/')
  cp "$report" "build/verifier-upload/plugin-verifier-report-${ide}.html"
  echo "Verifier report: build/verifier-upload/plugin-verifier-report-${ide}.html"
done
