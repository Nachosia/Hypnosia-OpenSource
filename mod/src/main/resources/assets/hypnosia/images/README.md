# Image Render Module

Модуль для рендера картинок и GIF-анимаций на экране.

## Папка для файлов

Картинки лежат **ВНЕ мода**, в папке игры:

```
.minecraft/Hypnosia/kartinki/
├── logo.png
├── animation.gif
└── ...
```

Мод автоматически создаёт эту папку при запуске, если её нет.

## Как работает

Вместо того чтобы рендерить ВСЁ из папки, модуль смотрит **только на прописанные в конфиге файлы**.

### 1. Закинь файлы в `.minecraft/Hypnosia/kartinki/`

Поддерживаются `.png` и `.gif`.

### 2. Пропиши нужные файлы в конфиге

В файле `.minecraft/hypnosia/client-settings.properties` добавь ключ:

```properties
image.entries=logo.png,animation.gif,my_cover.png
```

Формат: имена файлов **с расширением**, через запятую.

### 3. Автоопределение типа

Модуль сам смотрит на расширение:
- `.png` → загружает из `kartinki/`, рендерит как статичную картинку
- `.gif` → загружает из `kartinki/`, рендерит как анимацию (GIF-конфиг)

### 4. V2 GUI

Модуль находится в разделе **Client → Images**.
- Там виден список всех прописанных картинок
- Тап по строке — вкл/выкл конкретную картинку
- **Reload** — перезагружает все файлы с диска
- Глобальный тумблер модуля — вкл/выкл весь оверлей

### 5. Настройки каждого изображения

Каждый файл имеет свой набор настроек:

```properties
image.entries=logo.png,animation.gif

# logo.png
image.entry.logo.png.enabled=true
image.entry.logo.png.x=20.0
image.entry.logo.png.y=200.0
image.entry.logo.png.scale=1.0
image.entry.logo.png.rounded=8.0

# animation.gif
image.entry.animation.gif.enabled=true
image.entry.animation.gif.x=20.0
image.entry.animation.gif.y=400.0
image.entry.animation.gif.scale=1.5
image.entry.animation.gif.rounded=16.0
```

## API (для разработчиков)

```kotlin
// Добавить файл в конфиг (автоматически определит PNG или GIF)
ImageRenderConfig.add("my_pic.png")
ImageRenderConfig.add("my_anim.gif")

// Удалить
ImageRenderConfig.remove("my_pic.png")

// Глобально включить/выключить оверлей
ImageRenderConfig.setGlobalEnabled(false)

// Получить все записи
val entries = ImageRenderConfig.entries()
for (entry in entries) {
    println("${entry.path} — PNG=${entry.isPng}, GIF=${entry.isGif}")
}

// Обновить настройки одного изображения
val entry = ImageRenderEntry("logo.png", enabled = true, x = 100f, y = 100f, scale = 2f)
ImageRenderConfig.update(entry)

// Перезагрузить все изображения с диска
ImageRenderModule.reload()

// Доступ к загруженным объектам
val static = ImageRenderModule.getStatic("logo")
val gif = ImageRenderModule.getGif("animation")
```
