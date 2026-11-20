package com.example.shareflow.core.security

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Sha256ChecksumVerifierTest {

    private val verifier = Sha256ChecksumVerifier()

    @Test
    fun `computeSha256 produces standard RFC digest`() {
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        val result = verifier.computeSha256(ByteArrayInputStream("hello".toByteArray()))
        assertTrue(result is AppResult.Success)
        assertEquals(expected, (result as AppResult.Success).data)
    }

    @Test
    fun `verifySha256 succeeds on matching digest and fails on tampering`() {
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

        val validResult = verifier.verifySha256(
            ByteArrayInputStream("hello".toByteArray()),
            expected.uppercase()
        )
        assertTrue(validResult is AppResult.Success && validResult.data)

        val tamperedResult = verifier.verifySha256(
            ByteArrayInputStream("hello!".toByteArray()),
            expected
        )
        assertTrue(tamperedResult is AppResult.Failure)
        assertTrue((tamperedResult as AppResult.Failure).error is AppError.IntegrityMismatch)
    }

    @Test
    fun `verifySha256 rejects malformed expected hash`() {
        val result = verifier.verifySha256(
            ByteArrayInputStream("hello".toByteArray()),
            "not-a-valid-sha256"
        )
        assertTrue(result is AppResult.Failure)
        assertTrue((result as AppResult.Failure).error is AppError.Validation)
    }
}
