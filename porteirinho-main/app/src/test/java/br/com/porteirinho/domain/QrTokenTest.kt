package br.com.porteirinho.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrTokenTest {
    @Test
    fun `accepts only structured tokens`() {
        assertEquals("cred-1", QrToken.parse("porteirinho:v1:cred-1:secret-value")?.credentialId)
        assertNull(QrToken.parse("cred-1:secret-value"))
        assertNull(QrToken.parse("porteirinho:v1:cred-1:x"))
    }
}
