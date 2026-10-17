package com.posepocket.media

import org.junit.Assert.*
import org.junit.Test

class InputMediaLimitTest {
    @Test fun sourceLargerThanBuildLimitIsEligibleForLocalNormalization() {
        VideoSizeLimits.checkSource(50_000_001L)
        VideoSizeLimits.checkSource(175_000_000L)
        VideoSizeLimits.checkSource(VideoSizeLimits.MAX_SOURCE_BYTES)
    }

    @Test fun sourceIsBoundedAndZeroLengthInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { VideoSizeLimits.checkSource(0) }
        assertThrows(IllegalArgumentException::class.java) {
            VideoSizeLimits.checkSource(VideoSizeLimits.MAX_SOURCE_BYTES + 1)
        }
    }

    @Test fun nvidiaOnlyAcceptsFinalEncodedVideoWithin50MB() {
        VideoSizeLimits.checkForNvidia(VideoSizeLimits.MAX_NVIDIA_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            VideoSizeLimits.checkForNvidia(VideoSizeLimits.MAX_NVIDIA_BYTES + 1)
        }
    }
}