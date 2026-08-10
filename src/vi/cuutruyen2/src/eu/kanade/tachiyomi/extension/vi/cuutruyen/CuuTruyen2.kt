package eu.kanade.tachiyomi.extension.vi.cuutruyen

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

class CuuTruyen2 : HttpSource(), ConfigurableSource {

    override val name = "Cứu Truyện 2"
    override val baseUrl = "https://cuutruyen.net"
    override val lang = "vi"
    override val supportsLatest = true

    private val apiUrl = "https://cuutruyen.net/api/v2"
    private val imageServer = "https://storage-ct.lrclib.net"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", baseUrl)
                .build()
            chain.proceed(newRequest)
        }
        .build()

    // ==================== POPULAR ====================
    override fun popularMangaRequest(page: Int): Request {
        return GET("$apiUrl/mangas?page=$page&sort=-views_count", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.body.string()
        val jsonArray = try {
            kotlinx.serialization.json.Json.parseToJsonElement(data).jsonObject["data"]?.jsonArray
        } catch (e: Exception) {
            return MangasPage(emptyList(), false)
        } ?: return MangasPage(emptyList(), false)

        val mangas = jsonArray.mapNotNull { jsonElement ->
            val obj = jsonElement.jsonObject
            SManga.create().apply {
                title = obj["name"]?.jsonPrimitive?.content ?: ""
                url = "/mangas/${obj["id"]?.jsonPrimitive?.int ?: return@mapNotNull null}"
                thumbnail_url = obj["cover_url"]?.jsonPrimitive?.content
                description = obj["description"]?.jsonPrimitive?.content
                artist = obj["author"]?.jsonPrimitive?.content
                status = parseStatus(obj["status"]?.jsonPrimitive?.content)
            }
        }

        return MangasPage(mangas, mangas.size == 20)
    }

    // ==================== LATEST ====================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$apiUrl/mangas?page=$page&sort=-updated_at", headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ==================== SEARCH ====================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val searchUrl = if (query.isNotEmpty()) {
            "$apiUrl/mangas?page=$page&search=$query"
        } else {
            "$apiUrl/mangas?page=$page&sort=-views_count"
        }
        return GET(searchUrl, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ==================== MANGA DETAILS ====================
    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        return GET("$apiUrl/mangas/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.body.string()
        val jsonObject = try {
            kotlinx.serialization.json.Json.parseToJsonElement(data).jsonObject["data"]?.jsonObject
        } catch (e: Exception) {
            return SManga.create()
        } ?: return SManga.create()

        return SManga.create().apply {
            title = jsonObject["name"]?.jsonPrimitive?.content ?: ""
            description = jsonObject["description"]?.jsonPrimitive?.content
            thumbnail_url = jsonObject["cover_url"]?.jsonPrimitive?.content
            artist = jsonObject["author"]?.jsonPrimitive?.content
            author = jsonObject["author"]?.jsonPrimitive?.content
            genre = jsonObject["genres"]?.jsonArray?.joinToString { it.jsonPrimitive.content }
            status = parseStatus(jsonObject["status"]?.jsonPrimitive?.content)
            initialized = true
        }
    }

    // ==================== CHAPTER LIST ====================
    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        return GET("$apiUrl/mangas/$id/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.body.string()
        val jsonArray = try {
            kotlinx.serialization.json.Json.parseToJsonElement(data).jsonObject["data"]?.jsonArray
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()

        return jsonArray.mapNotNull { jsonElement ->
            val obj = jsonElement.jsonObject
            SChapter.create().apply {
                name = "Chapter ${obj["number"]?.jsonPrimitive?.content ?: ""}"
                url = "/chapters/${obj["id"]?.jsonPrimitive?.int ?: return@mapNotNull null}"
                date_upload = parseDate(obj["created_at"]?.jsonPrimitive?.content ?: "")
                chapter_number = obj["number"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
            }
        }.reversed() // Reversed để chapter mới nhất ở trên cùng
    }

    // ==================== PAGE LIST ====================
    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.substringAfterLast("/")
        return GET("$apiUrl/chapters/$id", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.body.string()
        val pagesArray = try {
            kotlinx.serialization.json.Json.parseToJsonElement(data)
                .jsonObject["data"]?.jsonObject?.get("pages")?.jsonArray
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()

        return pagesArray.mapIndexed { index, jsonElement ->
            val obj = jsonElement.jsonObject
            val imageUrl = obj["image_url"]?.jsonPrimitive?.content ?: ""
            
            Page(
                index = index,
                url = "",
                imageUrl = imageUrl
            )
        }
    }

    // ==================== IMAGE URL ====================
    override fun imageUrlParse(response: Response): String {
        return response.request.url.toString()
    }

    override fun getFilterList() = FilterList()

    // ==================== UTILITIES ====================
    private fun parseStatus(status: String?): Int {
        return when (status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseDate(dateString: String): Long {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS+07:00", Locale.ENGLISH)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Optional: thêm settings nếu cần
    }
}
