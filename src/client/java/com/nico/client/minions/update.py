"""
Regenerate Hypixel SkyBlock minion tier speed/storage values and upgrade costs from dedicated wiki pages.

Usage:
  python regenerate_minions_json.py minions.json minions.exact.json

The script keeps your existing metadata/baseDrops/listedOutputs/specialRules, but replaces:
  - tiers
  - upgradeCosts

with values parsed from each dedicated page like:
  https://hypixelskyblock.minecraft.wiki/w/Pig_Minion

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

ITEM_NAME_TO_PRODUCT_ID_OVERRIDES = {
    # Farming
    "Raw Porkchop": "PORK",
    "Enchanted Raw Porkchop": "ENCHANTED_PORK",
    "Enchanted Cooked Porkchop": "ENCHANTED_GRILLED_PORK",

    "Wheat": "WHEAT",
    "Hay Bale": "HAY_BLOCK",
    "Enchanted Hay Bale": "ENCHANTED_HAY_BALE",
    "Tightly-Tied Hay Bale": "TIGHTLY_TIED_HAY_BALE",

    "Carrot": "CARROT_ITEM",
    "Enchanted Carrot": "ENCHANTED_CARROT",
    "Enchanted Golden Carrot": "ENCHANTED_GOLDEN_CARROT",

    "Potato": "POTATO_ITEM",
    "Enchanted Potato": "ENCHANTED_POTATO",
    "Enchanted Baked Potato": "ENCHANTED_BAKED_POTATO",

    "Pumpkin": "PUMPKIN",
    "Enchanted Pumpkin": "ENCHANTED_PUMPKIN",

    "Melon": "MELON",
    "Enchanted Melon": "ENCHANTED_MELON",
    "Enchanted Melon Block": "ENCHANTED_MELON_BLOCK",

    "Cactus": "CACTUS",
    "Enchanted Cactus Green": "ENCHANTED_CACTUS_GREEN",
    "Enchanted Cactus": "ENCHANTED_CACTUS",

    "Sugar Cane": "SUGAR_CANE",
    "Enchanted Sugar": "ENCHANTED_SUGAR",
    "Enchanted Sugar Cane": "ENCHANTED_SUGAR_CANE",

    "Nether Wart": "NETHER_STALK",
    "Enchanted Nether Wart": "ENCHANTED_NETHER_STALK",
    "Mutant Nether Wart": "MUTANT_NETHER_STALK",

    "Cocoa Beans": "INK_SACK:3",
    "Enchanted Cocoa Bean": "ENCHANTED_COCOA",
    "Enchanted Cookie": "ENCHANTED_COOKIE",

    "Raw Chicken": "RAW_CHICKEN",
    "Enchanted Raw Chicken": "ENCHANTED_RAW_CHICKEN",
    "Enchanted Cooked Chicken": "ENCHANTED_COOKED_CHICKEN",

    "Raw Beef": "RAW_BEEF",
    "Enchanted Raw Beef": "ENCHANTED_RAW_BEEF",
    "Leather": "LEATHER",
    "Enchanted Leather": "ENCHANTED_LEATHER",

    "Mutton": "MUTTON",
    "Enchanted Mutton": "ENCHANTED_MUTTON",
    "Enchanted Cooked Mutton": "ENCHANTED_COOKED_MUTTON",

    "Rabbit": "RABBIT",
    "Enchanted Raw Rabbit": "ENCHANTED_RAW_RABBIT",
    "Rabbit Hide": "RABBIT_HIDE",
    "Enchanted Rabbit Hide": "ENCHANTED_RABBIT_HIDE",

    # Mining
    "Cobblestone": "COBBLESTONE",
    "Enchanted Cobblestone": "ENCHANTED_COBBLESTONE",

    "Coal": "COAL",
    "Enchanted Coal": "ENCHANTED_COAL",
    "Enchanted Coal Block": "ENCHANTED_COAL_BLOCK",

    "Iron Ingot": "IRON_INGOT",
    "Enchanted Iron": "ENCHANTED_IRON",
    "Enchanted Iron Block": "ENCHANTED_IRON_BLOCK",

    "Gold Ingot": "GOLD_INGOT",
    "Enchanted Gold": "ENCHANTED_GOLD",
    "Enchanted Gold Block": "ENCHANTED_GOLD_BLOCK",

    "Diamond": "DIAMOND",
    "Enchanted Diamond": "ENCHANTED_DIAMOND",
    "Enchanted Diamond Block": "ENCHANTED_DIAMOND_BLOCK",

    "Emerald": "EMERALD",
    "Enchanted Emerald": "ENCHANTED_EMERALD",
    "Enchanted Emerald Block": "ENCHANTED_EMERALD_BLOCK",

    "Lapis Lazuli": "INK_SACK:4",
    "Enchanted Lapis Lazuli": "ENCHANTED_LAPIS_LAZULI",
    "Enchanted Lapis Block": "ENCHANTED_LAPIS_LAZULI_BLOCK",

    "Redstone": "REDSTONE",
    "Enchanted Redstone": "ENCHANTED_REDSTONE",
    "Enchanted Redstone Block": "ENCHANTED_REDSTONE_BLOCK",

    "Quartz": "QUARTZ",
    "Nether Quartz": "QUARTZ",
    "Enchanted Quartz": "ENCHANTED_QUARTZ",
    "Enchanted Quartz Block": "ENCHANTED_QUARTZ_BLOCK",

    "Obsidian": "OBSIDIAN",
    "Enchanted Obsidian": "ENCHANTED_OBSIDIAN",

    "Glowstone Dust": "GLOWSTONE_DUST",
    "Enchanted Glowstone Dust": "ENCHANTED_GLOWSTONE_DUST",
    "Enchanted Glowstone": "ENCHANTED_GLOWSTONE",

    "Gravel": "GRAVEL",
    "Flint": "FLINT",
    "Enchanted Flint": "ENCHANTED_FLINT",

    "Sand": "SAND",
    "Enchanted Sand": "ENCHANTED_SAND",

    "Ice": "ICE",
    "Packed Ice": "PACKED_ICE",
    "Enchanted Ice": "ENCHANTED_ICE",
    "Enchanted Packed Ice": "ENCHANTED_PACKED_ICE",

    "Snowball": "SNOW_BALL",
    "Snow Block": "SNOW_BLOCK",
    "Enchanted Snow Block": "ENCHANTED_SNOW_BLOCK",

    "Mithril": "MITHRIL_ORE",
    "Enchanted Mithril": "ENCHANTED_MITHRIL",

    "Hard Stone": "HARD_STONE",
    "Concentrated Stone": "CONCENTRATED_STONE",

    "End Stone": "ENDER_STONE",
    "Enchanted End Stone": "ENCHANTED_ENDSTONE",

    "Red Sand": "RED_SAND",
    "Enchanted Red Sand": "ENCHANTED_RED_SAND",
    "Enchanted Red Sand Cube": "ENCHANTED_RED_SAND_CUBE",

    "Mycelium": "MYCEL",
    "Enchanted Mycelium": "ENCHANTED_MYCELIUM",
    "Enchanted Mycelium Cube": "ENCHANTED_MYCELIUM_CUBE",

    # Combat
    "Rotten Flesh": "ROTTEN_FLESH",
    "Enchanted Rotten Flesh": "ENCHANTED_ROTTEN_FLESH",
    "Enchanted Rotten Flesh Block": "ENCHANTED_ROTTEN_FLESH_BLOCK",

    "Bone": "BONE",
    "Enchanted Bone": "ENCHANTED_BONE",
    "Enchanted Bone Block": "ENCHANTED_BONE_BLOCK",

    "String": "STRING",
    "Enchanted String": "ENCHANTED_STRING",

    "Spider Eye": "SPIDER_EYE",
    "Enchanted Spider Eye": "ENCHANTED_SPIDER_EYE",
    "Enchanted Fermented Spider Eye": "ENCHANTED_FERMENTED_SPIDER_EYE",

    "Gunpowder": "SULPHUR",
    "Enchanted Gunpowder": "ENCHANTED_GUNPOWDER",

    "Slimeball": "SLIME_BALL",
    "Slime Ball": "SLIME_BALL",
    "Enchanted Slimeball": "ENCHANTED_SLIME_BALL",
    "Enchanted Slime Block": "ENCHANTED_SLIME_BLOCK",

    "Magma Cream": "MAGMA_CREAM",
    "Enchanted Magma Cream": "ENCHANTED_MAGMA_CREAM",

    "Blaze Rod": "BLAZE_ROD",
    "Blaze Powder": "BLAZE_POWDER",
    "Enchanted Blaze Powder": "ENCHANTED_BLAZE_POWDER",
    "Enchanted Blaze Rod": "ENCHANTED_BLAZE_ROD",

    "Ender Pearl": "ENDER_PEARL",
    "Enchanted Ender Pearl": "ENCHANTED_ENDER_PEARL",
    "Absolute Ender Pearl": "ABSOLUTE_ENDER_PEARL",

    "Ghast Tear": "GHAST_TEAR",
    "Enchanted Ghast Tear": "ENCHANTED_GHAST_TEAR",

    "Revenant Flesh": "REVENANT_FLESH",
    "Tarantula Web": "TARANTULA_WEB",
    "Null Sphere": "NULL_SPHERE",
    "Crude Gabagool": "CRUDE_GABAGOOL",

    # Fishing
    "Clay": "CLAY_BALL",
    "Enchanted Clay": "ENCHANTED_CLAY_BALL",
    "Enchanted Clay Block": "ENCHANTED_CLAY_BLOCK",

    "Lily Pad": "WATER_LILY",
    "Enchanted Lily Pad": "ENCHANTED_WATER_LILY",

    # Foraging
    "Oak Wood": "LOG",
    "Enchanted Oak Wood": "ENCHANTED_OAK_LOG",
    "Spruce Wood": "LOG:1",
    "Enchanted Spruce Wood": "ENCHANTED_SPRUCE_LOG",
    "Birch Wood": "LOG:2",
    "Enchanted Birch Wood": "ENCHANTED_BIRCH_LOG",
    "Jungle Wood": "LOG:3",
    "Enchanted Jungle Wood": "ENCHANTED_JUNGLE_LOG",
    "Acacia Wood": "LOG_2",
    "Enchanted Acacia Wood": "ENCHANTED_ACACIA_LOG",
    "Dark Oak Wood": "LOG_2:1",
    "Enchanted Dark Oak Wood": "ENCHANTED_DARK_OAK_LOG",
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
    return display.strip().replace(" ", "_")


def clean_html_to_text(raw: str) -> str:
    raw = re.sub(r"<script\b.*?</script>", " ", raw, flags=re.IGNORECASE | re.DOTALL)
    raw = re.sub(r"<style\b.*?</style>", " ", raw, flags=re.IGNORECASE | re.DOTALL)
    raw = re.sub(r"<[^>]+>", " ", raw)
    raw = html.unescape(raw)
    raw = re.sub(r"\s+", " ", raw)
    return raw


def normalize_item_name(value: str) -> str:
    value = html.unescape(value or "")
    value = re.sub(r"\s+", " ", value).strip()
    value = re.sub(r"^Image:\s*", "", value, flags=re.IGNORECASE)
    value = re.sub(r"\.png$", "", value, flags=re.IGNORECASE)
    return value.strip()


def fallback_product_id_for_item_name(item_name: str) -> str:
    cleaned = normalize_item_name(item_name)
    cleaned = cleaned.upper()
    cleaned = re.sub(r"[^A-Z0-9:]+", "_", cleaned)
    cleaned = re.sub(r"^_+", "", cleaned)
    cleaned = re.sub(r"_+$", "", cleaned)
    return cleaned


def product_id_for_item_name(item_name: str, minion: Dict[str, Any]) -> str:
    item_name = normalize_item_name(item_name)

    if item_name in ITEM_NAME_TO_PRODUCT_ID_OVERRIDES:
        return ITEM_NAME_TO_PRODUCT_ID_OVERRIDES[item_name]

    for section_name in ("baseDrops", "listedOutputs"):
        for item in minion.get(section_name, []):
            existing_name = normalize_item_name(str(item.get("itemName", "")))
            existing_id = item.get("productId")

            if existing_name == item_name and existing_id:
                return existing_id

    return fallback_product_id_for_item_name(item_name)


def parse_number(value: str) -> int:
    return int(value.replace(",", "").strip())


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

        if tier not in tiers:
            tiers[tier] = {
                "secondsBetweenActions": int(speed) if speed.is_integer() else speed,
                "storage": storage,
            }

    missing = [tier for tier in range(1, expected_max_tier + 1) if tier not in tiers]
    if missing:
        raise ValueError(f"missing tier cards: {missing}")

    return {str(tier): tiers[tier] for tier in range(1, expected_max_tier + 1)}


FIRST_COINS_RE = re.compile(
    r"(?:\?{3}|[0-9][0-9,.]*)\s*coins",
    re.IGNORECASE,
)

MATERIAL_X_RE = re.compile(
    r"(?P<amount>[0-9][0-9,]*)x\s+"
    r"(?P<name>[A-Za-z][A-Za-z0-9'’\-\s]+?)"
    r"(?="
    r"\s+[0-9][0-9,]*x\s+[A-Za-z]"
    r"|\s+(?:\?{3}|[0-9][0-9,.]*)\s*coins"
    r"|\s+CUMU"
    r"|\s+Image:"
    r"|$"
    r")",
    re.IGNORECASE,
)

def clean_cost_text(value: str) -> str:
    value = html.unescape(value or "")
    value = re.sub(r"\s+", " ", value).strip()

    # Remove image labels but keep the useful text after them.
    value = re.sub(r"\bImage:\s*", " ", value, flags=re.IGNORECASE)

    # Remove wiki/table noise.
    value = re.sub(r"\b(?:CUMU|Recipe|Total|Cumulative|Bazaar)\b", " ", value, flags=re.IGNORECASE)
    value = re.sub(r"\s+", " ", value).strip()

    return value


def parse_materials_from_cost_text(cost_text: str, minion: Dict[str, Any]) -> List[Dict[str, Any]]:
    cost_text = clean_cost_text(cost_text)

    materials: List[Dict[str, Any]] = []

    for match in MATERIAL_X_RE.finditer(cost_text):
        amount = parse_number(match.group("amount"))
        item_name = normalize_item_name(match.group("name"))

        item_name = re.sub(r"\b(?:and|or|plus)$", "", item_name, flags=re.IGNORECASE).strip()
        item_name = re.sub(r"^(?:and|or|plus)\b", "", item_name, flags=re.IGNORECASE).strip()

        if not item_name or amount <= 0:
            continue

        # Avoid accidentally treating the minion item itself as a material.
        if " Minion" in item_name:
            continue

        materials.append({
            "productId": product_id_for_item_name(item_name, minion),
            "itemName": item_name,
            "amount": amount,
        })

    merged: Dict[str, Dict[str, Any]] = {}

    for material in materials:
        key = material["productId"]

        if key not in merged:
            merged[key] = material
        else:
            merged[key]["amount"] += material["amount"]

    return list(merged.values())


def parse_upgrade_costs(
    page_text: str,
    expected_max_tier: int,
    minion: Dict[str, Any],
) -> Dict[str, Dict[str, Any]]:
    """
    Parses upgrade costs from the Stats table.

    The wiki's Stats rows look roughly like:

      II
      Cooldown: 26s
      Storage: 256
      160x Spider Eye
      ??? coins
      recipe grid...
      240x Spider Eye
      CUMU

    The first material group after Storage is the direct upgrade cost for that tier.
    The later material group is cumulative cost and should be ignored.
    """

    matches = list(TIER_CARD_RE.finditer(page_text))
    tier_blocks: Dict[int, str] = {}

    for index, match in enumerate(matches):
        roman = match.group("roman").upper()
        tier = ROMAN_TO_INT.get(roman)

        if tier is None or tier > expected_max_tier:
            continue

        # Start AFTER the storage value.
        # That puts us right at the Total Upgrade Cost column.
        block_start = match.end()
        block_end = matches[index + 1].start() if index + 1 < len(matches) else len(page_text)
        block = page_text[block_start:block_end]

        if tier not in tier_blocks:
            tier_blocks[tier] = block

    upgrades: Dict[str, Dict[str, Any]] = {}

    for to_tier in range(2, expected_max_tier + 1):
        block = tier_blocks.get(to_tier)

        if not block:
            continue

        # Direct upgrade cost ends at the first coin-price cell.
        # Anything after that is recipe grid/cumulative cost/profit/etc.
        coins_match = FIRST_COINS_RE.search(block)

        if coins_match:
            cost_text = block[:coins_match.start()]
        else:
            cost_text = block

        materials = parse_materials_from_cost_text(cost_text, minion)

        if not materials:
            continue

        from_tier = to_tier - 1

        upgrades[f"{from_tier}_to_{to_tier}"] = {
            "materials": materials,
            "source": "dedicated_wiki_page",
        }

    return upgrades


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
            upgrade_costs = parse_upgrade_costs(text, max_tier, minion)

            minion["tiers"] = exact_tiers
            minion["tierDataQuality"] = "exact_from_dedicated_wiki_page"
            minion["tierDataSource"] = f"https://hypixelskyblock.minecraft.wiki/w/{page}"

            if upgrade_costs:
                minion["upgradeCosts"] = upgrade_costs
                minion["upgradeCostDataQuality"] = "parsed_from_dedicated_wiki_page"
                minion["upgradeCostDataSource"] = f"https://hypixelskyblock.minecraft.wiki/w/{page}"
            else:
                minion["upgradeCostDataQuality"] = "needs_manual_review"
                minion["upgradeCostDataSource"] = f"https://hypixelskyblock.minecraft.wiki/w/{page}"

            if "cooldownRange" in minion:
                minion["cooldownRange"]["deprecated"] = True
            if "storageRange" in minion:
                minion["storageRange"]["deprecated"] = True

            updated += 1
            print(f"OK  {minion_id:<15} {page:<28} upgrades={len(upgrade_costs)}")
            time.sleep(0.1)

        except Exception as exc:
            errors.append((minion_id, f"{page}: {exc}"))
            minion["tierDataQuality"] = "needs_manual_review"
            minion["tierDataSource"] = f"https://hypixelskyblock.minecraft.wiki/w/{page}"
            minion["upgradeCostDataQuality"] = "needs_manual_review"
            minion["upgradeCostDataSource"] = f"https://hypixelskyblock.minecraft.wiki/w/{page}"

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