package com.example.data.repository

import com.example.data.db.ProjectDao
import com.example.data.model.ProjectEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

class ProjectRepository(private val projectDao: ProjectDao) {

    fun getAllProjects(): Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    suspend fun getProjectById(id: Long): ProjectEntity? = projectDao.getProjectById(id)

    suspend fun saveProject(project: ProjectEntity): Long = projectDao.insertProject(project)

    suspend fun updateProject(project: ProjectEntity) = projectDao.updateProject(project)

    suspend fun deleteProject(project: ProjectEntity) {
        // Clean up project files on disk
        runCatching {
            File(project.originalImagePath).takeIf { it.exists() }?.delete()
            File(project.maskImagePath).takeIf { it.exists() }?.delete()
            File(project.thumbnailPath).takeIf { it.exists() }?.delete()
        }
        projectDao.deleteProject(project)
    }
}
