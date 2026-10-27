package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val originalImagePath: String,
    val maskImagePath: String,
    val thumbnailPath: String,
    val bgType: String = "TRANSPARENT", // "TRANSPARENT", "COLOR", "IMAGE"
    val bgColor: Long = 0x00000000L,
    val bgImagePath: String? = null,
    val width: Int,
    val height: Int
)
