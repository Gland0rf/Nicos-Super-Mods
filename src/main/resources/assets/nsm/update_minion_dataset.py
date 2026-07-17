"""
Regenerate Hypixel SkyBlock minion tier speed/storage values from dedicated wiki pages.

Usage:
  python regenerate_minions_json.py minions.json minions.exact.json

The script keeps your existing metadata/baseDrops/listedOutputs/specialRules, but replaces
`tiers` with exact values parsed from each dedicated page like:
  https://wiki.hypixel.net/Pig_Minion

Requires internet access. No API key needed.
"""

from __future__ import annotations

import html
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Dict, Any, List, Tuple
import urllib.error
import urllib.parse
import urllib.request
import http
from email.message import Message
import http.cookiejar

ROMAN_TO_INT = {
    "I": 1,
    "II": 2,
    "III": 3,
    "IV": 4,
    "V": 5,
    "VI": 6,
    "VII": 7,
    "VIII": 8,
    "IX": 9,
    "X": 10,
    "XI": 11,
    "XII": 12,
}

# Use explicit page names for cases where displayName -> page-name conversion is not enough.
PAGE_OVERRIDES = {
    # Farming
    "COCOA_BEANS": "Cocoa_Beans_Minion",
    "NETHER_WART": "Nether_Wart_Minion",
    "SUGAR_CANE": "Sugar_Cane_Minion",
    "MELON": "Melon_Minion",
    "PUMPKIN": "Pumpkin_Minion",
    "WHEAT": "Wheat_Minion",
    "CACTUS": "Cactus_Minion",
    "CARROT": "Carrot_Minion",
    "POTATO": "Potato_Minion",
    "MUSHROOM": "Mushroom_Minion",
    "PIG": "Pig_Minion",
    "COW": "Cow_Minion",
    "CHICKEN": "Chicken_Minion",
    "SHEEP": "Sheep_Minion",
    "RABBIT": "Rabbit_Minion",

    # Mining
    "COBBLESTONE": "Cobblestone_Minion",
    "COAL": "Coal_Minion",
    "IRON": "Iron_Minion",
    "GOLD": "Gold_Minion",
    "DIAMOND": "Diamond_Minion",
    "EMERALD": "Emerald_Minion",
    "LAPIS": "Lapis_Minion",
    "REDSTONE": "Redstone_Minion",
    "QUARTZ": "Quartz_Minion",
    "OBSIDIAN": "Obsidian_Minion",
    "GLOWSTONE": "Glowstone_Minion",
    "GRAVEL": "Gravel_Minion",
    "SAND": "Sand_Minion",
    "ICE": "Ice_Minion",
    "SNOW": "Snow_Minion",
    "MITHRIL": "Mithril_Minion",
    "HARD_STONE": "Hard_Stone_Minion",
    "END_STONE": "End_Stone_Minion",
    "RED_SAND": "Red_Sand_Minion",
    "MYCELIUM": "Mycelium_Minion",

    # Combat
    "ZOMBIE": "Zombie_Minion",
    "SKELETON": "Skeleton_Minion",
    "SPIDER": "Spider_Minion",
    "CAVE_SPIDER": "Cave_Spider_Minion",
    "CREEPER": "Creeper_Minion",
    "SLIME": "Slime_Minion",
    "MAGMA_CUBE": "Magma_Cube_Minion",
    "BLAZE": "Blaze_Minion",
    "ENDERMAN": "Enderman_Minion",
    "GHAST": "Ghast_Minion",
    "REVENANT": "Revenant_Minion",
    "TARANTULA": "Tarantula_Minion",
    "VOIDLING": "Voidling_Minion",
    "INFERNO": "Inferno_Minion",
    "VAMPIRE": "Vampire_Minion",

    # Fishing
    "CLAY": "Clay_Minion",
    "FISHING": "Fishing_Minion",
    "LILY_PAD": "Lily_Pad_Minion",

    # Foraging
    "OAK": "Oak_Minion",
    "SPRUCE": "Spruce_Minion",
    "BIRCH": "Birch_Minion",
    "DARK_OAK": "Dark_Oak_Minion",
    "ACACIA": "Acacia_Minion",
    "JUNGLE": "Jungle_Minion",
    "FLOWER": "Flower_Minion",

    # Special/Event
    "SUNFLOWER": "Sunflower_Minion",
}

USER_AGENT = (
    "MinionDataRegenerator/1.0 "
    "(Hypixel SkyBlock data utility;"
)

REQUEST_HEADERS = {
    "User-Agent": USER_AGENT,
    "Accept": "application/json,text/html;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
}

COOKIE_JAR = http.cookiejar.CookieJar()

HTTP = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(COOKIE_JAR),
)


def open_url(url: str, *, timeout: int = 30) -> bytes:
    request = urllib.request.Request(
        url,
        headers=REQUEST_HEADERS,
        method="GET",
    )

    try:
        with HTTP.open(request, timeout=timeout) as response:
            return response.read()

    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")

        raise RuntimeError(
            f"HTTP {exc.code} {exc.reason} for {url}\n"
            f"Response preview: {body[:500]}"
        ) from exc

    except urllib.error.URLError as exc:
        raise RuntimeError(
            f"Could not retrieve {url}: {exc.reason}"
        ) from exc


