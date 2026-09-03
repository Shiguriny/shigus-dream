package com.shigusdream.backend.repository.memory

import com.shigusdream.backend.repository.ModArtifactRepository
import com.shigusdream.backend.repository.ModArtifact
import java.util.concurrent.atomic.AtomicReference

class InMemoryModArtifactRepository : ModArtifactRepository {
    private val current = AtomicReference<ModArtifact?>()

    override fun save(artifact: ModArtifact) {
        current.set(artifact)
    }

    override fun latest(): ModArtifact? = current.get()
}
