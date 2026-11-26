#!/usr/bin/env python3
"""Find BibleProject videos nobody has hand-checked yet (run weekly by bibleproject-videos.yml).

The app's BibleProject list (data/model/BookResources.kt, #407) is hand-checked and compiled in,
so a new video only reaches readers when someone checks it and adds it. This script downloads
only bibleproject.com's sitemap (a list of page URLs, never page content: third-party content is
link-only), takes the /videos/<slug>/ pages and prints the slugs that are not in
docs/bibleproject-videos-reviewed.txt. It never edits the app's data.

Standard library only, no API keys. Usage:
  check_bibleproject_videos.py --reviewed docs/bibleproject-videos-reviewed.txt
      [--sitemap-file FILE] [--already-listed FILE] [--issue-body FILE] [--comment]
  check_bibleproject_videos.py --self-test
"""

import argparse
import os
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

SITEMAP_URL = "https://bibleproject.com/en/sitemap.xml"
SITEMAP_NS = "{http://www.sitemaps.org/schemas/sitemap/0.9}"
# One path segment after /videos/, so /videos/collections/<name>/ never matches.
VIDEO_URL = re.compile(r"^https://bibleproject\.com/(?:en/)?videos/([a-z0-9][a-z0-9-]*)/?$")
NOT_VIDEOS = {"all", "collections"}
# The sitemap had 264 videos on 2026-11-25. Far fewer means its format changed, and the check
# would otherwise pass quietly while finding nothing.
MIN_VIDEOS = 100
REPO = os.environ.get("GITHUB_REPOSITORY", "Daniel-Pino-2000/TreasuredWord")


def fetch_sitemap(url=SITEMAP_URL, attempts=3):
    request = urllib.request.Request(url, headers={"User-Agent": "TreasuredWord-video-check"})
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except OSError as error:
            if attempt == attempts:
                raise
            print(f"Sitemap download failed ({error}), retrying", file=sys.stderr)
            time.sleep(10 * attempt)


def video_slugs(sitemap_xml):
    root = ET.fromstring(sitemap_xml)
    slugs = set()
    for loc in root.iter(f"{SITEMAP_NS}loc"):
        match = VIDEO_URL.match((loc.text or "").strip())
        if match and match.group(1) not in NOT_VIDEOS:
            slugs.add(match.group(1))
    return slugs


def read_reviewed(text):
    """One slug per line; '#' starts a comment, so a line can say what happened to the video."""
    slugs = set()
    for line in text.splitlines():
        slug = line.split("#", 1)[0].strip()
        if slug:
            slugs.add(slug)
    return slugs


def not_yet_listed(slugs, issue_text):
    """Slugs the open issue (body and comments) doesn't link yet, so a weekly run never repeats one."""
    return sorted(s for s in slugs if f"/videos/{s}/" not in issue_text)


def issue_body(slugs, comment=False):
    lead = (
        "BibleProject published more videos since the last check:"
        if comment
        else "BibleProject published videos that nobody has checked for TreasuredWord yet:"
    )
    lines = [lead, ""]
    lines += [f"- [ ] [{s}](https://bibleproject.com/videos/{s}/)" for s in slugs]
    if not comment:
        lines += [
            "",
            "For each video, following CLAUDE.md's rule for hand-checked data "
            "(`app/src/main/java/com/application/bibleapp/data/model/BookResources.kt`):",
            "- [ ] BibleProject's own page names the passage or book it is about "
            "(no passage named means it stays out).",
            "- [ ] The YouTube ID matches: topic, runtime (YouTube runs up to ~25 s longer) and "
            "series episode.",
            "- [ ] YouTube oEmbed's `author_url` is `@bibleproject` "
            "(or `@ProyectoBibliaOficial` for the Spanish one).",
            "- [ ] Spanish version added if there is one (bibleproject.com/es/videos/...).",
            "- [ ] Date checked noted next to the entry; unit tests stay offline.",
            "- [ ] Only links: no titles, descriptions or thumbnails copied from their site.",
            "",
            "**Done when**",
            "- [ ] Every slug listed here (and in later comments) is in "
            f"[docs/bibleproject-videos-reviewed.txt](https://github.com/{REPO}/blob/main/docs/bibleproject-videos-reviewed.txt), "
            "whether or not it went into the app.",
            "- [ ] The videos that fit are in `passageEntries` / the overview maps, in a PR "
            "that closes this issue.",
        ]
    lines += [
        "",
        "_From the weekly `bibleproject-videos.yml` check, which reads only "
        "bibleproject.com's sitemap._",
    ]
    return "\n".join(lines) + "\n"


def self_test():
    sitemap = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://bibleproject.com/videos/</loc></url>
  <url><loc>https://bibleproject.com/videos/all/</loc></url>
  <url><loc>https://bibleproject.com/videos/collections/torah/</loc></url>
  <url><loc>https://bibleproject.com/videos/psalm-1/</loc></url>
  <url><loc>https://bibleproject.com/en/videos/luke-1-2/</loc></url>
  <url><loc>https://bibleproject.com/es/videos/salmo-1/</loc></url>
  <url><loc>https://bibleproject.com/podcasts/psalm-1/</loc></url>
</urlset>"""
    assert video_slugs(sitemap) == {"psalm-1", "luke-1-2"}, video_slugs(sitemap)
    assert read_reviewed("# header\npsalm-1  # in the app\n\n") == {"psalm-1"}
    listed = "- [ ] [luke-1-2](https://bibleproject.com/videos/luke-1-2/)"
    assert not_yet_listed({"luke-1-2", "luke-1"}, listed) == ["luke-1"]
    assert "luke-1" in issue_body(["luke-1"]) and "Done when" in issue_body(["luke-1"])
    assert "Done when" not in issue_body(["luke-1"], comment=True)
    print("Self-test passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--reviewed", help="file of slugs already checked")
    parser.add_argument("--sitemap-file", help="read this file instead of downloading the sitemap")
    parser.add_argument("--already-listed", help="text of the open issue, to skip slugs it has")
    parser.add_argument("--issue-body", help="write an issue body here (empty when nothing is new)")
    parser.add_argument("--comment", action="store_true", help="body for a comment, not a new issue")
    parser.add_argument("--self-test", action="store_true", help="run the offline checks and exit")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0
    if not args.reviewed:
        parser.error("--reviewed is required")

    if args.sitemap_file:
        with open(args.sitemap_file, "rb") as f:
            sitemap = f.read()
    else:
        sitemap = fetch_sitemap()
    on_site = video_slugs(sitemap)
    if len(on_site) < MIN_VIDEOS:
        print(f"Only {len(on_site)} videos in the sitemap: has its format changed?", file=sys.stderr)
        return 1

    with open(args.reviewed, encoding="utf-8") as f:
        reviewed = read_reviewed(f.read())
    new = sorted(on_site - reviewed)
    gone = sorted(reviewed - on_site)
    print(f"{len(on_site)} videos on bibleproject.com, {len(reviewed)} reviewed, {len(new)} new")
    for slug in new:
        print(f"new: {slug}")
    for slug in gone:
        # Renamed or removed on their site; a renamed one also shows up as new.
        print(f"no longer in the sitemap: {slug}")

    if args.already_listed:
        with open(args.already_listed, encoding="utf-8") as f:
            new = not_yet_listed(new, f.read())
        print(f"{len(new)} not in the open issue yet")
    if args.issue_body:
        with open(args.issue_body, "w", encoding="utf-8") as f:
            f.write(issue_body(new, args.comment) if new else "")
    return 0


if __name__ == "__main__":
    sys.exit(main())
