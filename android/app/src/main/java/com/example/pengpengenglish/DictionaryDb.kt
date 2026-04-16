package com.example.pengpengenglish

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.LinkedHashSet
import java.util.Locale

class DictionaryDb(private val context: Context) {
    private val dbFile: File by lazy { ensureMainDbReady() }
    private val exampleDbFile: File by lazy { ensureExampleDbReady() }
    private val primaryExampleDbFile: File by lazy { ensurePrimaryExampleDbReady() }
    private val meaningCache = mutableMapOf<String, String>()
    private val exampleCache = mutableMapOf<String, ExamplePair>()
    private val phoneticCache = mutableMapOf<String, String>()
    private val primaryExampleCache = mutableMapOf<String, ExamplePair>()

    fun getPacks(): List<VocabPack> {
        val start = SystemClock.elapsedRealtime()
        val packs = mutableListOf<VocabPack>()
        openDb().use { db ->
            db.rawQuery(
                "SELECT pack_id, pack_name FROM vocab_packs ORDER BY pack_name",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    packs += VocabPack(
                        id = c.getString(0),
                        name = c.getString(1),
                        words = emptyList()
                    )
                }
            }
        }
        Log.d(PERF_TAG, "getPacks: ${packs.size} packs, cost=${SystemClock.elapsedRealtime() - start}ms")
        return packs
    }

    fun countWords(packId: String): Int {
        val start = SystemClock.elapsedRealtime()
        openDb().use { db ->
            db.rawQuery(
                "SELECT COUNT(1) FROM vocab_pack_words WHERE pack_id = ?",
                arrayOf(packId)
            ).use { c ->
                val count = if (c.moveToFirst()) c.getInt(0) else 0
                Log.d(PERF_TAG, "countWords($packId): $count, cost=${SystemClock.elapsedRealtime() - start}ms")
                return count
            }
        }
    }

    fun getWordsPage(packId: String, limit: Int, offset: Int): List<WordEntry> {
        val start = SystemClock.elapsedRealtime()
        val words = mutableListOf<WordEntry>()
        openDb().use { db ->
            db.rawQuery(
                """
                SELECT vpw.word
                FROM vocab_pack_words vpw
                WHERE vpw.pack_id = ?
                ORDER BY vpw.line_no
                LIMIT ? OFFSET ?
                """.trimIndent(),
                arrayOf(packId, limit.toString(), offset.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val word = c.getString(0)
                    val example = lookupExample(word, packId)
                    words += WordEntry(
                        word = word,
                        example = example.en,
                        exampleCn = example.cn
                    )
                }
            }
        }
        Log.d(
            PERF_TAG,
            "getWordsPage($packId, limit=$limit, offset=$offset): ${words.size} rows, cost=${SystemClock.elapsedRealtime() - start}ms"
        )
        return words
    }

    fun countSearchWords(keyword: String): Int {
        val q = keyword.trim()
        if (q.isBlank()) return 0
        openDb().use { db ->
            db.rawQuery(
                """
                SELECT COUNT(1)
                FROM ecdict_entries
                WHERE lower(word) LIKE lower(?)
                """.trimIndent(),
                arrayOf("$q%")
            ).use { c ->
                return if (c.moveToFirst()) c.getInt(0) else 0
            }
        }
    }

    fun searchWords(keyword: String, limit: Int = 30, offset: Int = 0): List<WordEntry> {
        val q = keyword.trim()
        if (q.isBlank()) return emptyList()
        val words = mutableListOf<WordEntry>()
        openDb().use { db ->
            db.rawQuery(
                """
                SELECT word
                FROM ecdict_entries
                WHERE lower(word) LIKE lower(?)
                ORDER BY CASE WHEN lower(word) = lower(?) THEN 0 ELSE 1 END, word
                LIMIT ? OFFSET ?
                """.trimIndent(),
                arrayOf("$q%", q, limit.toString(), offset.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val word = c.getString(0) ?: continue
                    val example = lookupExample(word, null)
                    words += WordEntry(
                        word = word,
                        example = example.en,
                        exampleCn = example.cn
                    )
                }
            }
        }
        return words
    }

    private fun openDb(): SQLiteDatabase {
        return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    }

    private fun openExampleDb(): SQLiteDatabase {
        return SQLiteDatabase.openDatabase(exampleDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun openPrimaryExampleDb(): SQLiteDatabase {
        return SQLiteDatabase.openDatabase(
            primaryExampleDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }

    private fun ensureMainDbReady(): File {
        val start = SystemClock.elapsedRealtime()
        val outFile = File(context.noBackupFilesDir, "ecdict.db")
        val prefs = context.getSharedPreferences("dictionary_db_meta", Context.MODE_PRIVATE)
        val copiedVersion = prefs.getInt("asset_db_version", 0)
        val needCopy = !outFile.exists() || outFile.length() == 0L || copiedVersion != ASSET_DB_VERSION
        if (needCopy) {
            context.assets.open("ecdict.db").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            prefs.edit().putInt("asset_db_version", ASSET_DB_VERSION).apply()
        }
        ensureSchema(outFile)
        ensureBundledPacks(outFile)
        Log.d(PERF_TAG, "ensureMainDbReady: cost=${SystemClock.elapsedRealtime() - start}ms")
        return outFile
    }

    private fun ensureBundledPacks(file: File) {
        val start = SystemClock.elapsedRealtime()
        val words = loadWordListAsset("packs/primary_school_words.txt")
        if (words.isEmpty()) return
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO vocab_packs(pack_id, pack_name, source, word_count, updated_at)
                    VALUES(?, ?, ?, ?, datetime('now', 'localtime'))
                    """.trimIndent(),
                    arrayOf<Any>(PRIMARY_PACK_ID, PRIMARY_PACK_NAME, "builtin", words.size)
                )
                db.execSQL("DELETE FROM vocab_pack_words WHERE pack_id = ?", arrayOf(PRIMARY_PACK_ID))
                val stmt = db.compileStatement(
                    "INSERT OR REPLACE INTO vocab_pack_words(pack_id, word, line_no) VALUES(?, ?, ?)"
                )
                words.forEachIndexed { index, word ->
                    stmt.clearBindings()
                    stmt.bindString(1, PRIMARY_PACK_ID)
                    stmt.bindString(2, word)
                    stmt.bindLong(3, (index + 1).toLong())
                    stmt.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        Log.d(PERF_TAG, "ensureBundledPacks: words=${words.size}, cost=${SystemClock.elapsedRealtime() - start}ms")
    }

    private fun loadWordListAsset(path: String): List<String> {
        val ordered = LinkedHashSet<String>()
        return try {
            context.assets.open(path).bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val word = raw.trim()
                    if (word.isNotBlank()) {
                        ordered += word
                    }
                }
            }
            ordered.toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun ensureSchema(file: File) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val columns = db.rawQuery("PRAGMA table_info(vocab_pack_words)", null).use { c ->
                buildSet {
                    while (c.moveToNext()) add(c.getString(1))
                }
            }
            if (!columns.contains("example_sentence")) {
                db.execSQL("ALTER TABLE vocab_pack_words ADD COLUMN example_sentence TEXT")
            }
        }
    }

    private fun ensureExampleDbReady(): File {
        val outFile = File(context.noBackupFilesDir, "example_sentence.db")
        val prefs = context.getSharedPreferences("dictionary_db_meta", Context.MODE_PRIVATE)
        val copiedVersion = prefs.getInt("example_db_version", 0)
        val needCopy = !outFile.exists() || outFile.length() == 0L || copiedVersion != EXAMPLE_DB_VERSION
        if (needCopy) {
            context.assets.open("example_sentence.db").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            prefs.edit().putInt("example_db_version", EXAMPLE_DB_VERSION).apply()
        }
        return outFile
    }

    private fun ensurePrimaryExampleDbReady(): File {
        val start = SystemClock.elapsedRealtime()
        val outFile = File(context.noBackupFilesDir, "primary_examples.db")
        val prefs = context.getSharedPreferences("dictionary_db_meta", Context.MODE_PRIVATE)
        val builtVersion = prefs.getInt("primary_example_db_version", 0)
        val needBuild = !outFile.exists() || outFile.length() == 0L || builtVersion != PRIMARY_EXAMPLE_DB_VERSION
        if (needBuild) {
            buildPrimaryExampleDb(outFile)
            prefs.edit().putInt("primary_example_db_version", PRIMARY_EXAMPLE_DB_VERSION).apply()
        }
        Log.d(PERF_TAG, "ensurePrimaryExampleDbReady(needBuild=$needBuild): cost=${SystemClock.elapsedRealtime() - start}ms")
        return outFile
    }

    private fun buildPrimaryExampleDb(outFile: File) {
        if (outFile.exists()) outFile.delete()
        SQLiteDatabase.openOrCreateDatabase(outFile, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS primary_examples(
                    word TEXT NOT NULL,
                    sentence_en TEXT NOT NULL,
                    sentence_cn TEXT NOT NULL,
                    priority INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(word, sentence_en)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_primary_examples_word_pri ON primary_examples(word, priority)")

            val words = loadWordListAsset("packs/primary_school_words.txt")
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotBlank() }
                .distinct()
            val pairs = loadPrimaryExamplePairs("packs/primary_school_examples.txt")
            if (words.isEmpty() || pairs.isEmpty()) return

            val stmt = db.compileStatement(
                "INSERT OR IGNORE INTO primary_examples(word, sentence_en, sentence_cn, priority) VALUES(?, ?, ?, ?)"
            )
            db.beginTransaction()
            try {
                pairs.forEachIndexed { index, pair ->
                    for (word in words) {
                        if (!containsWordOrPhrase(pair.en, word)) continue
                        stmt.clearBindings()
                        stmt.bindString(1, word)
                        stmt.bindString(2, pair.en)
                        stmt.bindString(3, pair.cn)
                        stmt.bindLong(4, index.toLong())
                        stmt.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun lookupExample(word: String, packId: String?): ExamplePair {
        if (packId == PRIMARY_PACK_ID) {
            return lookupPrimaryExample(word)
        }
        val key = word.lowercase()
        exampleCache[key]?.let { return it }

        val example = openExampleDb().use { db ->
            db.rawQuery(
                """
                SELECT sentence_en, COALESCE(sentence_cn, '')
                FROM example_sentences
                WHERE lower(word) = lower(?)
                  AND sentence_en IS NOT NULL
                  AND sentence_en <> ''
                ORDER BY COALESCE(heat, 0) DESC, id ASC
                LIMIT 1
                """.trimIndent(),
                arrayOf(word)
            ).use { c ->
                if (c.moveToFirst()) {
                    ExamplePair(
                        en = c.getString(0) ?: "",
                        cn = c.getString(1) ?: ""
                    )
                } else {
                    ExamplePair("", "")
                }
            }
        }
        exampleCache[key] = example
        return example
    }

    private fun lookupPrimaryExample(word: String): ExamplePair {
        val key = word.lowercase(Locale.ROOT)
        primaryExampleCache[key]?.let { return it }
        val example = openPrimaryExampleDb().use { db ->
            db.rawQuery(
                """
                SELECT sentence_en, sentence_cn
                FROM primary_examples
                WHERE word = ?
                ORDER BY priority ASC
                LIMIT 1
                """.trimIndent(),
                arrayOf(key)
            ).use { c ->
                if (c.moveToFirst()) {
                    ExamplePair(
                        en = c.getString(0) ?: "",
                        cn = c.getString(1) ?: ""
                    )
                } else {
                    ExamplePair("", "")
                }
            }
        }
        primaryExampleCache[key] = example
        return example
    }

    private fun loadPrimaryExamplePairs(path: String): List<ExamplePair> {
        val lines = try {
            context.assets.open(path).bufferedReader().readLines()
        } catch (_: Throwable) {
            emptyList()
        }
        if (lines.isEmpty()) return emptyList()
        val cleaned = lines.map { it.trim() }.filter { it.isNotBlank() }
        val pairs = mutableListOf<ExamplePair>()
        var pendingEn: String? = null
        for (line in cleaned) {
            if (looksLikeEnglishSentence(line)) {
                pendingEn = line
                continue
            }
            if (pendingEn != null && looksLikeChineseSentence(line)) {
                pairs += ExamplePair(en = pendingEn!!, cn = line)
                pendingEn = null
            }
        }
        return pairs
    }

    private fun looksLikeEnglishSentence(text: String): Boolean {
        val hasAsciiWord = ENGLISH_TOKEN_REGEX.containsMatchIn(text)
        val hasCjk = CJK_REGEX.containsMatchIn(text)
        return hasAsciiWord && !hasCjk
    }

    private fun looksLikeChineseSentence(text: String): Boolean {
        return CJK_REGEX.containsMatchIn(text)
    }

    private fun containsWordOrPhrase(sentence: String, word: String): Boolean {
        val sentenceLower = sentence.lowercase(Locale.ROOT)
        return if (word.contains(' ')) {
            val idx = sentenceLower.indexOf(word)
            if (idx < 0) false else {
                val beforeOk = idx == 0 || !sentenceLower[idx - 1].isLetter()
                val end = idx + word.length
                val afterOk = end >= sentenceLower.length || !sentenceLower[end].isLetter()
                beforeOk && afterOk
            }
        } else {
            Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(sentenceLower)
        }
    }

    fun lookupMeaning(word: String): String {
        val key = word.lowercase()
        meaningCache[key]?.let { return it }

        val meaning = openDb().use { db ->
            db.rawQuery(
                """
                SELECT COALESCE(NULLIF(translation, ''), NULLIF(definition, ''), '')
                FROM ecdict_entries
                WHERE lower(word) = lower(?)
                LIMIT 1
                """.trimIndent(),
                arrayOf(word)
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            }
        }
        meaningCache[key] = meaning
        return meaning
    }

    fun lookupPhonetic(word: String): String {
        val key = word.lowercase()
        phoneticCache[key]?.let { return it }

        val phonetic = openDb().use { db ->
            db.rawQuery(
                """
                SELECT COALESCE(NULLIF(phonetic, ''), '')
                FROM ecdict_entries
                WHERE lower(word) = lower(?)
                LIMIT 1
                """.trimIndent(),
                arrayOf(word)
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            }
        }
        phoneticCache[key] = phonetic
        return phonetic
    }

    companion object {
        // Bump this when app/assets/ecdict.db is refreshed.
        private const val ASSET_DB_VERSION = 3
        private const val EXAMPLE_DB_VERSION = 1
        private const val PRIMARY_EXAMPLE_DB_VERSION = 1
        private const val PRIMARY_PACK_ID = "primary_school"
        private const val PRIMARY_PACK_NAME = "小学英语大纲词汇"
        private const val PERF_TAG = "PPStartPerf"
        private val CJK_REGEX = Regex("[\\u4e00-\\u9fff]")
        private val ENGLISH_TOKEN_REGEX = Regex("[A-Za-z]+")
    }
}

data class WordEntry(
    val word: String,
    val example: String,
    val exampleCn: String
)

data class ExamplePair(
    val en: String,
    val cn: String
)
