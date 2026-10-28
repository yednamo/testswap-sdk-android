# TestSwap Android SDK

[Українською](#українською) · [English](#english)

## Українською

Невелика бібліотека для застосунків, які проходять закрите тестування через
[TestSwap](https://testswap.yednamo.com). Через [Google Play Integrity API](https://developer.android.com/google/play/integrity)
вона підтверджує, що тестувальник встановив застосунок із Google Play за своїм особистим посиланням і відкриває його
на сертифікованому пристрої, і автоматично відмічає дні тесту.

SDK необов’язковий: без нього тестувальники відмічають дні на сайті. Застосунки з SDK позначені в каталозі,
і тестувальники охочіше їх беруть.

### Як це працює

1. Тестувальник отримує в TestSwap особисте посилання на ваш застосунок у Google Play з міткою `ts_…`.
2. Під час першого запуску SDK читає мітку через [Google Play Install Referrer](https://developer.android.com/google/play/installreferrer).
3. Раз на добу (за часом тестувальника) SDK отримує від TestSwap одноразовий код, запитує з ним токен
   Play Integrity (classic-запит) і надсилає токен у TestSwap.
4. TestSwap розшифровує токен ключами вашого застосунку й відмічає день, лише якщо Google підтвердив: ваш пакет,
   цей одноразовий код, збірку розпізнано Google Play, акаунт отримав застосунок із Google Play, сертифікований пристрій.
5. Коли тест завершено, SDK більше нічого не надсилає.
6. Звичайні користувачі (встановили не за посиланням TestSwap) нічого не надсилають: без мітки SDK мовчить.

### Ключі Play Integrity

TestSwap перевіряє токени сам, тож у кабінеті застосунку в TestSwap потрібно додати ключі відповіді Play Integrity:
у Play Console в налаштуваннях classic-запитів оберіть власні ключі шифрування відповіді, завантажте їх і вставте
ключ розшифрування й ключ перевірки (base64). Покроково:
[документація Google](https://developer.android.com/google/play/integrity/classic).
Без ключів застосунок не з’явиться в каталозі.

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
    implementation("com.github.yednamo:testswap-sdk-android:0.2.0")
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

Бібліотека сама додає дозвіл `INTERNET`. Мінімальна версія Android: 6.0 (API 23), цього вимагає Play Integrity.
Версії 0.1.x більше не підтримуються сервером.

### Які дані надсилаються

Ключ застосунку, токен участі з мітки встановлення, токен Google Play Integrity і версія SDK на
`https://testswap.yednamo.com`. TestSwap зберігає лише результат перевірки, без ідентифікаторів пристрою,
рекламних ідентифікаторів чи даних користувача. Опишіть використання Play Integrity API та TestSwap
у політиці конфіденційності й розділі Data safety у Play Console.

### Збирання

JDK 17, Android SDK (platform 36):

```bash
./gradlew :testswap:assembleRelease
```

Реліз: тег `X.Y.Z` має збігатися з `version` у `testswap/build.gradle.kts`, JitPack збирає артефакт за тегом.

## English

A small library for apps in closed testing through [TestSwap](https://testswap.yednamo.com). Using the
[Google Play Integrity API](https://developer.android.com/google/play/integrity), it confirms that a tester installed
the app from Google Play via their personal link and opens it on a certified device, and checks in test days automatically.

The SDK is optional: without it testers check in on the website. Apps with the SDK are marked in the catalog,
and testers prefer them.

### How it works

1. In TestSwap the tester gets a personal Google Play link to your app with a `ts_…` tag.
2. On first launch the SDK reads the tag via the [Google Play Install Referrer](https://developer.android.com/google/play/installreferrer).
3. Once a day (in the tester’s time zone) the SDK gets a one-time code from TestSwap, requests a Play Integrity token
   with it (a classic request) and sends the token to TestSwap.
4. TestSwap decrypts the token with your app’s keys and checks in the day only if Google confirms: your package, this
   one-time code, the build recognized by Google Play, the account got the app from Google Play, a certified device.
5. Once the test is over, the SDK stops sending anything.
6. Regular users (not installed via a TestSwap link) send nothing: without the tag the SDK stays silent.

### Play Integrity keys

TestSwap verifies tokens itself, so add your app’s Play Integrity response keys in the TestSwap dashboard: in Play
Console, choose to manage your own response encryption keys for classic requests, download them and paste the
decryption key and the verification key (base64). Step by step:
[Google’s documentation](https://developer.android.com/google/play/integrity/classic).
Without the keys the app does not appear in the catalog.

### Setup

Add JitPack to `settings.gradle.kts`, the dependency `com.github.yednamo:testswap-sdk-android:0.2.0`
to the app module, and call `TestSwap.init(this, "tsk_...")` in `Application.onCreate()` with the app key
from your TestSwap dashboard (see the Ukrainian section above for full snippets). The library adds the `INTERNET`
permission itself. Minimum Android version: 6.0 (API 23), required by Play Integrity.
Versions 0.1.x are no longer supported by the server.

### Data sent

The app key, the participation token from the install tag, the Google Play Integrity token and the SDK version,
to `https://testswap.yednamo.com`. TestSwap stores only the verification result, with no device identifiers,
advertising IDs or user data. Describe your use of the Play Integrity API and TestSwap in your privacy policy
and the Data safety section in Play Console.

## License

MIT
