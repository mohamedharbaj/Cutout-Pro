package com.example

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.data.repository.ProjectRepository
import com.example.data.repository.SettingsRepository
import com.example.engine.ModelManager

class CutoutProApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var projectRepository: ProjectRepository
        private set

    lateinit var modelManager: ModelManager
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        projectRepository = ProjectRepository(database.projectDao())
        modelManager = ModelManager(this)
        settingsRepository = SettingsRepository(this)
    }
}
