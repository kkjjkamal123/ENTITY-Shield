#!/usr/bin/env bash
# Downloads the SMS and URL datasets into the gitignored datasets/ folder and prints row counts.
# Feeds (OpenPhish, PhishTank) change daily, so each file is stamped with the download date.
# CICIDS2017 needs the UNB request form and is fetched by hand (see docs/PLAN.md §4.4).
set -euo pipefail
cd "$(dirname "$0")/../datasets"
d=$(date +%F)
mkdir -p uci_sms tranco openphish phishtank

curl -fsSL -o uci_sms/smsspamcollection.zip https://archive.ics.uci.edu/static/public/228/sms+spam+collection.zip
curl -fsSL -o tranco/top-1m.csv.zip https://tranco-list.eu/top-1m.csv.zip
curl -fsSL -o "openphish/feed_$d.txt" https://openphish.com/feed.txt
curl -fsSL -o "phishtank/online-valid_$d.csv.gz" http://data.phishtank.com/data/online-valid.csv.gz

(cd uci_sms && unzip -oq smsspamcollection.zip)
(cd tranco && unzip -oq top-1m.csv.zip)

echo "uci_sms:   $(wc -l < uci_sms/SMSSpamCollection) messages"
echo "tranco:    $(wc -l < tranco/top-1m.csv) domains"
echo "openphish: $(wc -l < "openphish/feed_$d.txt") URLs"
echo "phishtank: $(( $(gzip -dc "phishtank/online-valid_$d.csv.gz" | wc -l) - 1 )) URLs"
