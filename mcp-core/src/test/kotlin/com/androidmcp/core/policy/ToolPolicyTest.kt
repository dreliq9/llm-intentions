package com.androidmcp.core.policy

import com.androidmcp.core.protocol.ConfirmationMode
import com.androidmcp.core.protocol.MutationClass
import com.androidmcp.core.protocol.SensitiveDataClass
import com.androidmcp.core.protocol.ToolMetadata
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ToolPolicyTest {

    private fun remote(grant: Boolean = true) = ToolPolicyContext(
        origin = InvocationOrigin.REMOTE_PROVIDER,
        hasGrant = grant,
    )

    private fun local(grant: Boolean = true) = ToolPolicyContext(
        origin = InvocationOrigin.LOCAL_DEVELOPER,
        hasGrant = grant,
    )

    @Test
    fun `remote missing grant is denied`() {
        val decision = ToolPolicyEngine.evaluate(
            ToolMetadata(
                mutation = MutationClass.READ_ONLY,
                sensitiveData = SensitiveDataClass.NONE,
            ),
            remote(grant = false),
        )
        assertEquals(PolicyDecisionType.DENY, decision.type)
    }

    @Test
    fun `remote credentials are denied even with grant and never-confirm metadata`() {
        val decision = ToolPolicyEngine.evaluate(
            ToolMetadata(
                mutation = MutationClass.READ_ONLY,
                sensitiveData = SensitiveDataClass.CREDENTIALS,
                confirmation = ConfirmationMode.NEVER,
            ),
            remote(),
        )
        assertEquals(PolicyDecisionType.DENY, decision.type)
    }

    @Test
    fun `remote explicit non-sensitive read is auto allowed with grant`() {
        val decision = ToolPolicyEngine.evaluate(
            ToolMetadata(
                mutation = MutationClass.READ_ONLY,
                sensitiveData = SensitiveDataClass.NONE,
            ),
            remote(),
        )
        assertEquals(PolicyDecisionType.ALLOW, decision.type)
    }

    @Test
    fun `remote mutation requires confirmation`() {
        val decision = ToolPolicyEngine.evaluate(
            ToolMetadata(
                mutation = MutationClass.MUTATING,
                sensitiveData = SensitiveDataClass.NONE,
            ),
            remote(),
        )
        assertEquals(PolicyDecisionType.CONFIRM, decision.type)
    }

    @Test
    fun `remote personal read requires confirmation`() {
        val decision = ToolPolicyEngine.evaluate(
            ToolMetadata(
                mutation = MutationClass.READ_ONLY,
                sensitiveData = SensitiveDataClass.PERSONAL,
            ),
            remote(),
        )
        assertEquals(PolicyDecisionType.CONFIRM, decision.type)
    }

    @Test
    fun `unknown metadata cannot auto authorize remote invocation`() {
        val decision = ToolPolicyEngine.evaluate(ToolMetadata(), remote())
        assertEquals(PolicyDecisionType.CONFIRM, decision.type)
    }

    @Test
    fun `destructive operation always confirms when granted`() {
        val decision = ToolPolicyEngine.evaluate(
            ToolMetadata(
                destructive = true,
                mutation = MutationClass.MUTATING,
                sensitiveData = SensitiveDataClass.NONE,
                confirmation = ConfirmationMode.NEVER,
            ),
            remote(),
        )
        assertEquals(PolicyDecisionType.CONFIRM, decision.type)
    }

    @Test
    fun `always confirmation overrides otherwise safe local tool`() {
        val decision = ToolPolicyEngine.evaluate(
            ToolMetadata(
                mutation = MutationClass.READ_ONLY,
                sensitiveData = SensitiveDataClass.NONE,
                confirmation = ConfirmationMode.ALWAYS,
            ),
            local(),
        )
        assertEquals(PolicyDecisionType.CONFIRM, decision.type)
    }

    @Test
    fun `local explicit read is allowed with grant`() {
        val decision = ToolPolicyEngine.evaluate(
            ToolMetadata(
                mutation = MutationClass.READ_ONLY,
                sensitiveData = SensitiveDataClass.PERSONAL,
            ),
            local(),
        )
        assertEquals(PolicyDecisionType.ALLOW, decision.type)
    }
}
