package me.capcom.smsgateway.modules.messages

import me.capcom.smsgateway.domain.ProcessingState

internal object MessageStatePolicy {
    fun accepts(current: ProcessingState, next: ProcessingState): Boolean = when (current) {
        ProcessingState.Pending -> next != ProcessingState.Pending
        ProcessingState.Processed -> next in setOf(ProcessingState.Sent, ProcessingState.Delivered, ProcessingState.Failed)
        ProcessingState.Sent -> next in setOf(ProcessingState.Delivered, ProcessingState.Failed)
        ProcessingState.Delivered, ProcessingState.Failed -> false
    }

    fun aggregate(states: List<ProcessingState>): ProcessingState = when {
        states.isEmpty() -> ProcessingState.Pending
        states.any { it == ProcessingState.Pending } -> ProcessingState.Pending
        states.any { it == ProcessingState.Processed } -> ProcessingState.Processed
        states.all { it == ProcessingState.Failed } -> ProcessingState.Failed
        states.all { it == ProcessingState.Delivered } -> ProcessingState.Delivered
        else -> ProcessingState.Sent
    }

    fun partOutcome(expectedParts: Int, states: List<ProcessingState>): ProcessingState? = when {
        states.any { it == ProcessingState.Failed } -> ProcessingState.Failed
        expectedParts <= 0 || states.size < expectedParts -> null
        states.all { it == ProcessingState.Delivered } -> ProcessingState.Delivered
        states.all { it == ProcessingState.Sent } -> ProcessingState.Sent
        else -> null
    }
}
