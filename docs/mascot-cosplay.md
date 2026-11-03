# Косплеи маскота AniMix

**Новый набор · 1 ноября 2026:** принята мультяшная рисовка `design/mascot/remake-2026-11/animix-mascot-cartoon.png`. Новые косплеи и образы «Необъятного океана» из `design/mascot/remake-2026-11/art-pack/` встроены в 1.2.0 re: «Клинок» → `cosplay-demon`, «Пять невест» → `cosplay-quintuplets`, «Атака титанов» → `cosplay-titan`, Hunter × Hunter → `cosplay-hunter`, «Необъятный океан» 37105 → `cosplay-grand-blue-diver`, 59986 и 62542 → `cosplay-grand-blue-beach`. Ниже — архив старой версии и её прежних соответствий, а не спецификация нового образа.

Маскот остаётся той же взрослой девушкой, меняются костюм и поза. Оригинальное лицо, причёска и розовые кончики сохраняются. Это косплей маскота, а не замена постера или персонажа тайтла.

## Размещение

На странице аниме после описания и перед кадрами расположен `MascotCosplayNote`: прозрачная иллюстрация 112 × 184 dp, подпись «Косплей маскота» и название образа. Одна иллюстрация, без панели, кнопок, анимации и сетевых запросов. Светлая и тёмная темы сохраняют исходные цвета PNG.

| Образ | Релизы Shikimori | Локальный файл в `shared/src/commonMain/composeResources/drawable/` |
| --- | --- | --- |
| Незуко · Клинок, рассекающий демонов | 38000, 40456, 49926, 47778, 51019, 55701, 59192, 62546, 62547 | `animix_cosplay_demon.png` |
| Мику Накано · Пять невест | 38101, 39783, 48548, 54915, 58755, 64104 | `animix_cosplay_quintuplets.png` |
| Разведкорпус · Атака титанов | 16498, 25777, 35760, 38524, 40028, 48583, 51535, 59571, 18397, 25781, 36106, 23775, 23777, 36702, 42091 | `animix_cosplay_titan.png` |
| Гон · Hunter × Hunter (2011) и два фильма | 11061, 13271, 19951 | `animix_cosplay_hunter.png` |

Названия и ID сверены через публичный GraphQL API каталога Shikimori. Соответствия заданы явно в `data/mascot/MascotCosplay.kt`; перевод названия, рекомендации и случайное совпадение слов не влияют на выбор. Релизы Hunter × Hunter 1999 и их OVA исключены. Номер из другого источника, например Yummy, не считается ID Shikimori. Отдельные рекламные ролики не добавляются автоматически.

## Генерация

Все четыре финальных PNG созданы встроенным image_gen, с `animix_mascot_v2.png` как референсом личности, на настоящем прозрачном фоне. Код не ретуширует и не перекрашивает иллюстрации. Точные промпты ниже.


## Промпт: Гон

```text
Use case: identity-preserve. Create one original AniMix app mascot cosplay illustration. Input reference shows the adult AniMix woman whose identity must stay recognizable: same adult face and natural adult proportions (23 years old), rose eyes, graphite shoulder-length wavy hair with dusty-pink ends and tiny pink clip. She is an ADULT FAN COSPLAYING, not transformed into the age or identity of the original anime character. Preserve her exact face and hair. Change only clothing and pose as described below. Clean refined contemporary anime cel illustration, restrained detail, delicate charcoal outlines matching the reference. A single full-body adult woman, centered with all hair, fingers and footwear inside frame. Tight portrait framing with a small clean margin. Genuine TRANSPARENT PNG CUTOUT, zero-alpha outside character. No background, atmosphere, vignette, gradient, shadow backdrop, floor, scenery, extra people, lettering, watermark, UI, particles or floating decorations. Clothing must be fully opaque, comfortable and tasteful. Keep costume recognizable but simple; avoid piles of accessories. Adult fan cosplay of Gon's recognizable green adventurer outfit from Hunter x Hunter (2011): opaque forest-green jacket with red-orange edging and upright collar, matching simple green tailored shorts, dark belt and green lace-up mid-calf boots. Neat comfortable costume, no exposed midriff, no fishing rod, no bag or props. Preserve mascot adult face, wavy graphite hair with pink tips; do not give her Gon's spiky hair or childish proportions. Energetic but restrained standing pose, one hand on hip and the other giving a small thumbs-up beside shoulder; warm open-eyed adventurous smile. Full body and both boots inside frame.
```

## Промпт: Разведкорпус

