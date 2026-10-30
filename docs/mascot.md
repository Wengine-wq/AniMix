# Маскот AniMix 3.0

Первая версия: взрослая аниме-девушка в лёгкой повседневной одежде, графитовые волосы с розовыми кончиками, маленькая заколка. Фарфоровый, графитовый и мягкий розовый соответствуют палитре приложения. Имя пока не назначено.

## Использование

- Тематические косплеи для четырёх франшиз описаны отдельно в [mascot-cosplay.md](mascot-cosplay.md). Они появляются только на соответствующих страницах аниме и не заменяют обычные эмоции в пустых состояниях.
- Исходный PNG с настоящим alpha: `shared/src/commonMain/composeResources/drawable/animix_mascot_v2.png`. Изображение поставляется с приложением и доступно без сети.
- Общий компонент `AniMixMascot` сохраняет пропорции и исходные цвета. Не обрезать силуэт, не тонировать под тему, не добавлять отдельную рамку или панель.
- Точки размещения: пустые списки (160 × 208 dp) и идентичность релиза в «О приложении» (112 × 160 dp).
- Дополнительные позы: `animix_mascot_thinking.png` — задумчивая, для завершившегося пустого поиска и фильтров; `animix_mascot_relaxed.png` — стоя, закрытые глаза и спокойная улыбка, для пустых загрузок; `animix_mascot_happy.png` — стоя, подмигивание и V-жест, только для `FlowFinish.QueueCompleted`. Радостная поза не показывается при истечении лимита или ручной остановке.
- Вариант выбирается через `MascotPose`; поиск и загрузки используют общий `MascotMessage` размером 160 × 208 dp. Завершение очереди — 136 × 176 dp над заголовком. Ни анимаций, ни фоновых запросов нет; одна иллюстрация на состояние.
- Пустой список сохраняет текстовое объяснение и переход в каталог. Маскот декоративный, у кнопки отдельная доступная подпись. В «О приложении» иллюстрация подписана для экранного диктора.
- Маскот статичен; не перекрывает плеер, тайтлы, ошибки или пользовательский профиль. Не добавлять её во все состояния подряд и не делать говорящим помощником без отдельного решения пользователя.

## Генерация

Создана встроенным инструментом image_gen; CLI и внешний API не использовались. Точный промпт первой версии:

```text
Use case: stylized-concept.
Asset type: original mascot illustration for AniMix 3.0, an elegant anime watching app. One production-ready character cutout with REAL TRANSPARENT ALPHA background.
Subject: one beautiful clearly adult woman, about 23 years old, tasteful contemporary anime character design, warm confident gentle smile, looking toward the viewer. Soft graphite shoulder-length hair with dusty pink inner tips, side-swept bangs, a single small rounded pink hairclip. Expressive muted rose eyes.
Clothing: lightweight relaxed ivory short-sleeved blouse tucked into simple high-waisted charcoal summer shorts, plain pale pink open overshirt worn loosely over shoulders, simple white low-top sneakers. Natural adult proportions, ordinary comfortable summer outfit, fully opaque fabrics. No logos, no costume complexity.
Pose: full body, casual relaxed seated pose on an invisible ledge, both legs visible and bent naturally, ankles close together, one hand resting beside her and the other giving a small friendly wave near shoulder height. Clearly drawn elegant hands and all fingers. Friendly welcoming companion, not seductive. Silhouette compact and readable as a small UI illustration.
Style: premium clean contemporary Japanese anime key visual, precise delicate charcoal outlines, refined restrained cel shading with soft light, beautiful face, minimal material detail, polished art direction. Not chibi, not 3D.
Palette: graphite #1A1A1F, pastel pink #FFB4C8, warm porcelain #FBF8FA; harmonious on both dark graphite and porcelain app surfaces.
Composition: character centered and fully contained, approximately square canvas, all hair hands feet in frame, small clean transparent padding, no environment or floor, no props or supporting objects, no text or typography, no decorative particles, no circles, no UI, no watermark, no drop shadow background. Only ONE original character, no reference sheet, no alternate views. True transparent background.
```

Исходный квадратный концепт сохранён отдельно: `design/mascot/animix-mascot-concept-v1.png`. Финальный вертикальный вариант подготовлен тем же инструментом для более плотного размещения в интерфейсе. Промпт уточнения:

