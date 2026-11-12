package com.cue.brain.cloud

import com.cue.brain.Brain
import com.cue.brain.BrainCapabilities
import com.cue.brain.BrainEvent
import com.cue.brain.BrainSession
import com.cue.brain.FieldsRequest
import com.cue.brain.FieldsResult
import com.cue.brain.SessionConfig
import com.cue.brain.TurnInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Placeholder for a cloud provider behind the same contract. Off by default: the hackathon build sends nothing off
 * the phone. Switching a job to the cloud means implementing this class and adding a key behind a relay server.
 */
class CloudBrain : Brain {
    override val name = "cloud"
    override val capabilities = BrainCapabilities(contextTokens = 1_000_000, maxTools = 20, hearsAudio = false, speaks = false, needsNetwork = true, prepareMillis = 300)
    override suspend fun prepare() = false
    override fun isReady() = false
    override fun openSession(config: SessionConfig): BrainSession = object : BrainSession {
        override fun send(input: TurnInput): Flow<BrainEvent> = flowOf(BrainEvent.Failed("cloud brain is not configured"))
        override fun close() = Unit
    }
    override suspend fun complete(request: FieldsRequest) = FieldsResult(false, emptyList())
    override suspend fun release() = Unit
}
