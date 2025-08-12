package com.hiddenrefactoring.model

import java.time.Instant
import java.util.*
import kotlin.jvm.JvmOverloads

data class Comment @JvmOverloads constructor(
    var id: String = UUID.randomUUID().toString(),
    var text: String = "",
    var createdAt: Long = Instant.now().toEpochMilli(),
    var updatedAt: Long = createdAt,
)