def fetch_page_text_direct(page_name: str) -> str:
    encoded_page = urllib.parse.quote(page_name, safe="_/()'-")
    url = f"https://hypixelskyblock.minecraft.wiki/w/{encoded_page}"

    raw_html = open_url(url).decode("utf-8", errors="replace")
    return clean_html_to_text(raw_html)


def fetch_page_text(page_name: str) -> str:
    return fetch_page_text_direct(page_name)

def page_name_for(minion_id: str, minion: Dict[str, Any]) -> str:
    if minion_id in PAGE_OVERRIDES:
        return PAGE_OVERRIDES[minion_id]

    display = minion.get("displayName") or f"{minion_id.title()} Minion"
    # "Pig Minion" -> "Pig_Minion"
    return display.strip().replace(" ", "_")

def clean_html_to_text(raw: str) -> str:
    # Strip tags but preserve spacing so the regex sees readable card text.
    raw = re.sub(r"<script\b.*?</script>", " ", raw, flags=re.IGNORECASE | re.DOTALL)
    raw = re.sub(r"<style\b.*?</style>", " ", raw, flags=re.IGNORECASE | re.DOTALL)
    raw = re.sub(r"<[^>]+>", " ", raw)
    raw = html.unescape(raw)
    raw = re.sub(r"\s+", " ", raw)
    return raw

TIER_CARD_RE = re.compile(
    r"(?:^|\s)"
    r"(?P<roman>XII|XI|IX|VIII|VII|VI|IV|X|V|III|II|I)"
    r"(?:(?!\b(?:Cooldown|Time Between Actions)\s*:).){0,1200}?"
    r"(?:Cooldown|Time Between Actions)\s*:\s*"
    r"(?P<speed>[0-9]+(?:\.[0-9]+)?)\s*s"
    r"(?:(?!\b(?:Storage|Max Storage)\s*:).){0,400}?"
    r"(?:Storage|Max Storage)\s*:\s*"
    r"(?P<storage>[0-9,]+)",
    re.IGNORECASE | re.DOTALL,
)

def parse_tiers(page_text: str, expected_max_tier: int) -> Dict[str, Dict[str, Any]]:
    tiers: Dict[int, Dict[str, Any]] = {}

    for match in TIER_CARD_RE.finditer(page_text):
        roman = match.group("roman").upper()
        tier = ROMAN_TO_INT.get(roman)

        if tier is None or tier > expected_max_tier:
            continue

        speed = float(match.group("speed"))
        storage = int(match.group("storage").replace(",", ""))

        # Avoid later duplicate mentions in recipes/history overwriting the top card.
        if tier not in tiers:
            tiers[tier] = {
                "secondsBetweenActions": int(speed) if speed.is_integer() else speed,
                "storage": storage,
            }

    missing = [tier for tier in range(1, expected_max_tier + 1) if tier not in tiers]
    if missing:
        raise ValueError(f"missing tier cards: {missing}")

    return {str(tier): tiers[tier] for tier in range(1, expected_max_tier + 1)}


def regenerate(input_path: Path, output_path: Path) -> None:
    data = json.loads(input_path.read_text(encoding="utf-8"))

    errors: List[Tuple[str, str]] = []
    updated = 0

    for minion_id, minion in data.items():
        max_tier = int(minion.get("maxTier") or len(minion.get("tiers", {})) or 11)
        page = page_name_for(minion_id, minion)

        try:
            text = fetch_page_text(page)
            exact_tiers = parse_tiers(text, max_tier)

            minion["tiers"] = exact_tiers
            minion["tierDataQuality"] = "exact_from_dedicated_wiki_page"
            minion["tierDataSource"] = f"https://hypixelskyblock.minecraft.wiki/w/{page}"

            # Keep ranges if you want, but mark them as metadata only.
            if "cooldownRange" in minion:
                minion["cooldownRange"]["deprecated"] = True
            if "storageRange" in minion:
                minion["storageRange"]["deprecated"] = True

            updated += 1
            print(f"OK  {minion_id:<15} {page}")
            time.sleep(0.75)  # be polite to the wiki
        except Exception as exc:  # keep going so one weird page doesn't destroy the whole run
            errors.append((minion_id, f"{page}: {exc}"))
            minion["tierDataQuality"] = "needs_manual_review"
            minion["tierDataSource"] = f"https://hypixelskyblock.minecraft.wiki/w/{page}"
            print(f"ERR {minion_id:<15} {page}: {exc}", file=sys.stderr)

    output_path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"\nWrote {output_path}")
    print(f"Updated: {updated}/{len(data)}")

    if errors:
        print("\nManual review needed:", file=sys.stderr)
        for minion_id, message in errors:
            print(f"- {minion_id}: {message}", file=sys.stderr)
        sys.exit(2)


def main(argv: List[str]) -> None:
    if len(argv) != 3:
        print("Usage: python regenerate_minions_json.py minions.json minions.exact.json", file=sys.stderr)
        sys.exit(1)

    regenerate(Path(argv[1]), Path(argv[2]))


if __name__ == "__main__":
    main(sys.argv)