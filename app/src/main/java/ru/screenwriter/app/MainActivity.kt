package ru.screenwriter.app

import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private val Ink = Color(0xFF17191F)
private val Panel = Color(0xFF22252D)
private val Panel2 = Color(0xFF2C303A)
private val Paper = Color(0xFFF3EDE2)
private val Muted = Color(0xFFA6A9B2)
private val Gold = Color(0xFFE7B56B)
private val Sage = Color(0xFF9DB6A0)

data class Scene(val command: String, val text: String, val kind: String = "Сцена")
data class Story(
    val id: Long = System.currentTimeMillis(),
    var title: String,
    var world: String = "",
    var setting: String = "",
    var genre: String = "Психологическая проза",
    var characters: String = "",
    var events: String = "",
    var scenes: MutableList<Scene> = mutableListOf()
)

class StoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("screenwriter", Context.MODE_PRIVATE)
    fun endpoint(): String = prefs.getString("ai_endpoint", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
    fun model(): String = prefs.getString("ai_model", "gpt-4o-mini") ?: "gpt-4o-mini"
    fun saveAiSettings(endpoint: String, model: String) { prefs.edit().putString("ai_endpoint", endpoint.trim().trimEnd('/')).putString("ai_model", model.trim()).apply() }
    fun load(): MutableList<Story> {
        val arr = JSONArray(prefs.getString("stories", "[]"))
        val result = mutableListOf<Story>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val scenesJson = o.optJSONArray("scenes") ?: JSONArray()
            val scenes = mutableListOf<Scene>()
            for (j in 0 until scenesJson.length()) {
                val s = scenesJson.getJSONObject(j)
                scenes.add(Scene(s.optString("command"), s.optString("text"), s.optString("kind", "Сцена")))
            }
            result.add(Story(o.optLong("id"), o.optString("title"), o.optString("world"), o.optString("setting"),
                o.optString("genre", "Психологическая проза"), o.optString("characters"), o.optString("events"), scenes))
        }
        return result
    }
    fun save(stories: List<Story>) {
        val arr = JSONArray()
        stories.forEach { story ->
            val o = JSONObject().put("id", story.id).put("title", story.title).put("world", story.world)
                .put("setting", story.setting).put("genre", story.genre).put("characters", story.characters).put("events", story.events)
            val scenes = JSONArray()
            story.scenes.forEach { scenes.put(JSONObject().put("command", it.command).put("text", it.text).put("kind", it.kind)) }
            o.put("scenes", scenes); arr.put(o)
        }
        prefs.edit().putString("stories", arr.toString()).apply()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = StoryStore(this)
        setContent { ScreenwriterApp(store) }
    }
}

@Composable
fun ScreenwriterApp(store: StoryStore) {
    var stories by remember { mutableStateOf(store.load()) }
    var currentId by remember { mutableStateOf<Long?>(null) }
    var creating by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    fun persist() { store.save(stories) }
    val current = stories.find { it.id == currentId }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Gold, onPrimary = Ink, background = Ink, onBackground = Paper,
        surface = Panel, onSurface = Paper, surfaceVariant = Panel2, onSurfaceVariant = Muted,
        secondary = Sage, outline = Color(0xFF444955)
    )) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            when {
                settings -> SettingsScreen(store = store, onBack = { settings = false })
                creating -> CreateStoryScreen(
                    onCancel = { creating = false },
                    onCreate = { title, world, setting, genre, characters ->
                        val story = Story(title = title.ifBlank { "Новая история" }, world = world, setting = setting, genre = genre, characters = characters)
                        stories = (stories + story).toMutableList(); persist()
                        currentId = story.id; creating = false
                    }
                )
                current != null -> StoryEditor(
                    story = current,
                    store = store,
                    onBack = { persist(); currentId = null },
                    onChanged = { persist() }
                )
                else -> HomeScreen(
                    stories = stories,
                    onCreate = { creating = true },
                    onOpen = { currentId = it },
                    onSettings = { settings = true }
                )
            }
        }
    }
}

