package com.h0lymarc.eside

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.h0lymarc.eside.manager.ProjectManager
import com.h0lymarc.eside.ui.theme.EmbeddedStudioTheme
import org.json.JSONArray
import kotlin.math.roundToInt

class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Fresh launch only (not rotation or process restore): reopen the last project
        if (savedInstanceState == null) {
            ProjectManager.lastProject(this)?.let { dir ->
                startActivity(
                    Intent(this, EditorActivity::class.java)
                        .putExtra(EditorActivity.EXTRA_PROJECT, dir.absolutePath)
                )
            }
        }

        setContent { EmbeddedStudioTheme { CodeOnTheGoScreen() } }
    }
}

@Composable
fun CodeOnTheGoScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showNewProject by rememberSaveable { mutableStateOf(false) }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val subtleColor = MaterialTheme.colorScheme.onSurfaceVariant

    val menuItems = listOf(
        MenuItem(
            stringResource(R.string.create_project),
            Icons.Outlined.Add,
            onClick = { showNewProject = true }
        ),
        MenuItem(
            stringResource(R.string.open_project),
            Icons.Outlined.FolderOpen,
            onClick = { context.startActivity(Intent(context, ProjectsActivity::class.java)) }
        ),
        MenuItem(stringResource(R.string.clone_project), Icons.Outlined.AccountTree),
        MenuItem(stringResource(R.string.delete_project), Icons.Outlined.DeleteOutline),
        MenuItem(stringResource(R.string.terminal), Icons.Outlined.Terminal),
        MenuItem(stringResource(R.string.preferences), Icons.Outlined.Settings),
        MenuItem(stringResource(R.string.help), Icons.Outlined.HelpOutline)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val screenHeight = maxHeight

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .heightIn(min = screenHeight)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(76.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = textColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    IconButton(
                        onClick = { Toast.makeText(context, "Feedback", Toast.LENGTH_SHORT).show() },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Feedback,
                            contentDescription = "Feedback",
                            tint = textColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_logo),
                        contentDescription = "App icon",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(width = 50.dp, height = 90.dp)
                            .clip(RoundedCornerShape(7.dp))
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.get_started),
                        color = textColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.quick_start_guide),
                        color = textColor,
                        fontSize = 17.sp
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 16.dp)
                        .width(IntrinsicSize.Max),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(
                        if (screenHeight < 700.dp) 16.dp else 27.dp
                    )
                ) {
                    menuItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    val action = item.onClick
                                    if (action != null) action()
                                    else Toast.makeText(context, item.title, Toast.LENGTH_SHORT).show()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = textColor,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = item.title,
                                color = textColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.app_version),
                    color = subtleColor,
                    fontSize = 17.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 16.dp, bottom = 25.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = showNewProject,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            NewProjectScreen(
                onExit = { showNewProject = false },
                onTemplateSelected = { template ->
                    showNewProject = false
                    context.startActivity(
                        Intent(context, EditorActivity::class.java)
                            .putExtra(EditorActivity.EXTRA_TEMPLATE, template.assetPath)
                            .putExtra(
                                EditorActivity.EXTRA_REQUIRED_LIBS,
                                template.libraries.toTypedArray()
                            )
                    )
                }
            )
        }
    }
}

data class MenuItem(val title: String, val icon: ImageVector, val onClick: (() -> Unit)? = null)

data class ProjectTemplate(
    val id: String,
    val name: String,
    val assetPath: String,
    val libraries: List<String>,
    val icon: ImageVector
)

/** Fallback when assets/templates/manifest.json is missing. */
private val defaultTemplates = listOf(
    ProjectTemplate(
        id = "blank",
        name = "Blank .ino",
        assetPath = "templates/blank.ino",
        libraries = emptyList(),
        icon = Icons.Outlined.CheckBoxOutlineBlank
    ),
    ProjectTemplate(
        id = "lcd_i2c",
        name = "LCD I2C",
        assetPath = "templates/lcd_i2c.ino",
        libraries = listOf("LiquidCrystal I2C"),
        icon = Icons.Outlined.DesktopWindows
    )
)

private fun iconForTemplate(id: String): ImageVector = when (id) {
    "blank" -> Icons.Outlined.CheckBoxOutlineBlank
    "lcd_i2c" -> Icons.Outlined.DesktopWindows
    else -> Icons.Outlined.Code
}

/** Load templates from assets/templates/manifest.json, or fall back to built-in list. */
fun loadProjectTemplates(context: android.content.Context): List<ProjectTemplate> {
    return runCatching {
        val json = context.assets.open("templates/manifest.json")
            .bufferedReader()
            .use { it.readText() }
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val libsJson = obj.optJSONArray("libraries")
                val libs = buildList {
                    if (libsJson != null) {
                        for (j in 0 until libsJson.length()) add(libsJson.getString(j))
                    }
                }
                val id = obj.getString("id")
                add(
                    ProjectTemplate(
                        id = id,
                        name = obj.getString("name"),
                        assetPath = obj.getString("asset"),
                        libraries = libs,
                        icon = iconForTemplate(id)
                    )
                )
            }
        }
    }.getOrElse { defaultTemplates }
}

@Composable
private fun TemplateItem(template: ProjectTemplate, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 100.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = template.icon,
                contentDescription = template.name,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = template.name,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
fun NewProjectScreen(onExit: () -> Unit, onTemplateSelected: (ProjectTemplate) -> Unit) {
    BackHandler(onBack = onExit)

    val context = LocalContext.current
    val templates = remember { loadProjectTemplates(context) }

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissThreshold = with(LocalDensity.current) { 140.dp.toPx() }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && dragOffset > 0f) {
                    val consumed = maxOf(available.y, -dragOffset)
                    dragOffset += consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    dragOffset += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (dragOffset > dismissThreshold) onExit()
                else animate(dragOffset, 0f) { value, _ -> dragOffset = value }
                return available
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { onExit() } }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 76.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, dragOffset.roundToInt()) }
                    .nestedScroll(nestedScrollConnection)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) { detectTapGestures {} }
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 20.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                )

                Text(
                    text = "New project",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                )

                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    templates.chunked(4).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { template ->
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    TemplateItem(template) { onTemplateSelected(template) }
                                }
                            }
                            repeat(4 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