```text
Edit target: the attached AniMix mascot PNG. Change ONLY the transparent canvas framing. Preserve the exact same adult woman, illustration, face, outfit, seated pose, hands, colors, and every character detail. Produce a tightly framed vertical portrait canvas (roughly 1:2 width to height) around the full existing character, reducing the large empty transparent space at left and right. Keep all hair, fingertips, clothes and shoes fully inside the image with a small clean transparent margin. True transparent alpha background, no background, no text, no additional elements. This is a framing/cutout refinement for a UI asset, not a redesign.
```


## Радостная поза — animix_mascot_happy.png

Создана встроенным image_gen с `animix_mascot_v2.png` как референсом личности. Финальный промпт:

```text
Use case: identity-preserve. Create one new production UI mascot pose using the attached PNG as the identity reference, not as a scene. Preserve the SAME clearly adult woman (23), exact facial identity, graphite shoulder-length wavy hair with dusty pink ends and small pink clip, rose eyes, ivory short-sleeved opaque button blouse, simple charcoal shorts, pale pink open overshirt draped loosely over shoulders, white sneakers. Same natural adult proportions and refined modern anime illustration: delicate charcoal linework and restrained soft cel shading, graphite #1A1A1F / pastel pink #FFB4C8 / porcelain #FBF8FA palette. Full body visible including hair and both shoes, correct hands and fingers, no cropped extremities. Compact centered silhouette, tightly framed vertical canvas with a small safe transparent margin. Genuine transparent alpha background, no scenery, floor, furniture, shadow layer, props, text, UI, logo, particles, floating symbols or extra characters. No costume additions. Keep it tasteful, friendly and non-seductive. Produce only ONE character and ONE pose, not a reference sheet. New pose and emotion: standing casually facing the viewer, weight on one leg, other foot placed lightly beside it. One hand on her hip and the other raised beside her cheek making a small V peace sign. A cheerful confident smile and a gentle wink. Friendly satisfied 'we finished the watch queue' mood. Both legs and sneakers fully visible. Clearly different from the original seated wave, no jumping or exaggerated excitement.
```

## Спокойная поза — animix_mascot_relaxed.png

Создана встроенным image_gen с `animix_mascot_v2.png` как референсом личности. Финальный промпт:

```text
A new pose of the same AniMix mascot from the reference: an adult woman aged 23, same facial identity, wavy graphite hair with pink tips and small pink clip. Friendly anime illustration matching reference linework and colors. She stands upright with both feet next to each other and both hands gently clasped together in front of her waist. Relaxed shoulders, head slightly tilted, eyes peacefully closed and a warm gentle smile. Opaque ivory blouse with a neat closed neckline, open pink lightweight cardigan, charcoal tailored summer shorts and white sneakers. Full body inside the frame, including hair, hands and shoes. One clean isolated character sprite on a genuinely transparent background. Compact vertical composition with safe transparent margin. No scene, background blur, vignette, shadow, props, decorations, lettering or other characters. The empty area surrounding her must be completely transparent.
```

## Задумчивая поза — animix_mascot_thinking.png

Создана встроенным image_gen с `animix_mascot_v2.png` как референсом личности. Финальный промпт:

```text
Use case: identity-preserve. Create one new production UI mascot pose using the attached PNG as the identity reference, not as a scene. Preserve the SAME clearly adult woman (23), exact facial identity, graphite shoulder-length wavy hair with dusty pink ends and small pink clip, rose eyes, ivory short-sleeved opaque button blouse, simple charcoal shorts, pale pink open overshirt draped loosely over shoulders, white sneakers. Same natural adult proportions and refined modern anime illustration: delicate charcoal linework and restrained soft cel shading, graphite #1A1A1F / pastel pink #FFB4C8 / porcelain #FBF8FA palette. Full body visible including hair and both shoes, correct hands and fingers, no cropped extremities. Compact centered silhouette, tightly framed vertical canvas with a small safe transparent margin. Genuine transparent alpha background, no scenery, floor, furniture, shadow layer, props, text, UI, logo, particles, floating symbols or extra characters. No costume additions. Keep it tasteful, friendly and non-seductive. Produce only ONE character and ONE pose, not a reference sheet. New pose and emotion: sitting with both knees bent together to one side, body upright, one elbow resting lightly on a raised knee, the fingertips of that hand touching her chin, the other hand resting naturally on her lap. Head tilted a little, eyes looking thoughtfully sideways and slightly upward, a curious closed-mouth half smile and one eyebrow subtly raised. A gentle 'let me think' expression appropriate to a search with no results, never upset or distressed. Clearly different pose from the waving original.
```
