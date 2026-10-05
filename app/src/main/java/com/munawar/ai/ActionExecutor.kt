package com.munawar.ai

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Runs whitelisted actions. Nothing here executes arbitrary code from the AI. */
class ActionExecutor(private val ctx: Context) {

    var lang: String = "ur-PK"
    var countryCode: String = "92"

    private val contacts = ContactResolver(ctx)
    private var torchOn = false

    data class Out(val message: String?, val ok: Boolean = true, val info: Boolean = false)
    private data class Target(val name: String, val number: String)

    private fun l(ur: String, hi: String, en: String) = tr(lang, ur, hi, en)

    suspend fun run(action: ActionType, p: Map<String, String>): Out = try {
        when (action) {
            ActionType.NONE, ActionType.STOP -> Out(null)
            ActionType.CALL -> call(p)
            ActionType.SMS -> sms(p)
            ActionType.WHATSAPP -> whatsapp(p)
            ActionType.OPEN_APP -> openApp(p["app"].orEmpty())
            ActionType.TORCH -> torch(p["on"]?.toBooleanStrictOrNull() ?: !torchOn)
            ActionType.ALARM -> alarm(p)
            ActionType.TIMER -> timer(p)
            ActionType.YOUTUBE -> youtube(p["query"].orEmpty())
            ActionType.MAPS -> maps(p["query"].orEmpty())
            ActionType.WEB_SEARCH -> webSearch(p["query"].orEmpty())
            ActionType.VOLUME -> volume(p)
            ActionType.SETTINGS -> settings(p["page"].orEmpty())
            ActionType.CAMERA -> camera()
            ActionType.SAVE_NOTE -> saveNote(p["text"].orEmpty())
            ActionType.READ_NOTES -> readNotes()
            ActionType.BATTERY -> battery()
            ActionType.TIME -> time()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ActivityNotFoundException) {
        Out(l("اس کام کے لیے کوئی ایپ نہیں ملی", "इस काम के लिए कोई ऐप नहीं मिला", "I couldn't find an app for that"), ok = false)
    } catch (e: SecurityException) {
        Out(l("اس کام کی اجازت نہیں ہے، سیٹنگز میں اجازت دیں", "इसकी अनुमति नहीं है, सेटिंग्स में अनुमति दें", "Permission is missing. Please allow it in settings."), ok = false)
    } catch (e: Exception) {
        Out(l("یہ کام نہیں ہو سکا", "यह काम नहीं हो सका", "I couldn't do that"), ok = false)
    }

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun notFound(name: String) = l(
        "مجھے \"$name\" نام کا رابطہ نہیں ملا",
        "मुझे \"$name\" नाम का संपर्क नहीं मिला",
        "I couldn't find a contact named $name",
    )

    private suspend fun resolve(p: Map<String, String>): Target? {
        val raw = p["number"].orEmpty().filter { it.isDigit() || it == '+' }
        if (raw.length >= 5) return Target(raw, raw)
        val name = p["name"].orEmpty().trim()
        if (name.isEmpty()) return null
        val hit = withContext(Dispatchers.IO) { contacts.find(name) } ?: return null
        return Target(hit.name, hit.number)
    }

    private fun waDigits(n: String): String {
        val s = n.filter { it.isDigit() || it == '+' }
        return when {
            s.startsWith("+") -> s.drop(1)
            s.startsWith("00") -> s.drop(2)
            s.startsWith("0") -> countryCode + s.drop(1)
            else -> s
        }
    }

    // ---------- communication ----------

    private suspend fun call(p: Map<String, String>): Out {
        if (p["number"].isNullOrBlank() && p["name"].isNullOrBlank()) {
            return Out(l("کس کو کال کروں؟", "किसे कॉल करूँ?", "Whom should I call?"), ok = false)
        }
        val t = resolve(p) ?: return Out(notFound(p["name"].orEmpty()), ok = false)
        val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL
        start(Intent(action, Uri.fromParts("tel", t.number, null)))
        return Out(l("${t.name} کو کال لگا رہا ہوں", "${t.name} को कॉल लगा रहा हूँ", "Calling ${t.name}"))
    }

    private suspend fun sms(p: Map<String, String>): Out {
        val body = p["text"].orEmpty()
        val wantsContact = !(p["name"].isNullOrBlank() && p["number"].isNullOrBlank())
        val t = if (wantsContact) (resolve(p) ?: return Out(notFound(p["name"].orEmpty()), ok = false)) else null
        val i = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", t?.number.orEmpty(), null))
        i.putExtra("sms_body", body)
        start(i)
        return Out(l("پیغام تیار ہے، بھیجنے کے لیے سینڈ دبائیں", "संदेश तैयार है, भेजने के लिए सेंड दबाएँ", "Message is ready. Tap send."))
    }

    private suspend fun whatsapp(p: Map<String, String>): Out {
        val body = p["text"].orEmpty()
        val wantsContact = !(p["name"].isNullOrBlank() && p["number"].isNullOrBlank())
        val t = if (wantsContact) (resolve(p) ?: return Out(notFound(p["name"].orEmpty()), ok = false)) else null
        val digits = if (t != null) waDigits(t.number) else ""
        val url = "https://wa.me/" + digits + "?text=" + Uri.encode(body)
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return Out(l("واٹس ایپ کھول رہا ہوں، بھیجنے کے لیے سینڈ دبائیں", "व्हाट्सऐप खोल रहा हूँ, भेजने के लिए सेंड दबाएँ", "Opening WhatsApp. Tap send."))
    }

    // ---------- apps ----------

    private val aliases: List<Pair<List<String>, String>> = listOf(
        listOf("whatsapp", "whats app", "واٹس ایپ", "واٹس اپ", "واٹسایپ", "व्हाट्सएप", "व्हाट्सऐप", "व्हाट्स ऐप", "वॉट्सऐप") to "com.whatsapp",
        listOf("youtube", "یوٹیوب", "यूट्यूब", "यू ट्यूब") to "com.google.android.youtube",
        listOf("instagram", "انسٹاگرام", "इंस्टाग्राम") to "com.instagram.android",
        listOf("facebook", "فیس بک", "فیسبک", "फेसबुक") to "com.facebook.katana",
        listOf("chrome", "کروم", "क्रोम") to "com.android.chrome",
        listOf("gmail", "جی میل", "जीमेल") to "com.google.android.gm",
        listOf("maps", "google maps", "میپس", "नक्शा", "मैप्स", "मैप") to "com.google.android.apps.maps",
        listOf("play store", "playstore", "پلے سٹور", "प्ले स्टोर") to "com.android.vending",
        listOf("telegram", "ٹیلیگرام", "टेलीग्राम") to "org.telegram.messenger",
        listOf("snapchat", "اسنیپ چیٹ", "स्नैपचैट") to "com.snapchat.android",
        listOf("spotify", "اسپاٹیفائی") to "com.spotify.music",
    )
    private val settingsNames = setOf("settings", "setting", "سیٹنگ", "سیٹنگز", "سیٹنگس", "सेटिंग", "सेटिंग्स")

    private fun openedMsg() = l("کھول رہا ہوں", "खोल रहा हूँ", "Opening it")

    private fun appNotFound(q: String) = l(
        "مجھے \"$q\" ایپ نہیں ملی", "मुझे \"$q\" ऐप नहीं मिला", "I couldn't find an app called $q",
    )

    private fun openApp(nameRaw: String): Out {
        val q = nameRaw.trim().lowercase()
        if (q.isBlank()) return Out(l("کون سی ایپ کھولوں؟", "कौन सा ऐप खोलूँ?", "Which app should I open?"), ok = false)
        if (q in settingsNames) {
            start(Intent(Settings.ACTION_SETTINGS))
            return Out(openedMsg())
        }
        val pm = ctx.packageManager
        val aliasPkg = aliases.firstOrNull { q in it.first }?.second
        if (aliasPkg != null) {
            val li = pm.getLaunchIntentForPackage(aliasPkg)
            if (li != null) {
                start(li)
                return Out(openedMsg())
            }
        }
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var bestPkg: String? = null
        var best = 0
        for (ri in pm.queryIntentActivities(main, 0)) {
            val label = ri.loadLabel(pm).toString().lowercase()
            val s = when {
                label == q -> 100
                label.startsWith(q) -> 80
                label.contains(q) -> 60
                label.length >= 3 && q.contains(label) -> 50
                else -> 0
            }
            if (s > best) {
                best = s
                bestPkg = ri.activityInfo.packageName
            }
        }
        val pkg = bestPkg ?: return Out(appNotFound(q), ok = false)
        val li = pm.getLaunchIntentForPackage(pkg) ?: return Out(appNotFound(q), ok = false)
        start(li)
        return Out(openedMsg())
    }

    // ---------- device ----------

    private fun torch(on: Boolean): Out {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Out(l("اس فون میں ٹارچ نہیں ملی", "इस फ़ोन में टॉर्च नहीं मिली", "No flashlight found"), ok = false)
        cm.setTorchMode(id, on)
        torchOn = on
        return if (on) Out(l("ٹارچ آن کر دی", "टॉर्च चालू कर दी", "Flashlight on"))
        else Out(l("ٹارچ بند کر دی", "टॉर्च बंद कर दी", "Flashlight off"))
    }

    private fun alarm(p: Map<String, String>): Out {
        val h = p["hour"]?.trim()?.toIntOrNull()
        val m = p["minute"]?.trim()?.toIntOrNull() ?: 0
        if (h == null || h !in 0..23 || m !in 0..59) {
            return Out(l("الارم کس وقت کا لگاؤں؟", "अलार्म किस समय का लगाऊँ?", "What time should I set the alarm for?"), ok = false)
        }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, p["label"].orEmpty().ifBlank { "Munawar AI" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        start(i)
        val t = "%02d:%02d".format(h, m)
        return Out(l("الارم $t پر لگا دیا", "अलार्म $t पर लगा दिया", "Alarm set for $t"))
    }

    private fun timer(p: Map<String, String>): Out {
        val sec = p["seconds"]?.trim()?.toIntOrNull()
        if (sec == null || sec !in 1..86400) {
            return Out(l("ٹائمر کتنی دیر کا لگاؤں؟", "टाइमर कितनी देर का लगाऊँ?", "How long should the timer be?"), ok = false)
        }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_MESSAGE, p["label"].orEmpty().ifBlank { "Munawar AI" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        start(i)
        return Out(l("ٹائمر لگا دیا", "टाइमर लगा दिया", "Timer started"))
    }

    private fun youtube(q: String): Out {
        if (q.isBlank()) {
            val li = ctx.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            if (li != null) {
                start(li)
                return Out(openedMsg())
            }
        }
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        return Out(l("یوٹیوب پر ڈھونڈ رہا ہوں", "यूट्यूब पर खोज रहा हूँ", "Searching YouTube"))
    }

    private fun maps(q: String): Out {
        if (q.isBlank()) return Out(l("کہاں جانا ہے؟", "कहाँ जाना है?", "Where do you want to go?"), ok = false)
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)))
            i.setPackage("com.google.android.apps.maps")
            start(i)
        } catch (e: ActivityNotFoundException) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
        }
        return Out(l("نقشہ کھول رہا ہوں", "नक्शा खोल रहा हूँ", "Opening maps"))
    }

    private fun webSearch(q: String): Out {
        if (q.isBlank()) return Out(l("کیا تلاش کروں؟", "क्या खोजूँ?", "What should I search for?"), ok = false)
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        return Out(l("تلاش کر رہا ہوں", "खोज रहा हूँ", "Searching"))
    }

    private fun volume(p: Map<String, String>): Out {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val level = p["level"]?.trim()?.toIntOrNull()
        val step = maxOf(1, max / 5)
        val target = when {
            level != null -> max * level.coerceIn(0, 100) / 100
            p["direction"] == "up" -> cur + step
            p["direction"] == "down" -> cur - step
            p["direction"] == "max" -> max
            p["direction"] == "mute" -> 0
            else -> return Out(l("آواز کتنی کروں؟", "आवाज़ कितनी करूँ?", "How loud should it be?"), ok = false)
        }.coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return Out(l("آواز ٹھیک کر دی", "आवाज़ ठीक कर दी", "Volume adjusted"))
    }

    private fun settings(page: String): Out {
        val action = when (page.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        start(Intent(action))
        return Out(l("سیٹنگز کھول رہا ہوں", "सेटिंग्स खोल रहा हूँ", "Opening settings"))
    }

    private fun camera(): Out {
        start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        return Out(l("کیمرا کھول رہا ہوں", "कैमरा खोल रहा हूँ", "Opening the camera"))
    }

    // ---------- info ----------

    private fun battery(): Out {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val msg = if (charging) {
            l("بیٹری $pct فیصد ہے اور چارج ہو رہی ہے", "बैटरी $pct प्रतिशत है और चार्ज हो रही है", "Battery is at $pct percent and charging")
        } else {
            l("بیٹری $pct فیصد ہے", "बैटरी $pct प्रतिशत है", "Battery is at $pct percent")
        }
        return Out(msg, info = true)
    }

    private fun time(): Out {
        val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        return Out(l("اس وقت $t ہوا ہے", "अभी $t हुआ है", "It is $t"), info = true)
    }

    private val notesPrefs get() = ctx.getSharedPreferences("munawar_notes", Context.MODE_PRIVATE)

    private fun saveNote(t: String): Out {
        if (t.isBlank()) return Out(l("کیا لکھوں؟", "क्या लिखूँ?", "What should I note down?"), ok = false)
        val old = notesPrefs.getString("n", "").orEmpty()
        val clean = t.replace("\n", " ")
        notesPrefs.edit().putString("n", if (old.isEmpty()) clean else old + "\n" + clean).apply()
        return Out(l("نوٹ محفوظ کر لیا", "नोट सेव कर लिया", "Note saved"))
    }

    private fun readNotes(): Out {
        val list = notesPrefs.getString("n", "").orEmpty().split("\n").filter { it.isNotBlank() }.takeLast(5)
        if (list.isEmpty()) return Out(l("آپ کا کوئی نوٹ نہیں ہے", "आपका कोई नोट नहीं है", "You have no notes"), info = true)
        return Out(list.joinToString(". "), info = true)
    }
}
