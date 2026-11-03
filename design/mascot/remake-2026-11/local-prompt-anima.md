# Локальный промпт · nununuMix v5.4 ANIMA

Модель уточнена пользователем. Промпт подготовлен по документации Anima, но на локальном чекпойнте пользователя ещё не проверен. Программа, точное имя файла и режим генерации не сообщены.

## Версии

Через API карточки автора nununu568 проверено: `v5.4 anima_a` (3111561) помечена `a=aesthetic`; `v5.4 anima_b` (3095759) — `b=base`. Это варианты Anima, не Illustrious/SDXL. Не назначать настройки другого семейства по созвучному названию.

- [Карточка nununu mix](https://civitai.com/models/2071675/nununu-mix)
- [API варианта base](https://civitai.com/api/v1/model-versions/3095759)
- [Документация разработчика Anima](https://huggingface.co/circlestone-labs/Anima#prompting)

В Anima поддерживается сочетание тегов и естественного языка. Начальная строка `deviantart` — документированный тег части датасета с неанимешными иллюстрациями; здесь это эксперимент для нужного мультяшного направления, а не гарантированный переключатель стиля. Для первой пробы не использовать `score_*`: они не рекомендуются разработчиком для Anima-Aesthetic, а точный вариант пользователя пока не известен.

## Positive

```text
deviantart
masterpiece, best quality, safe, 1girl, solo, adult woman, amber eyes, wavy hair, shoulder-length hair, dark purple hair, pink streaked hair, white t-shirt, dark shorts, socks, sitting, waving, smile, open mouth, looking at viewer, full body, simple background

A hand-drawn 2D cartoon illustration of a cheerful adult woman with a rounded face, a small rounded nose and expressive round amber eyes. Her voluminous dark-plum hair has one broad dusty-pink front lock. She wears a loose ivory T-shirt with a small pink stylized A emblem, charcoal lounge shorts and ivory socks. She sits with both legs folded to one side and gives a friendly wave.

Visible delicate plum outlines, softly painted cel shading, broad grouped hair locks, warm peach highlights and muted violet shadows. Rounded flowing shapes, natural stylized adult proportions, subtle brush texture and moderate detail. Cozy, expressive illustrated character design.
```

## Negative

```text
worst quality, low quality, blurry, jpeg artifacts, 3d, cgi, render, photorealistic, plastic skin, glossy skin, doll, figurine, sculpted hair, chibi, oversized head, flat vector, anime screenshot, manga panels, extra fingers, extra limbs, watermark
```

## Начальная проба

Для обычного недистиллированного workflow без ускоряющих LoRA: 1024 × 1536, 30 шагов, CFG 4.5, `er_sde`, если доступен. Это экспериментальная стартовая точка из диапазона рекомендаций базовой Anima (30–50 шагов, CFG 4–5), не проверенный рецепт автора nununuMix v5.4. Для Turbo/ускоряющих LoRA эти параметры автоматически не переносить.

Использовать один seed и сравнить две генерации: с первой строкой `deviantart` и без неё; остальные параметры и текст оставить одинаковыми. Такой тест покажет, помогает ли этот тег именно в данном миксе. Пустой общий тег `anime` в Negative пока не добавлять; здесь подавляются конкретно 3D, чиби и кадр аниме.

Для сохранения именно лица и характера утверждённого маскота использовать `animix-mascot-cartoon.png` как визуальный референс в совместимом workflow. Нельзя обещать идентичность по одному тексту. Denoise и способ подключения референса требуют знания программы и workflow.

Точный логотип передавать отдельным изображением или наносить после генерации. Приложение и его ресурсы не меняются.
