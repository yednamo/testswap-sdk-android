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
7. Після повного перевстановлення SDK починає спочатку (порівнює `firstInstallTime` зі збереженим): стан,
   який Android Auto Backup відновив із попереднього встановлення, не заважає.
8. Якщо застосунок встановлено інакше (пошук у Google Play, сторінка «Стати тестувальником», веб-версія
   Google Play), мітки в Install Referrer немає. Тоді тестувальник натискає на сторінці тесту «Я встановив
   застосунок, зв'язати»: посилання `testswap://<пакет>/link?token=…` відкриває застосунок, `LinkActivity`
   з бібліотеки зберігає мітку, і далі все працює як звичайно. Activity оголошує маніфест бібліотеки
   (хост = `applicationId`), у коді застосунку нічого додавати не треба. Play Integrity перевіряє
   встановлення так само.

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
    implementation("com.github.yednamo:testswap-sdk-android:0.3.0")
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

Бібліотека сама додає дозвіл `INTERNET`. Мінімальна версія Android: 8.0 (API 26).
Версії 0.1.x більше не підтримуються сервером.

SDK працює лише в основному процесі застосунку й запускає перевірку лише під час старту Activity:
фоновий запуск процесу (push, WorkManager, віджет) день не відмічає. Для діагностики
`TestSwap.setDebug(true)` вмикає докладні повідомлення в Logcat (тег `TestSwap`).

### Які дані надсилаються

Ключ застосунку, токен участі (з мітки встановлення або з посилання «зв'язати»), токен Google Play Integrity,
версія SDK, а з 0.3.0 ще модель пристрою з версією Android («Samsung SM-A525F, Android 14») і версія збірки
застосунку («0.6.1 (13)») на `https://testswap.yednamo.com`. TestSwap зберігає результат перевірки, модель
і версії, які бачить розробник у кабінеті; ідентифікатори пристрою, рекламні ідентифікатори чи дані користувача
не збираються. У журналах запитів сервера також лишаються версія SDK та IP-адреса клієнта. Опишіть використання Play Integrity API та TestSwap
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
7. After a full reinstall the SDK starts over (it compares `firstInstallTime` with the stored one): state restored
   by Android Auto Backup from a previous install does not get in the way.
8. If the app was installed another way (Google Play search, the “Become a tester” page, the Google Play
   website), there is no tag in the Install Referrer. The tester then presses “I installed the app, link it”
   on the test page: the link `testswap://<package>/link?token=…` opens the app, the library’s `LinkActivity`
   stores the tag, and everything works as usual. The activity is declared by the library manifest
   (host = `applicationId`); nothing to add in the app code. Play Integrity still verifies the install.

### Play Integrity keys

TestSwap verifies tokens itself, so add your app’s Play Integrity response keys in the TestSwap dashboard: in Play
Console, choose to manage your own response encryption keys for classic requests, download them and paste the
decryption key and the verification key (base64). Step by step:
[Google’s documentation](https://developer.android.com/google/play/integrity/classic).
Without the keys the app does not appear in the catalog.

### Setup

Add JitPack to `settings.gradle.kts`, the dependency `com.github.yednamo:testswap-sdk-android:0.3.0`
to the app module, and call `TestSwap.init(this, "tsk_...")` in `Application.onCreate()` with the app key
from your TestSwap dashboard (see the Ukrainian section above for full snippets). The library adds the `INTERNET`
permission itself. Minimum Android version: 8.0 (API 26).
Versions 0.1.x are no longer supported by the server.

The SDK runs only in the app's main process and starts the check only when an Activity starts: a background
process start (push, WorkManager, widget) does not check in a day. For diagnostics, `TestSwap.setDebug(true)`
enables verbose Logcat messages (tag `TestSwap`).

### Data sent

The app key, the participation token (from the install tag or from the “link it” link), the Google Play Integrity
token, the SDK version and, since 0.3.0, the device model with Android version (“Samsung SM-A525F, Android 14”)
and the app build version (“0.6.1 (13)”), to `https://testswap.yednamo.com`. TestSwap stores the verification
result, the model and the versions, which the developer sees in the dashboard; no device identifiers, advertising
IDs or user data are collected. The server's request logs also keep the SDK version and the client IP address.
Describe your use of the Play Integrity API and TestSwap in your privacy policy
and the Data safety section in Play Console.

## License

MIT