```text
Use case: identity-preserve. Create one original AniMix app mascot cosplay illustration. Input reference shows the adult AniMix woman whose identity must stay recognizable: same adult face and natural adult proportions (23 years old), rose eyes, graphite shoulder-length wavy hair with dusty-pink ends and tiny pink clip. She is an ADULT FAN COSPLAYING, not transformed into the age or identity of the original anime character. Preserve her exact face and hair. Change only clothing and pose as described below. Clean refined contemporary anime cel illustration, restrained detail, delicate charcoal outlines matching the reference. A single full-body adult woman, centered with all hair, fingers and footwear inside frame. Tight portrait framing with a small clean margin. Genuine TRANSPARENT PNG CUTOUT, zero-alpha outside character. No background, atmosphere, vignette, gradient, shadow backdrop, floor, scenery, extra people, lettering, watermark, UI, particles or floating decorations. Clothing must be fully opaque, comfortable and tasteful. Keep costume recognizable but simple; avoid piles of accessories. Cosplay of an adult Survey Corps scout from Attack on Titan / Shingeki no Kyojin: short tan scout jacket over opaque ivory high-neck shirt, simple fitted cream trousers, understated brown harness straps at waist and thighs, tall brown boots, short forest-green cloak pinned at neck. A small blue-and-white Wings of Freedom patch on sleeve is the sole insignia. No swords, no mechanical maneuver gear, no weapons or blood. Confident upright standing pose with right fist resting respectfully over heart and left arm relaxed. Subtle proud smile, both eyes open. Preserve original adult mascot face and hair.
```

## Промпт: Мику Накано

```text
Use case: identity-preserve. Create one original AniMix app mascot cosplay illustration. Input reference shows the adult AniMix woman whose identity must stay recognizable: same adult face and natural adult proportions (23 years old), rose eyes, graphite shoulder-length wavy hair with dusty-pink ends and tiny pink clip. She is an ADULT FAN COSPLAYING, not transformed into the age or identity of the original anime character. Preserve her exact face and hair. Change only clothing and pose as described below. Clean refined contemporary anime cel illustration, restrained detail, delicate charcoal outlines matching the reference. A single full-body adult woman, centered with all hair, fingers and footwear inside frame. Tight portrait framing with a small clean margin. Genuine TRANSPARENT PNG CUTOUT, zero-alpha outside character. No background, atmosphere, vignette, gradient, shadow backdrop, floor, scenery, extra people, lettering, watermark, UI, particles or floating decorations. Clothing must be fully opaque, comfortable and tasteful. Keep costume recognizable but simple; avoid piles of accessories. Cosplay of Miku Nakano from The Quintessential Quintuplets / Gotoubun no Hanayome: dark teal/light-blue over-ear headphones resting around neck, pale blue long-sleeved cardigan, neatly buttoned white blouse with small dark ribbon, simple dark pleated knee-length skirt, dark tights and brown loafers. Keep original adult mascot hair and face, no wig, not a schoolgirl or child. Calm slightly shy smile, standing with feet close together, one hand touching the headphones and other lightly resting on cardigan hem. Clean recognizable costume, headphones are the only accessory.
```

## Промпт: Незуко

```text
Use case: identity-preserve. Create one original AniMix app mascot cosplay illustration. Input reference shows the adult AniMix woman whose identity must stay recognizable: same adult face and natural adult proportions (23 years old), rose eyes, graphite shoulder-length wavy hair with dusty-pink ends and tiny pink clip. She is an ADULT FAN COSPLAYING, not transformed into the age or identity of the original anime character. Preserve her exact face and hair. Change only clothing and pose as described below. Clean refined contemporary anime cel illustration, restrained detail, delicate charcoal outlines matching the reference. A single full-body adult woman, centered with all hair, fingers and footwear inside frame. Tight portrait framing with a small clean margin. Genuine TRANSPARENT PNG CUTOUT, zero-alpha outside character. No background, atmosphere, vignette, gradient, shadow backdrop, floor, scenery, extra people, lettering, watermark, UI, particles or floating decorations. Clothing must be fully opaque, comfortable and tasteful. Keep costume recognizable but simple; avoid piles of accessories. Cosplay of Nezuko's outfit from Demon Slayer / Kimetsu no Yaiba: pale pink kimono with recognizable simple geometric asanoha pattern, red-and-white obi, charcoal-brown open haori, cream leg wraps and dark sandals. Keep kimono neatly closed and long, adult mascot face visible, no bamboo in mouth, no demon marks, no wig. Relaxed standing pose, knees together, one hand lightly holding the haori edge and the other offering a small open-palm greeting. Gentle warm smile. Pink and graphite costume harmonizes with AniMix.
```
