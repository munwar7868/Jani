# Munawar AI — Build Guide

Android voice assistant (Kotlin + Jetpack Compose). Urdu / Hindi / English. Mic dabao, phir live call ki tarah sunta rehta hai.

> **Zaroori note:** Ye project is guide ke saath bina compile kiye diya gaya hai (banane wali jagah par Android SDK nahi tha). Pehli build par agar koi error aaye to poora error log copy karke bhej dein, wo theek ho jayega.

---

## 1. Kya kya hai

| Feature | Kaise kaam karta hai |
|---|---|
| Siri-style rangeen orb | Compose Canvas, awaaz ke saath hilta hai, 4 states (off / sun raha / soch raha / bol raha) |
| Urdu / Hindi / English voice | Android SpeechRecognizer + Android TTS (`ur-PK`, `hi-IN`, `en-US`, `en-IN`) |
| Mic dabao → 24/7 live call | Foreground service (microphone), band karne ke liye mic dobara dabao ya notification se Stop |
| Wake word "Hey Munawar" (optional) | Settings mein ON; jawab ke baad 10 second tak bina wake word ke follow-up chalta hai |
| 7 personalities | Normal, Friendly, Girlfriend, Boyfriend, Teacher, Funny, Professional |
| Do-stage pipeline | Stage 1: offline instant parser; Stage 2: Gemini (JSON) |
| Call (naam ya number se) | Contacts + fuzzy match + Urdu naam (امی، ابو، بھائی …); call se pehle "haan/nahi" confirm |
| SMS, WhatsApp | Message tayar karke app khol deta hai, aap Send dabate hain (`wa.me` link) |
| App kholna | Naam se (alias + installed apps ke label se) |
| Torch, Volume, Camera | Direct system calls |
| Alarm, Timer | System clock app ke zariye |
| YouTube, Maps, Google search | Intent / URL |
| Settings pages | wifi, bluetooth, display, sound, battery, location |
| Notes | "note likho …" / "mere notes sunao" (phone par local) |
| Battery, Time | Bina internet ke jawab |
| Personality + voice speed/pitch | Settings screen |
| API key setup + test | Test pass hone tak app aage nahi chalti |

**Safety design:** AI sirf JSON deta hai aur sirf `ActionType` whitelist ki cheezen chal sakti hain. AI koi code generate ya execute nahi kar sakta. Call ke liye confirmation zaroori hai.

---

## 2. Build karna

### A) Android Studio (sabse aasaan)
1. Zip extract karein, **Open** se folder `MunawarAI` kholein.
2. Gradle sync hone dein (internet chahiye). Android Studio khud `gradle-wrapper.jar` bana leta hai.
3. **Build → Build APK(s)**, ya phone connect karke Run.

Requirements: JDK 17, Android SDK 34.

### B) AndroidIDE Pro (phone par)
1. Zip extract karke project import karein.
2. Agar AndroidIDE ka Gradle/AGP version alag ho to `build.gradle` (root) mein `8.5.2` aur `1.9.24` aur `gradle/wrapper/gradle-wrapper.properties` mein `gradle-8.7` ko apne IDE ke supported version se badal dein. Kotlin version badlo to `app/build.gradle` mein `kotlinCompilerExtensionVersion` bhi uske mutabiq badlein (Kotlin 1.9.24 ↔ 1.5.14).
3. Build → assembleDebug.

### C) GitHub se APK
1. Project GitHub par push karein.
2. **Actions → Build APK → Run workflow**.
3. Khatam hone par **Artifacts → MunawarAI-debug-apk** download karein.

(`gradle-wrapper.jar` binary hai, is liye nahi diya; workflow Gradle 8.7 khud install karta hai.)

---

## 3. Pehli baar chalana

