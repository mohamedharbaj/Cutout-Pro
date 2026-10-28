package com.example

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.example.data.model.ProjectEntity
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WelcomeScreen
import com.example.ui.theme.CutoutProTheme
import com.example.util.ImageUtils
import com.example.util.SampleImageProvider
import kotlinx.coroutines.launch
import java.util.Locale

sealed class Screen {
    data object Welcome : Screen()
    data object Home : Screen()
    data class Editor(val bitmap: Bitmap, val project: ProjectEntity? = null) : Screen()
    data object Settings : Screen()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as CutoutProApplication
        val projectRepo = app.projectRepository
        val modelMgr = app.modelManager
        val settingsRepo = app.settingsRepository

        setContent {
            val scope = rememberCoroutineScope()
            val themeMode by settingsRepo.themeModeFlow.collectAsState(initial = "SYSTEM")
            val language by settingsRepo.languageFlow.collectAsState(initial = "SYSTEM")
            // null = DataStore not loaded yet. The old initial value `false` meant the Welcome screen
            // was NEVER shown, because the start screen was decided once, before the real value arrived.
            val isFirstLaunch by settingsRepo.isFirstLaunchFlow.collectAsState(initial = null)

            val isDark = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemInDarkTheme()
            }

            val layoutDirection = when (language) {
                "AR" -> LayoutDirection.Rtl
                "EN" -> LayoutDirection.Ltr
                else -> LocalLayoutDirection.current
            }

            var currentScreen by remember { mutableStateOf<Screen?>(null) }
            var isLoadingImage by remember { mutableStateOf(false) }

            LaunchedEffect(isFirstLaunch) {
                val first = isFirstLaunch
                if (currentScreen == null && first != null) {
                    currentScreen = if (first) Screen.Welcome else Screen.Home
                }
            }

            // The language setting used to flip only the layout direction; strings kept following
            // the system locale. Provide a Context whose resources use the chosen locale.
            val baseContext = LocalContext.current
            val localizedContext = remember(language, baseContext) { createLocalizedContext(baseContext, language) }

            // Settings had no back handling: system Back closed the whole app.
            BackHandler(enabled = currentScreen is Screen.Settings) {
                currentScreen = Screen.Home
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalLayoutDirection provides layoutDirection
            ) {
                CutoutProTheme(darkTheme = isDark) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            when (val screen = currentScreen) {
                                null -> {
                                    // Waiting for the first-launch flag from DataStore.
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) { CircularProgressIndicator() }
                                }
                                is Screen.Welcome -> {
                                    WelcomeScreen(
                                        onStartEditing = {
                                            scope.launch {
                                                settingsRepo.setFirstLaunchCompleted()
                                                currentScreen = Screen.Home
                                            }
                                        },
                                        onTrySample = {
                                            scope.launch {
                                                settingsRepo.setFirstLaunchCompleted()
                                                isLoadingImage = true
                                                val sample = SampleImageProvider.createSampleBitmap(this@MainActivity)
                                                isLoadingImage = false
                                                currentScreen = Screen.Editor(sample)
                                            }
                                        }
                                    )
                                }
                                is Screen.Home -> {
                                    HomeScreen(
                                        projectRepository = projectRepo,
                                        modelManager = modelMgr,
                                        onPhotoSelected = { uri ->
                                            scope.launch {
                                                isLoadingImage = true
                                                val result = ImageUtils.loadBitmapFromUri(this@MainActivity, uri)
                                                isLoadingImage = false
                                                result.onSuccess { bmp ->
                                                    currentScreen = Screen.Editor(bmp)
                                                }.onFailure { err ->
                                                    Toast.makeText(this@MainActivity, "Failed to load image: ${err.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        onTrySample = {
                                            scope.launch {
                                                isLoadingImage = true
                                                val sample = SampleImageProvider.createSampleBitmap(this@MainActivity)
                                                isLoadingImage = false
                                                currentScreen = Screen.Editor(sample)
                                            }
                                        },
                                        onOpenProject = { project ->
                                            scope.launch {
                                                isLoadingImage = true
                                                val orig = ImageUtils.loadBitmapFromFile(project.originalImagePath)
                                                isLoadingImage = false
                                                if (orig != null) {
                                                    currentScreen = Screen.Editor(orig, project)
                                                } else {
                                                    Toast.makeText(this@MainActivity, "Original image file not found", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        onOpenSettings = {
                                            currentScreen = Screen.Settings
                                        }
                                    )
                                }
                                is Screen.Editor -> {
                                    EditorScreen(
                                        initialBitmap = screen.bitmap,
                                        existingProject = screen.project,
                                        modelManager = modelMgr,
                                        projectRepository = projectRepo,
                                        onBack = {
                                            currentScreen = Screen.Home
                                        }
                                    )
                                }
                                is Screen.Settings -> {
                                    SettingsScreen(
                                        settingsRepository = settingsRepo,
                                        modelManager = modelMgr,
                                        onBack = {
                                            currentScreen = Screen.Home
                                        }
                                    )
                                }
                            }

                            if (isLoadingImage) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun createLocalizedContext(base: Context, language: String): Context {
    val locale = when (language) {
        "AR" -> Locale.forLanguageTag("ar")
        "EN" -> Locale.ENGLISH
        else -> return base
    }
    val config = Configuration(base.resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    val localizedResources = base.createConfigurationContext(config).resources
    // ContextWrapper (not the bare configuration context) so startActivity()/Activity lookups
    // still delegate to the real Activity.
    return object : ContextWrapper(base) {
        override fun getResources(): Resources = localizedResources
    }
}
