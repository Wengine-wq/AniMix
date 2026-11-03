# Локальная генерация: поправка после 3D-результата

**Уточнение:** пользователь сообщил `nununuMixV54 ANIMA`. Текущий вариант промпта и проверенные сведения о семействе — в [local-prompt-anima.md](local-prompt-anima.md). Ниже сохранена предыдущая универсальная попытка.

Локальная проба пользователя сохранила часть образа, но дала гладкий объёмный 3D-рендер. Формулировка «Western animated feature film» в прежнем промпте оказалась слишком неоднозначной. Ниже — кандидат с акцентом на видимый рисованный контур и мягкую покраску. На локальной модели он ещё не проверен; название модели и режим генерации пока не известны.

Использовать `animix-mascot-cartoon.png` как референс принятой личности и рисовки, если выбранный локальный workflow поддерживает изображение-референс.

## Positive

```text
Hand-drawn 2D cartoon character illustration, visible clean plum contour lines, expressive drawn facial features, softly painted cel shading, broad painted hair highlights, warm peach light and muted plum shadows, subtle brush texture, medium detail.

A young adult woman with a softly rounded face, round amber eyes, a small rounded nose and a cheerful open smile. Shoulder-length dark-plum wavy hair with one broad dusty-pink front lock. Natural stylized adult proportions. Loose ivory T-shirt with a pink stylized A emblem, charcoal lounge shorts, ivory socks. Sitting with bent legs folded to one side, gently waving at the viewer. One complete character, full body in frame, simple background.
```

## Negative — только если workflow поддерживает это поле

```text
3D render, CGI, Pixar render, plastic skin, glossy skin, sculpted hair, realistic fabric simulation, ambient occlusion, photorealism, doll, figurine, chibi, oversized head, flat vector, minimalist icon, manga, sharp anime chin, extra limbs, extra fingers, watermark
```

Не добавлять обратно `Pixar style`, `3D cartoon`, `Octane render`, `Unreal Engine` или `cinematic 3D`. Само слово `2D` не гарантирует нужную рисовку; результат зависит от модели и способа использования референса. Параметры sampler/CFG/denoise без названия модели и workflow не назначать.

Точный знак AniMix передавать отдельным изображением или наносить после генерации: текстовый промпт не гарантирует геометрию логотипа. Новые исходники и эта заметка пока не подключаются к приложению.
