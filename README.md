# kael_chat_app2

Чат с Kael (Android). Репозиторий готов к клонированию и сборке через Git.

## Требования

- **JDK 17** (для сборки)
- Android: minSdk 24, targetSdk 34

## Клонирование и сборка

### 1. Клонировать репозиторий

```bash
git clone https://github.com/ВАШ_ЛОГИН/kael_chat_app2.git
cd kael_chat_app2
```

### 2. Сборка из командной строки

**Windows (PowerShell или cmd):**
```bat
gradlew.bat assembleDebug
```

**macOS / Linux:**
```bash
chmod +x gradlew
./gradlew assembleDebug
```

APK будет в: `app/build/outputs/apk/debug/app-debug.apk`.

### 3. Сборка в Android Studio

1. **File → Open** → выбери папку `kael_chat_app2` (корень репозитория).
2. Дождись **Sync Project with Gradle Files**.
3. В **File → Project Structure → SDK Location** укажи **JDK 17**.
4. Запуск: кнопка **Run** (зелёный треугольник) или **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

## Сборка через GitHub Actions

При пуше в ветку `main` или `master` запускается сборка. Готовый **app-debug.apk** можно скачать во вкладке **Actions** → выбранный run → **Artifacts**.

## Что внутри

- Первый запуск: ввод API ключа → чат.
- Чат: Kael (слева), Lien (справа), поле «Пиши…», скрепка, кнопка отправки.
- Настройки: API ключ, API URL, консоль, вложения.
- Цвета: фон #111111, текст #B4B4B4, панели #2d2d2d с 16% прозрачности, Kael #3f0000, Lien #5a003e.
- Уведомления при ответе в фоне, до 10 000 сообщений, температура 1.2, время у сообщений, файлы от Kael ([FILE:]…[/FILE]) с кнопкой «Скачать».

Иконка: см. `app/src/main/res/drawable/ICON_README.txt`.
