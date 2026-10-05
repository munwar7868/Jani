package com.munawar.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

class ContactResolver(private val ctx: Context) {

    data class Hit(val name: String, val number: String)

    private val groups: List<Set<String>> = listOf(
        setOf("امی", "امی جان", "ammi", "ami", "mummy", "mom", "mother", "maa", "mama", "माँ", "मम्मी", "अम्मी"),
        setOf("ابو", "ابو جان", "abbu", "abu", "papa", "dad", "father", "पापा", "अब्बू"),
        setOf("بھائی", "bhai", "bhaiya", "brother", "भाई", "भैया"),
        setOf("بہن", "baji", "api", "behan", "sister", "बहन", "दीदी"),
        setOf("بیوی", "wife", "begum", "पत्नी"),
        setOf("دادی", "dadi", "दादी"),
        setOf("نانی", "nani", "नानी"),
    )

    fun find(query: String): Hit? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val group = groups.firstOrNull { g -> g.any { it == q } } ?: emptySet()
        val terms = (listOf(q) + group).toSet()

        var best: Hit? = null
        var bestScore = 0
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        ) ?: return null
        cursor.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val n = name.lowercase()
                var score = 0
                for (t in terms) {
                    val s = when {
                        n == t -> 100
                        n.startsWith(t) -> 80
                        n.split(" ").any { it == t } -> 75
                        n.contains(t) -> 60
                        else -> 0
                    }
                    if (s > score) score = s
                }
                if (score == 0 && q.length >= 4) {
                    val d = n.split(" ").minOf { lev(it, q) }
                    if (d <= 1) score = 40 else if (q.length >= 6 && d <= 2) score = 35
                }
                if (score > bestScore) {
                    bestScore = score
                    best = Hit(name, num)
                }
            }
        }
        return best
    }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
}