@Composable
fun HomeScreen(stories: List<Story>, onCreate: () -> Unit, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(Gold, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoStories, null, tint = Ink, modifier = Modifier.size(27.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("СЦЕНАРИСТ", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Text("мастерская историй", color = Muted, fontSize = 12.sp)
                }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки", tint = Muted) }
        }
        Spacer(Modifier.height(34.dp))
        Text("ТВОЯ СЛЕДУЮЩАЯ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Text("большая история.", color = Paper, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Создавай миры, меняй ход событий и превращай диалог в готовую книгу или сценарий.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(22.dp))
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(17.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Создать историю", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("МОИ ИСТОРИИ", color = Muted, fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f)); Text("${stories.size}", color = Gold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (stories.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MenuBook, null, tint = Panel2, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Здесь появятся твои миры", color = Muted, fontSize = 15.sp)
                    Text("Начни с идеи — остальное можно изменить позже.", color = Muted.copy(alpha = .7f), fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(stories) { _, story ->
                    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).clickable { onOpen(story.id) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(Panel2, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Book, null, tint = Gold)
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(story.title, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text("${story.genre}  ·  ${story.scenes.size} сцен", color = Muted, fontSize = 12.sp)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun CreateStoryScreen(onCancel: () -> Unit, onCreate: (String, String, String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var world by remember { mutableStateOf("") }
    var setting by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("Психологическая проза") }
    var characters by remember { mutableStateOf("") }
    val genres = listOf("Психологическая проза", "Фэнтези", "Фантастика", "Триллер", "Детектив", "Ужасы", "Романтика", "Приключения", "Сценарий")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Paper) }
        Text("С чего начнём?", color = Paper, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("Задай основу. Мир и герои не высечены в камне — их можно менять прямо во время истории.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(22.dp))
        FieldLabel("НАЗВАНИЕ")
        OutlinedTextField(title, { title = it }, placeholder = { Text("Например, Дом без окон") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(14.dp)); FieldLabel("МИР И ПРАВИЛА")
        OutlinedTextField(world, { world = it }, placeholder = { Text("Где и когда происходит история? Что необычного в этом мире?") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Spacer(Modifier.height(14.dp)); FieldLabel("СЕТТИНГ И НАСТРОЕНИЕ")
        OutlinedTextField(setting, { setting = it }, placeholder = { Text("Атмосфера, конфликт, важные темы…") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Spacer(Modifier.height(14.dp)); FieldLabel("ЖАНР")
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(genre, modifier = Modifier.weight(1f)); Icon(Icons.Default.Tune, null) }
            DropdownMenu(expanded, { expanded = false }) { genres.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { genre = it; expanded = false }) } }
        }
        Spacer(Modifier.height(14.dp)); FieldLabel("ПЕРСОНАЖИ")
        OutlinedTextField(characters, { characters = it }, placeholder = { Text("Имена, цели, страхи, отношения…") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onCreate(title, world, setting, genre, characters) }, modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { Text("Открыть мастерскую", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun StoryEditor(story: Story, store: StoryStore, onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    var exportScreenplay by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(buildExport(story, exportScreenplay)) }
        }
    }
    var command by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("Продолжить") }
    var generating by remember { mutableStateOf(false) }
    var generationError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(false) }
    var editedScene by remember { mutableIntStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    val types = listOf("Продолжить", "Изменить сцену", "Добавить героя", "Событие", "Направить сюжет")
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Paper) }
            Column(Modifier.weight(1f)) {
                Text(story.title, color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${story.genre} · ${story.scenes.size} сцен", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = { showInfo = !showInfo }) { Icon(Icons.Default.MoreHoriz, "Детали", tint = Muted) }
        }
        if (showInfo) {
            Column(Modifier.fillMaxWidth().background(Panel).padding(14.dp)) {
                Text("МИР", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(story.world.ifBlank { "Не задан" }, color = Paper, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp)); Text("СЕТТИНГ", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(story.setting.ifBlank { "Не задан" }, color = Paper, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp)); Text("ПЕРСОНАЖИ", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(story.characters.ifBlank { "Добавь персонажей сообщением ниже" }, color = Paper, fontSize = 13.sp)
            }
        }
        TabRow(selectedTabIndex = activeTab, containerColor = Ink, contentColor = Gold) {
            Tab(activeTab == 0, { activeTab = 0 }, text = { Text("Сцены") }, icon = { Icon(Icons.Default.ChatBubbleOutline, null, modifier = Modifier.size(17.dp)) })
            Tab(activeTab == 1, { activeTab = 1 }, text = { Text("Персонажи") }, icon = { Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(17.dp)) })
            Tab(activeTab == 2, { activeTab = 2 }, text = { Text("Экспорт") }, icon = { Icon(Icons.Default.Download, null, modifier = Modifier.size(17.dp)) })
        }
        when (activeTab) {
            0 -> {
                if (story.scenes.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.EditNote, null, tint = Gold, modifier = Modifier.size(54.dp))
                        Spacer(Modifier.height(14.dp)); Text("Здесь начнётся история", color = Paper, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(Modifier.height(8.dp)); Text("Напиши, что происходит, или дай команду — например: «Героиня находит письмо, которое написала сама себе через 20 лет».", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(story.scenes) { index, scene ->
                            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("СЦЕНА ${index + 1}", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { editedScene = index; editText = scene.text }) { Text("Изменить", color = Muted, fontSize = 12.sp) }
                                }
                                if (editedScene == index) {
                                    OutlinedTextField(editText, { editText = it }, modifier = Modifier.fillMaxWidth(), minLines = 4)
                                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(onClick = { editedScene = -1 }) { Text("Отмена") }
                                        TextButton(onClick = { story.scenes[index] = scene.copy(text = editText); editedScene = -1; onChanged() }) { Text("Сохранить", color = Gold) }
                                    }
                                } else Text(scene.text, color = Paper, fontSize = 15.sp, lineHeight = 23.sp)
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().background(Panel).padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        types.forEach { t ->
                            val short = when(t) { "Продолжить" -> "Дальше"; "Изменить сцену" -> "Правка"; "Добавить героя" -> "Герой"; "Событие" -> "Событие"; else -> "Вектор" }
                            FilterChip(selected = kind == t, onClick = { kind = t }, label = { Text(short, fontSize = 11.sp) })
                        }
                    }
                    if (generationError.isNotBlank()) Text(generationError, color = Color(0xFFFF9B91), fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(value = command, onValueChange = { command = it }, enabled = !generating, modifier = Modifier.weight(1f),
                            placeholder = { Text("Опиши действие или изменение…", fontSize = 13.sp) }, minLines = 2, maxLines = 5)
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = {
                            if (command.isNotBlank() && !generating) {
                                val userCommand = command.trim()
                                val userKind = kind
                                command = ""
                                generationError = ""
                                generating = true
                                scope.launch {
                                    try {
                                        val generated = withContext(Dispatchers.IO) {
                                            if (AiRuntimeConfig.apiKey.isNotBlank()) OpenAiCompatibleClient.generate(store.endpoint(), store.model(), AiRuntimeConfig.apiKey, story, userKind, userCommand)
                                            else OfflineStoryEngine.generate(story, userKind, userCommand)
                                        }
                                        when (userKind) {
                                            "Изменить сцену" -> if (story.scenes.isNotEmpty()) story.scenes[story.scenes.lastIndex] = story.scenes.last().copy(command = userCommand, text = generated, kind = "Переписанная сцена") else story.scenes.add(Scene(userCommand, generated, "Сцена"))
                                            "Добавить героя" -> { story.characters = (story.characters + "\\n" + userCommand).trim(); story.scenes.add(Scene(userCommand, generated, "Персонаж")) }
                                            "Событие" -> { story.events = (story.events + "\\n" + userCommand).trim(); story.scenes.add(Scene(userCommand, generated, "Событие")) }
                                            else -> story.scenes.add(Scene(userCommand, generated, "Сцена"))
                                        }
                                        onChanged()
                                    } catch (e: Exception) {
                                        generationError = "Ошибка ИИ: ${e.message ?: "не удалось получить ответ"}. Проверь адрес, модель, ключ и подключение."
                                    } finally { generating = false }
                                }
                            }
                        }, enabled = !generating && command.isNotBlank(), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Gold, contentColor = Ink), modifier = Modifier.size(52.dp)) {
                            if (generating) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Ink) else Icon(Icons.AutoMirrored.Filled.Send, "Отправить")
                        }
                    }
                    Text(if (AiRuntimeConfig.apiKey.isNotBlank()) "ИИ подключён · полный текст истории включён в контекст" else "Офлайн-шаблоны. Для генерации языковой моделью подключи API в настройках.", color = Muted.copy(alpha = .7f), fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
            1 -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp)) {
                    Text("Персонажи — живые заметки", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Добавляй и уточняй героев по мере развития сюжета.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(story.characters, { story.characters = it; onChanged() }, modifier = Modifier.fillMaxWidth(), minLines = 8, label = { Text("Имя · цель · страх · отношения") })
                    Spacer(Modifier.height(18.dp)); Text("КЛЮЧЕВЫЕ СОБЫТИЯ", color = Gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(story.events, { story.events = it; onChanged() }, modifier = Modifier.fillMaxWidth(), minLines = 5, label = { Text("Что уже изменило ход истории") })
                }
            }
            else -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text("История готова выйти из чата", color = Paper, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp)); Text("Экспортируй материал в удобном формате. Файл сохранится в выбранное место на устройстве.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
                    Spacer(Modifier.height(22.dp))
                    ExportCard("Книга · TXT", "Заголовок, главы и сплошной литературный текст", onClick = { exportScreenplay = false; exportLauncher.launch("${story.title}.txt") })
                    Spacer(Modifier.height(12.dp))
                    ExportCard("Сценарий · TXT", "Сцены, описания действия и диалоги в сценарной структуре", onClick = { exportScreenplay = true; exportLauncher.launch("${story.title}_scenario.txt") })
                    Spacer(Modifier.height(20.dp))
                    Text("Совет: можно редактировать любую сцену вручную, а командой «Изменить сцену» — заменить последнюю сцену новым вариантом.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
fun ExportCard(title: String, desc: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(Panel2, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Download, null, tint = Gold) }
        Spacer(Modifier.width(13.dp)); Column(Modifier.weight(1f)) {
            Text(title, color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp)); Text(desc, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Gold, modifier = Modifier.size(17.dp))
    }
}

@Composable
fun SettingsScreen(store: StoryStore, onBack: () -> Unit) {
    var endpoint by remember { mutableStateOf(store.endpoint()) }
    var model by remember { mutableStateOf(store.model()) }
    var apiKey by remember { mutableStateOf(AiRuntimeConfig.apiKey) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Paper) }
        Text("Настройки мастерской", color = Paper, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Text("МОДЕЛЬ И КОНТЕКСТ", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(8.dp))
        Text("Подключи сервер, совместимый с OpenAI Chat Completions. В каждый запрос входят мир, сеттинг, жанр, персонажи, события и полный текст всех сцен. История хранится на устройстве. API-ключ держится только в памяти до закрытия приложения и не сохраняется на диск.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(endpoint, { endpoint = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Адрес API") }, placeholder = { Text("https://api.openai.com/v1") })
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(model, { model = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название модели") }, placeholder = { Text("gpt-4o-mini") })
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(apiKey, { apiKey = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("API-ключ (не сохраняется на диск)") }, placeholder = { Text("Вставь ключ провайдера") })
        Spacer(Modifier.height(12.dp))
        Button(onClick = { store.saveAiSettings(endpoint, model); AiRuntimeConfig.apiKey = apiKey.trim(); saved = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { Text("Сохранить настройки ИИ") }
        if (saved) Text("Настройки сохранены. Ключ действует до закрытия приложения.", color = Sage, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(20.dp))
        Text("ЛОКАЛЬНАЯ МОДЕЛЬ GGUF", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text("Выбор GGUF-файла не запускает модель: в этой сборке нет native inference-движка и весов. Этот вариант использует подключённый API; для полностью автономной генерации потребуется отдельная интеграция llama.cpp.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(24.dp))
        Text("БИБЛИОТЕКА ПИСАТЕЛЯ", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text("Встроенные подсказки движка помогают оформлять команду, сцену и сюжетные заметки. Полноценная скрытая база писательских знаний и её retrieval-механизм — следующий этап реализации.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
    Spacer(Modifier.height(6.dp))
}

object AiRuntimeConfig { @Volatile var apiKey: String = "" }

object OpenAiCompatibleClient {
    fun generate(baseUrl: String, model: String, apiKey: String, story: Story, kind: String, command: String): String {
        require(baseUrl.startsWith("https://")) { "Для защиты данных используй HTTPS-адрес API" }
        require(model.isNotBlank()) { "Не указана модель" }
        val connection = (java.net.URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val payload = JSONObject()
                .put("model", model)
                .put("temperature", 0.85)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", StoryPromptBuilder.systemPrompt(story, kind)))
                    .put(JSONObject().put("role", "user").put("content", "КОМАНДА АВТОРА:\\n$command\\n\\nВыполни команду и верни только готовый литературный текст без пояснений.")))
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(500)}")
            val content = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
            if (content.isBlank() || content == "null") throw IllegalStateException("Модель вернула пустой ответ")
            return content
        } finally { connection.disconnect() }
    }
}

object StoryPromptBuilder {
    fun systemPrompt(story: Story, kind: String): String = buildString {
        append("Ты — профессиональный соавтор и редактор художественной прозы. Пиши по-русски, выразительно, без клише. Не пересказывай уже написанное и не противоречь фактам истории. Соблюдай причинность, голоса персонажей, хронологию, жанр и указания автора. Не объясняй подтекст, если он должен оставаться скрытым. Тип действия: ").append(kind).append(".\\n\\n")
        append("НАЗВАНИЕ: ").append(story.title).append("\\nЖАНР: ").append(story.genre)
        append("\\nМИР И ПРАВИЛА: ").append(story.world.ifBlank { "Не заданы" })
        append("\\nСЕТТИНГ И НАСТРОЕНИЕ: ").append(story.setting.ifBlank { "Не заданы" })
        append("\\nПЕРСОНАЖИ И ОТНОШЕНИЯ: ").append(story.characters.ifBlank { "Пока не заданы" })
        append("\\nЗАФИКСИРОВАННЫЕ СОБЫТИЯ: ").append(story.events.ifBlank { "Пока не заданы" })
        append("\\n\\nПОЛНЫЙ ТЕКСТ ИСТОРИИ (все сохранённые сцены по порядку; это канон):\\n")
        if (story.scenes.isEmpty()) append("[Сцен пока нет]") else story.scenes.forEachIndexed { index, scene ->
            append("\\n--- СЦЕНА ").append(index + 1).append(" · ").append(scene.kind).append(" ---\\n")
            if (scene.command.isNotBlank()) append("Команда автора: ").append(scene.command).append("\\n")
            append(scene.text).append("\\n")
        }
        append("\\nПеред ответом проверь непрерывность: знания персонажей, предметы, обещания, травмы, отношения, хронологию и незакрытые сюжетные вопросы. При переписывании сцены не изменяй предыдущие сцены.")
    }
}

object OfflineStoryEngine {
    fun generate(story: Story, kind: String, command: String): String {
        val title = story.title.ifBlank { "Без названия" }
        val world = story.world.ifBlank { "мир, который ещё только складывается" }
        val setting = story.setting.ifBlank { "тишина перед переменами" }
        val chars = story.characters.ifBlank { "тот, кому предстоит сделать выбор" }
        val previous = story.scenes.lastOrNull()?.text?.takeLast(450)
        return when (kind) {
            "Добавить героя" -> "НОВЫЙ ПЕРСОНАЖ\n\n$command\n\nВ истории «$title» появление этого человека меняет расстановку сил. Его прошлое пока остаётся за кадром, но детали будут раскрываться через поступки, оговорки и то, о чём он предпочитает молчать.\n\nМир истории: $world. Настроение: $setting."
            "Событие" -> "СОБЫТИЕ\n\n$command\n\nПоследствия не возникают мгновенно. Сначала меняется одна небольшая деталь: привычный маршрут, интонация в знакомом голосе, предмет не на своём месте. Затем герои понимают, что прежний порядок уже не восстановить без потерь.\n\nУчастники: $chars."
            "Изменить сцену" -> "ПЕРЕПИСАННАЯ СЦЕНА\n\n$command\n\n$previous\n\nНовая версия сцены следует твоей команде. Проверь причинно-следственные связи и при необходимости уточни детали мира или мотивацию персонажей."
            "Направить сюжет" -> "ПОВОРОТ СЮЖЕТА\n\n$command\n\nПеремена начинается с решения, которое кажется незначительным. Но в мире «$title» даже молчание имеет цену. Героям придётся выбирать не между правильным и неправильным, а между двумя потерями, каждая из которых что-то о них расскажет.\n\nКонтекст: $world. Атмосфера: $setting. Герои: $chars."
            else -> {
                val opening = if (previous.isNullOrBlank()) "Всё началось не так, как потом рассказывали." else "После случившегося никто не спешил нарушать тишину."
                "$opening\n\n$command\n\nВ $world день казался обыкновенным только издали. $setting. $chars — каждый по-своему чувствовал, что привычная версия происходящего больше не объясняет мелочей.\n\nКто-то отвёл взгляд. Кто-то задал вопрос, на который никто не собирался отвечать. И именно тогда стало ясно: следующий шаг уже сделан, хотя никто не мог сказать, кем именно."
            }
        }
    }
}

private fun buildExport(story: Story, screenplay: Boolean): String {
    val out = StringBuilder()
    if (!screenplay) {
        out.append(story.title.uppercase()).append("\n\n")
        if (story.genre.isNotBlank()) out.append(story.genre).append("\n\n")
        story.scenes.forEachIndexed { index, scene ->
            out.append("Глава ").append(index + 1).append("\n\n")
            out.append(scene.text.trim()).append("\n\n")
        }
        if (story.scenes.isEmpty()) out.append("История пока не содержит сцен.\n")
    } else {
        out.append(story.title.uppercase()).append("\n")
        out.append("СЦЕНАРИЙ · ").append(story.genre.uppercase()).append("\n\n")
        story.scenes.forEachIndexed { index, scene ->
            out.append("СЦЕНА ${index + 1} — ${scene.kind.uppercase()}\n")
            out.append("ИНТ. / НАТ. — МЕСТО НЕ УКАЗАНО — ВРЕМЯ НЕ УКАЗАНО\n\n")
            out.append(scene.text.trim()).append("\n\n")
        }
        if (story.scenes.isEmpty()) out.append("Сцен пока нет.\n")
    }
    out.append("\n---\nМир: ").append(story.world).append("\nСеттинг: ").append(story.setting)
    out.append("\nПерсонажи:\n").append(story.characters)
    out.append("\nСобытия:\n").append(story.events)
    return out.toString()
}
