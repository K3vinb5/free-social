package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guided capture stop rules: detect first, record ~15 s, auto-stop, surface loss, user stop. */
class GuidedCaptureStateTest {

    @Test
    fun `recording does not start until the surface is detected and completes after fifteen seconds`() {
        val machine = GuidedCaptureMachine(detectTimeoutMs = 30_000, recordMs = 15_000)
        machine.start(now = 0)

        assertEquals(CaptureOutcome.NONE, machine.poll(0, surfaceVerified = false))
        assertEquals(CapturePhase.WAITING, machine.phase)

        // Home screen was on display for 4 s: still waiting, nothing recorded.
        assertEquals(CaptureOutcome.NONE, machine.poll(4_000, surfaceVerified = false))
        assertEquals(CapturePhase.WAITING, machine.phase)

        assertEquals(CaptureOutcome.STARTED, machine.poll(4_500, surfaceVerified = true))
        assertEquals(CapturePhase.RECORDING, machine.phase)

        assertEquals(CaptureOutcome.NONE, machine.poll(19_499, surfaceVerified = true))
        assertEquals(CaptureOutcome.COMPLETE, machine.poll(19_500, surfaceVerified = true))
        assertEquals(CapturePhase.FINISHED, machine.phase)
    }

    @Test
    fun `no surface within thirty seconds cancels without a dump`() {
        val machine = GuidedCaptureMachine(detectTimeoutMs = 30_000, recordMs = 15_000)
        machine.start(now = 1_000)

        assertEquals(CaptureOutcome.NONE, machine.poll(30_999, surfaceVerified = false))
        assertEquals(CaptureOutcome.TIMEOUT, machine.poll(31_000, surfaceVerified = false))
        assertEquals(CapturePhase.FINISHED, machine.phase)
    }

    @Test
    fun `losing the surface stops the recording early`() {
        val machine = GuidedCaptureMachine(
            detectTimeoutMs = 30_000,
            recordMs = 15_000,
            lossPolls = 2,
        )
        machine.start(now = 0)
        assertEquals(CaptureOutcome.STARTED, machine.poll(0, surfaceVerified = true))

        // One missed poll is tolerated (a transition frame), two consecutive misses stop the capture.
        assertEquals(CaptureOutcome.NONE, machine.poll(500, surfaceVerified = false))
        assertEquals(CaptureOutcome.SURFACE_LOST, machine.poll(1_000, surfaceVerified = false))
    }

    @Test
    fun `stop now cancels during detection and captures during recording`() {
        val waiting = GuidedCaptureMachine(detectTimeoutMs = 30_000, recordMs = 15_000)
        waiting.start(now = 0)
        assertEquals(CaptureOutcome.CANCELLED, waiting.stopByUser())
        assertEquals(CapturePhase.FINISHED, waiting.phase)

        val recording = GuidedCaptureMachine(detectTimeoutMs = 30_000, recordMs = 15_000)
        recording.start(now = 0)
        assertEquals(CaptureOutcome.STARTED, recording.poll(0, surfaceVerified = true))
        assertEquals(CaptureOutcome.COMPLETE, recording.stopByUser())
        assertEquals(CapturePhase.FINISHED, recording.phase)
    }
}
