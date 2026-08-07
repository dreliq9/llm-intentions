package com.androidmcp.core.policy

import com.androidmcp.core.protocol.MutationClass
import com.androidmcp.core.protocol.SensitiveDataClass
import com.androidmcp.core.protocol.ToolMetadata
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfirmingToolAuthorizerTest {
    private val secret = ByteArray(32) { index -> (index + 7).toByte() }
    private var now = 1_800_000_000_000L
    private val codec = PolicyRequestStateCodec(
        secret = secret,
        nowMs = { now },
        ttlMs = 60_000L,
    )

    private val remote = ToolInvocationSecurityContext(
        origin = InvocationOrigin.REMOTE_PROVIDER,
        principalId = "relay:user-1",
    )

    private fun request(
        metadata: ToolMetadata,
        arguments: JsonObject = buildJsonObject { put("value", 1) },
        inputResponses: JsonObject? = null,
        requestState: String? = null,
        context: ToolInvocationSecurityContext = remote,
    ) = ToolAuthorizationRequest(
        toolName = "device.action",
        metadata = metadata,
        arguments = arguments,
        inputResponses = inputResponses,
        requestState = requestState,
        securityContext = context,
        supportsInputRequired = true,
    )

    private fun acceptedResponse(approved: Boolean = true) = buildJsonObject {
        put(ConfirmingToolAuthorizer.CONFIRMATION_REQUEST_KEY, buildJsonObject {
            put("action", "accept")
            put("content", buildJsonObject { put("approved", approved) })
        })
    }

    @Test
    fun `remote call without grant is denied`() = runBlocking {
        val authorizer = ConfirmingToolAuthorizer(
            grantChecker = ToolGrantChecker { _, _ -> false },
            stateCodec = codec,
            nowMs = { now },
        )
        val outcome = authorizer.authorize(request(
            ToolMetadata(
                mutation = MutationClass.READ_ONLY,
                sensitiveData = SensitiveDataClass.NONE,
            )
        ))
        assertIs<ToolAuthorizationOutcome.Deny>(outcome)
    }

    @Test
    fun `granted non-sensitive remote read is allowed`() = runBlocking {
        val authorizer = ConfirmingToolAuthorizer(
            grantChecker = ToolGrantChecker { _, _ -> true },
            stateCodec = codec,
            nowMs = { now },
        )
        val outcome = authorizer.authorize(request(
            ToolMetadata(
                mutation = MutationClass.READ_ONLY,
                sensitiveData = SensitiveDataClass.NONE,
            )
        ))
        assertIs<ToolAuthorizationOutcome.Allow>(outcome)
    }

    @Test
    fun `granted remote mutation requires approval then allows exactly once`() = runBlocking {
        val authorizer = ConfirmingToolAuthorizer(
            grantChecker = ToolGrantChecker { _, _ -> true },
            stateCodec = codec,
            nowMs = { now },
        )
        val metadata = ToolMetadata(
            mutation = MutationClass.MUTATING,
            sensitiveData = SensitiveDataClass.NONE,
        )

        val initial = assertIs<ToolAuthorizationOutcome.InputRequired>(
            authorizer.authorize(request(metadata))
        )
        val state = initial.result.requestState!!

        assertIs<ToolAuthorizationOutcome.Allow>(
            authorizer.authorize(request(
                metadata = metadata,
                inputResponses = acceptedResponse(),
                requestState = state,
            ))
        )

        val replay = authorizer.authorize(request(
            metadata = metadata,
            inputResponses = acceptedResponse(),
            requestState = state,
        ))
        val denied = assertIs<ToolAuthorizationOutcome.Deny>(replay)
        assertTrue(denied.reason.contains("already used") || denied.reason.contains("unknown"))
    }

    @Test
    fun `decline consumes state and never allows`() = runBlocking {
        val authorizer = ConfirmingToolAuthorizer(
            grantChecker = ToolGrantChecker { _, _ -> true },
            stateCodec = codec,
            nowMs = { now },
        )
        val metadata = ToolMetadata(
            mutation = MutationClass.MUTATING,
            sensitiveData = SensitiveDataClass.NONE,
        )
        val initial = assertIs<ToolAuthorizationOutcome.InputRequired>(
            authorizer.authorize(request(metadata))
        )
        val declined = buildJsonObject {
            put(ConfirmingToolAuthorizer.CONFIRMATION_REQUEST_KEY, buildJsonObject {
                put("action", "decline")
            })
        }

        assertIs<ToolAuthorizationOutcome.Deny>(
            authorizer.authorize(request(
                metadata = metadata,
                inputResponses = declined,
                requestState = initial.result.requestState,
            ))
        )
    }

    @Test
    fun `confirmation cannot be transplanted to changed arguments`() = runBlocking {
        val authorizer = ConfirmingToolAuthorizer(
            grantChecker = ToolGrantChecker { _, _ -> true },
            stateCodec = codec,
            nowMs = { now },
        )
        val metadata = ToolMetadata(
            mutation = MutationClass.MUTATING,
            sensitiveData = SensitiveDataClass.NONE,
        )
        val initial = assertIs<ToolAuthorizationOutcome.InputRequired>(
            authorizer.authorize(request(metadata))
        )
        val changedArgs = buildJsonObject { put("value", 2) }

        val denied = assertIs<ToolAuthorizationOutcome.Deny>(
            authorizer.authorize(request(
                metadata = metadata,
                arguments = changedArgs,
                inputResponses = acceptedResponse(),
                requestState = initial.result.requestState,
            ))
        )
        assertTrue(denied.reason.contains("arguments"))
    }

    @Test
    fun `authenticated local developer path remains compatible during metadata backfill`() = runBlocking {
        val authorizer = ConfirmingToolAuthorizer(
            grantChecker = ToolGrantChecker { _, _ -> false },
            stateCodec = codec,
            nowMs = { now },
            allowAuthenticatedLocalDeveloper = true,
        )
        val local = ToolInvocationSecurityContext(
            origin = InvocationOrigin.LOCAL_DEVELOPER,
            principalId = "local:developer",
        )

        assertIs<ToolAuthorizationOutcome.Allow>(
            authorizer.authorize(request(
                metadata = ToolMetadata(),
                context = local,
            ))
        )
    }
}
