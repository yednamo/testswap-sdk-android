# TestSwap Android SDK

[Українською](#українською) · [English](#english)

## Українською

Невелика бібліотека для застосунків, які проходять закрите тестування через
[TestSwap](https://testswap.yednamo.com). Вона підтверджує, що тестувальник встановив застосунок
з Google Play за своїм особистим посиланням, і автоматично відмічає дні тесту.

SDK необов’язковий: без нього тестувальники відмічають дні на сайті. Застосунки з SDK позначені в каталозі,
і тестувальники охочіше їх беруть.

### Як це працює

1. Тестувальник отримує в TestSwap особисте посилання на ваш застосунок у Google Play з міткою `ts_…`.
2. Під час першого запуску SDK читає мітку через [Google Play Install Referrer](https://developer.android.com/google/play/installreferrer)
   і повідомляє TestSwap, що встановив саме цей тестувальник.
3. Під час запусків застосунку SDK відмічає день тесту (не частіше ніж раз на 3 години, сервер рахує один день на добу).
4. Коли тест завершено, SDK більше нічого не надсилає.
5. Звичайні користувачі (встановили не за посиланням TestSwap) нічого не надсилають: без мітки SDK мовчить.

### Підключення

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`build.gradle.kts` модуля застосунку:

```kotlin
dependencies {
    implementation("com.github.yednamo:testswap-sdk-android:0.1.0")
}
```

`Application.onCreate()` з ключем застосунку з кабінету TestSwap:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TestSwap.init(this, "tsk_...")
    }
}
```

Java:

```java
TestSwap.init(this, "tsk_...");
```

Бібліотека сама додає дозвіл `INTERNET`. Мінімальна версія Android: 5.0 (API 21).

### Які дані надсилаються

Лише ключ застосунку, токен участі з мітки встановлення і версія SDK на `https://testswap.yednamo.com`.
Жодних ідентифікаторів пристрою, рекламних ідентифікаторів чи даних користувача. Якщо ваша політика конфіденційності
або розділ Data safety у Play Console перелічують сервіси, додайте туди TestSwap.

### Збирання

JDK 17, Android SDK (platform 36):

```bash
./gradlew :testswap:assembleRelease
```

Реліз: тег `X.Y.Z` має збігатися з `version` у `testswap/build.gradle.kts`, JitPack збирає артефакт за тегом.

## English

A small library for apps in closed testing through [TestSwap](https://testswap.yednamo.com). It confirms that a tester
installed the app from Google Play via their personal link and checks in test days automatically.

The SDK is optional: without it testers check in on the website. Apps with the SDK are marked in the catalog,
and testers prefer them.

### How it works

1. In TestSwap the tester gets a personal Google Play link to your app with a `ts_…` tag.
2. On first launch the SDK reads the tag via the [Google Play Install Referrer](https://developer.android.com/google/play/installreferrer)
   and tells TestSwap that this particular tester installed the app.
3. On app launches the SDK checks in the test day (at most every 3 hours; the server counts one day per day).
4. Once the test is over, the SDK stops sending anything.
5. Regular users (not installed via a TestSwap link) send nothing: without the tag the SDK stays silent.

### Setup

Add JitPack to `settings.gradle.kts`, the dependency `com.github.yednamo:testswap-sdk-android:0.1.0`
to the app module, and call `TestSwap.init(this, "tsk_...")` in `Application.onCreate()` with the app key
from your TestSwap dashboard (see the Ukrainian section above for full snippets). The library adds the `INTERNET`
permission itself. Minimum Android version: 5.0 (API 21).

### Data sent

Only the app key, the participation token from the install tag and the SDK version, to `https://testswap.yednamo.com`.
No device identifiers, advertising IDs or user data.

## License

MIT
