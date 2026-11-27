#!/bin/sh
# Builds the search data for a hosted server (Render runs this while building the image).
# The data folder is git-ignored, so a fresh copy of the project has none.
#
#   1. real shops and EV chargers from OpenStreetMap (ODbL, credited on the page)
#   2. the Natural Resources Canada guides (the one crawled site; reuse allowed with credit)
#   3. the invented test pages (hidden on the page whenever real pages exist)
#   4. parse everything into the searchable index
#
# A download that fails does not stop the build: the site then starts with whatever
# did download, and at worst with the invented pages only.
set -u
CP="target/classes:target/dependency/*"
run() { java -cp "$CP" ca.carcareplus.app.Main "$@"; }
# A public download server that hangs must not hold up the build forever
limited() { seconds=$1; shift; if command -v timeout >/dev/null 2>&1; then timeout "$seconds" "$@"; else "$@"; fi; }

if [ "${SKIP_OSM:-}" = "1" ]; then
  echo "build-data: SKIP_OSM=1, not downloading OpenStreetMap"
else
  limited 900 java -cp "$CP" ca.carcareplus.app.Main osm \
    || echo "build-data: OpenStreetMap download failed or took over 15 minutes; continuing without it"
fi
run crawl "Natural Resources Canada" || echo "build-data: crawl failed; continuing without it"
# These two must work, or the site would have nothing to search: stop the build instead
run fixtures || exit 1
run parse || exit 1