1. App kholein → **Gemini API key** daalein (https://aistudio.google.com/apikey se free milti hai).
2. **Test & Save** dabayein. Model default `gemini-2.5-flash` hai; agar "404 / model not found" aaye to model ka naam badal kar dobara test karein.
3. Home par **mic** dabayein → Microphone, Contacts, Call, Notifications ki ijazat dein.
4. Settings (⚙) mein ye bhi dein:
   - **Display over other apps**: taake app band/background hone par bhi YouTube, Maps, WhatsApp khul sakein.
   - **Battery optimisation off**: taake 24/7 sunna na ruke.
5. Urdu bolne ke liye phone mein **Google Speech Services** aur **Urdu TTS voice** installed honi chahiye (Settings → Language → Text-to-speech).

### Example commands
- "امی کو کال کرو" / "Ammi ko call karo"
- "ٹارچ جلاؤ" / "torch band karo"
- "واٹس ایپ کھولو" / "YouTube kholo"
- "Ali ko WhatsApp par likho ke main late aaunga" (Gemini samjhega)
- "صبح 6 بجے کا الارم لگاؤ", "10 minute ka timer"
- "آواز بڑھاؤ", "battery kitni hai", "kitne baje hain"
- "Lahore ka rasta batao" (Maps), "YouTube par naat chalao"
- "note likho: doodh lana hai", "mere notes sunao"
- "so jao" / "stop listening" → assistant band

---

## 4. Code ka naqsha

```
app/src/main/java/com/munawar/ai/
  MainActivity.kt      Compose entry
  Screens.kt           Setup, Home, Settings screens
  Ui.kt                Theme, Orb (Canvas), Bubble, MicButton
  VoiceService.kt      Foreground service (mic) + wake lock
  AssistantCore.kt     Pipeline: listen -> think -> act -> speak (+ Bus state)
  Stt.kt               SpeechRecognizer wrapper (auto-restart, error handling)
  Tts.kt               TextToSpeech wrapper (language fallback)
  LocalParser.kt       Stage 1: offline Urdu/Hindi/English commands
  GeminiClient.kt      Stage 2: Gemini REST + ActionType whitelist + system prompt
  ActionExecutor.kt    Har action ka asal kaam (intents, torch, volume ...)
  ContactResolver.kt   Contacts fuzzy match + Urdu aliases
  Prefs.kt             DataStore settings (API key sirf phone par)
  Personality.kt       7 personas
  Util.kt              tr(): Urdu/Hindi/English strings
```

Flow: `Mic → Stt → (wake word?) → LocalParser → [nahi samjha] → Gemini → ActionType check → ActionExecutor → Tts → dobara sunna`.

### Naya action kaise jodein
1. `GeminiClient.kt` mein `ActionType` mein naam jodein.
2. Wahi `systemPrompt()` mein uski line likhein (params ke saath).
3. `ActionExecutor.run()` ke `when` mein branch aur function likhein.
4. Optional: `LocalParser` mein offline keywords.

---

## 5. Masle aur hal

| Masla | Hal |
|---|---|
| "Speech recognition needs internet" | Google ka Urdu recognizer aksar online hota hai. Internet check karein ya Settings → Google → Speech mein offline language download karein. |
| "language not available" | Settings mein koi aur zubaan chunein, ya Google Speech Services update karein. |
| Awaaz Urdu mein nahi aa rahi | Text-to-speech settings mein Urdu voice install karein; warna Hindi/English par fallback hota hai. |
| Beep ki awaaz har sentence ke baad | SpeechRecognizer ki aadat hai; kuch phones par system sounds band karne se kam hoti hai. |
| Background mein app nahi khulti | "Display over other apps" ki ijazat dein. |
| Kuch der baad sunna band | Battery optimisation off karein; kuch brands (Xiaomi/Oppo/Vivo) mein "Autostart / Background" bhi allow karein. |
| 400 / 403 error | API key ghalat hai; Settings → Change API key. |
| 429 error | Free quota khatam; thori der baad try karein. |
| Gradle sync fail | Section 2-B ke version notes dekhein. |

---

## 6. Is version mein kya nahi hai (sach batana zaroori hai)

- **Gemini Live (full-duplex, beech mein tokna):** abhi SpeechRecognizer + REST + Android TTS hai. Zyada stable aur halka hai, lekin jawab ke dauran bolna (barge-in) nahi chalta.
- **Image generator, habits / Smart Modes, connectors (Gmail, Drive …), screen automation (Accessibility), PC connect:** shaamil nahi.
- **Boot par khud start:** Android 14 microphone service ko boot se start karne nahi deta, is liye har baar mic dabana hoga.
- **True wake word:** "Hey Munawar" text-matching hai (mic chalta rehta hai), low-power hardware wake word nahi.
- WhatsApp / SMS khud Send nahi karta; aap ko Send dabana hota hai (Play Store policy ke mutabiq aur safe).

Ye sab agle step mein ek ek karke jod sakte hain.
