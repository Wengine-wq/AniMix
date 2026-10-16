import json, sys, os
root = sys.argv[1]
data = json.load(open(os.path.join(root, "design/achievements/achievements.ru.json"), encoding="utf-8"))

shikimori = {
    "haikyuu": 20583, "attack_on_titan": 16498, "one_piece": 21, "jujutsu_kaisen": 40748, "solo_leveling": 52299,
    "demon_slayer": 38000, "gachiakuta": 59062, "dandadan": 57334, "chainsaw_man": 44511, "frieren": 52991,
    "kaiju_no_8": 52588, "sakamoto_days": 58939, "blue_lock": 49596, "apothecary_diaries": 54492,
    "hells_paradise": 46569, "fire_force": 38671, "witch_hat_atelier": 51553, "re_zero": 31240, "oshi_no_ko": 52034,
    "quintessential_quintuplets_s1": 38101, "quintessential_quintuplets_s2": 39783,
    "quintessential_quintuplets_movie_2022": 48548,
}
categories = {
    "anime": "Anime", "library": "Library", "settings": "Settings", "habits": "Habits", "watch_time": "WatchTime",
    "downloads": "Downloads", "discovery": "Discovery", "community": "Community", "profile": "Profile", "meta": "Meta",
}


def q(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


lines = []
for a in sorted(data["achievements"], key=lambda x: x["number"]):
    t = a["trigger"]
    args = [f"metric = {q(t['metric'])}", f"threshold = {t['threshold']}"]
    if "animeKey" in t:
        args.append(f"animeId = {shikimori[t['animeKey']]}")
    if "value" in t:
        args.append(f"value = {q(t['value'])}")
    if "setting" in t:
        args.append(f"value = {q(t['setting'])}")
    if "requiredIds" in t:
        args.append("required = listOf(" + ", ".join(q(r) for r in t["requiredIds"]) + ")")
    lines.append(
        f"    Achievement(\n        id = {q(a['id'])}, number = {a['number']}, category = AchievementCategory.{categories[a['category']]},\n"
        f"        name = {q(a['name'])},\n        description = {q(a['description'])},\n"
        f"        trigger = Trigger({', '.join(args)}),\n    ),"
    )

out = f'''package app.animix.data.achievements

// Generated from design/achievements/achievements.ru.json (v{data["version"]}); edit the JSON, then regenerate.
// animeId values are Shikimori ids, checked against the Shikimori API on 2026-10-16.

internal val AchievementCatalog: List<Achievement> = listOf(
{chr(10).join(lines)}
)
'''
p = os.path.join(root, "shared/src/commonMain/kotlin/app/animix/data/achievements/AchievementCatalog.kt")
os.makedirs(os.path.dirname(p), exist_ok=True)
open(p, "w", encoding="utf-8", newline="\n").write(out)
print("ok", len(lines))
