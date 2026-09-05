package com.shigusdream.backend.repository.memory

import com.shigusdream.backend.repository.ModArtifactRepository
import com.shigusdream.backend.repository.ModArtifact
import com.shigusdream.backend.repository.WebScenario
import com.shigusdream.backend.repository.WebScenarioRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class InMemoryModArtifactRepository : ModArtifactRepository {
    private val current = AtomicReference<ModArtifact?>()

    override fun save(artifact: ModArtifact) {
        current.set(artifact)
    }

    override fun latest(): ModArtifact? = current.get()
}

class InMemoryWebScenarioRepository : WebScenarioRepository {
    private val scenarios = ConcurrentHashMap<String, WebScenario>()

    override fun save(scenario: WebScenario) {
        scenarios[scenario.name] = scenario
    }

    override fun list(): List<WebScenario> = scenarios.values.sortedBy { it.name }

    override fun byName(name: String): WebScenario? = scenarios[name]

    override fun delete(name: String): Boolean = scenarios.remove(name) != null
}
