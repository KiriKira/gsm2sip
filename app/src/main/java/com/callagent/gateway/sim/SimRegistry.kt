package com.callagent.gateway.sim

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.os.UserHandle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.root.MagiskRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Local binding between server-assigned SIM UUIDs and this phone's active
 * subscriptions. ICCIDs are only read transiently to make a salted local
 * fingerprint; neither the ICCID nor its fingerprint leaves this device.
 *
 * The registry deliberately separates SMS availability from voice-account
 * availability. Voice requires an exact, unique Telephony/Telecom association;
 * it never infers a subId from the opaque PhoneAccountHandle id.
 */
object SimRegistry {

    private const val PREFS_NAME = "sim_registry_v1"
    private const val KEY_REVISION = "mapping_revision"
    private const val KEY_LOCAL_BARRIER = "local_revision_barrier"
    private const val KEY_OWNER_ID = "server_owner_id"
    private const val KEY_SALT = "fingerprint_salt"
    private const val KEY_MAPPINGS = "mappings"

    private val lock = Any()

    enum class IdentityState {
        FINGERPRINT_VERIFIED,
        LOCAL_CONFIRMED,
        UNVERIFIED
    }

    internal enum class VoiceAccountApiPath {
        MAGISK_BROKER,
        PUBLIC_REVERSE,
        PUBLIC_FORWARD_AND_REVERSE
    }

    /** Pure safety predicates shared by registry operations and regression tests. */
    internal object Policy {
        fun voiceAccountApiPath(apiLevel: Int): VoiceAccountApiPath = when {
            apiLevel >= Build.VERSION_CODES.S -> VoiceAccountApiPath.PUBLIC_FORWARD_AND_REVERSE
            apiLevel >= Build.VERSION_CODES.R -> VoiceAccountApiPath.PUBLIC_REVERSE
            else -> VoiceAccountApiPath.MAGISK_BROKER
        }

        fun fingerprintMismatch(pinned: String?, observed: String?): Boolean =
            pinned != null && observed != null && pinned != observed

        fun newFingerprintNeedsConfirmation(
            identityState: IdentityState,
            pinned: String?,
            observed: String?
        ): Boolean = identityState == IdentityState.LOCAL_CONFIRMED && pinned == null && observed != null

        fun localConfirmationExpired(
            identityState: IdentityState,
            currentlyObservedFingerprint: String?,
            confirmedBootCount: Int?,
            currentBootCount: Int?
        ): Boolean = identityState == IdentityState.LOCAL_CONFIRMED && currentlyObservedFingerprint == null &&
            (confirmedBootCount == null || currentBootCount == null || confirmedBootCount != currentBootCount)

        fun canUseSingleConfirmation(
            currentRevision: Long,
            proposedRevision: Long?,
            bindingChanged: Boolean
        ): Boolean = !bindingChanged && (proposedRevision == null || proposedRevision == currentRevision)

        fun isNewProposal(currentRevision: Long, proposedRevision: Long): Boolean =
            proposedRevision > currentRevision

        fun resyncRevisionIsAcceptable(currentRevision: Long, serverRevision: Long): Boolean =
            serverRevision >= currentRevision

        fun heartbeatRevisionIsConsistent(
            currentRevision: Long,
            serverRevision: Long,
            invalidatedCount: Int,
            alreadyInvalidatedCount: Int = 0
        ): Boolean = if (invalidatedCount == 0) {
            serverRevision == currentRevision
        } else if (serverRevision == currentRevision) {
            alreadyInvalidatedCount == invalidatedCount
        } else {
            serverRevision > currentRevision
        }

        fun dispatchVersionsMatch(
            expectedRevision: Long,
            currentRevision: Long,
            expectedLocalBarrier: Long?,
            currentLocalBarrier: Long
        ): Boolean = expectedRevision == currentRevision &&
            (expectedLocalBarrier == null || expectedLocalBarrier == currentLocalBarrier)
    }

    enum class ErrorCode {
        SIM_MAPPING_CHANGED,
        SIM_UNAVAILABLE,
        SIM_ALREADY_MAPPED
    }

    class SimMappingException(
        val code: ErrorCode,
        message: String
    ) : IllegalStateException(message)

    data class SimMapping(
        val simId: String,
        val subscriptionId: Int,
        val slotIndex: Int,
        val mappingRevision: Long,
        val localRevisionBarrier: Long,
        val identityState: IdentityState,
        val phoneAccountHandle: PhoneAccountHandle?,
        val smsAvailable: Boolean,
        val voiceAvailable: Boolean
    )

    data class SimBindingConfirmation(val simId: String, val subscriptionId: Int)

    data class SimSubscription(
        val subscriptionId: Int,
        val slotIndex: Int,
        val displayName: String,
        val carrierName: String,
        val phoneNumber: String?,
        val isMapped: Boolean,
        val fingerprintPresent: Boolean,
        val voiceAvailable: Boolean
    )

    data class SimRegistrySnapshot(
        val mappingRevision: Long,
        /** Monotonic local invalidation counter; not sent as the wire revision. */
        val localRevisionBarrier: Long,
        val mappings: List<SimMapping>,
        val subscriptions: List<SimSubscription>
    )

    private data class StoredMapping(
        val simId: String,
        var subscriptionId: Int,
        var slotIndex: Int,
        /** Salted SHA-256 of ICCID, or null when the OS does not expose it. */
        var fingerprint: String?,
        var identityState: IdentityState,
        /** Used only for LOCAL_CONFIRMED mappings without an ICCID fingerprint. */
        var confirmedBootCount: Int?,
        /** Opaque account handle key; never parsed to infer a subscription. */
        var accountHandleKey: String?
    )

    private data class RegistryState(
        var mappingRevision: Long,
        var localRevisionBarrier: Long,
        var ownerId: String,
        val salt: ByteArray,
        val mappings: LinkedHashMap<String, StoredMapping>
    )

    private data class CurrentSubscription(
        val info: SubscriptionInfo,
        val fingerprint: String?,
        val phoneAccountHandle: PhoneAccountHandle?
    )

    /** Read current subscriptions, account associations, and local mappings. */
    @JvmStatic
    fun snapshot(context: Context): SimRegistrySnapshot = synchronized(lock) {
        val appContext = context.applicationContext
        val state = loadState(appContext)
        ensureGatewayOwner(appContext, state)
        val current = readCurrentSubscriptions(appContext, state.salt, includeVoice = true)
        refreshBindings(appContext, state, current)
        persist(appContext, state)
        makeSnapshot(appContext, state, current)
    }

    /** Clear server-owned SIM UUIDs after the gateway is paired to a new owner. */
    @JvmStatic
    fun resetForGatewayChange(context: Context) = synchronized(lock) {
        val appContext = context.applicationContext
        val state = loadState(appContext)
        state.ownerId = CredentialStore.load(appContext)?.gatewayId.orEmpty()
        state.mappings.clear()
        state.mappingRevision = 0L
        state.localRevisionBarrier = 0L
        persist(appContext, state)
    }

    /** Apply the server's authoritative heartbeat revision and local invalidations atomically. */
    @JvmStatic
    fun applyHeartbeatInvalidation(
        context: Context,
        serverRevision: Long,
        invalidatedSimIds: List<String>
    ): SimRegistrySnapshot = synchronized(lock) {
        val appContext = context.applicationContext
        val state = loadState(appContext)
        ensureGatewayOwner(appContext, state)
        if (state.ownerId.isEmpty()) {
            throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Gateway is not paired to a server")
        }
        if (serverRevision < state.mappingRevision) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "Heartbeat SIM mapping revision is stale")
        }

        val canonicalIds = invalidatedSimIds.map(::canonicalServerSimId)
        if (canonicalIds.distinct().size != canonicalIds.size) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "Heartbeat contains duplicate invalidated SIM ids")
        }
        val unknownIds = canonicalIds.filterNot(state.mappings::containsKey)
        if (unknownIds.isNotEmpty()) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "Heartbeat invalidated an unknown local SIM id")
        }
        val alreadyUnverifiedCount = canonicalIds.count {
            state.mappings.getValue(it).identityState == IdentityState.UNVERIFIED
        }
        if (!Policy.heartbeatRevisionIsConsistent(
                state.mappingRevision, serverRevision, canonicalIds.size, alreadyUnverifiedCount
            )) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "Heartbeat revision and invalidation set disagree")
        }

        canonicalIds.forEach { simId ->
            val mapping = state.mappings.getValue(simId)
            if (mapping.identityState != IdentityState.UNVERIFIED) {
                mapping.identityState = IdentityState.UNVERIFIED
                bumpLocalBarrier(state)
            }
        }
        state.mappingRevision = serverRevision
        val current = readCurrentSubscriptions(appContext, state.salt, includeVoice = true)
        refreshBindings(appContext, state, current)
        persist(appContext, state)
        makeSnapshot(appContext, state, current)
    }

    /** Fail closed after a 409/resync by adopting the server revision only. */
    @JvmStatic
    fun invalidateAllAtServerRevision(context: Context, serverRevision: Long): SimRegistrySnapshot = synchronized(lock) {
        val appContext = context.applicationContext
        val state = loadState(appContext)
        ensureGatewayOwner(appContext, state)
        if (state.ownerId.isEmpty()) {
            throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Gateway is not paired to a server")
        }
        if (!Policy.resyncRevisionIsAcceptable(state.mappingRevision, serverRevision)) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "Resync SIM mapping revision is stale")
        }
        state.mappings.values.forEach { mapping ->
            if (mapping.identityState != IdentityState.UNVERIFIED) {
                mapping.identityState = IdentityState.UNVERIFIED
                bumpLocalBarrier(state)
            }
        }
        state.mappingRevision = serverRevision
        val current = readCurrentSubscriptions(appContext, state.salt, includeVoice = true)
        refreshBindings(appContext, state, current)
        persist(appContext, state)
        makeSnapshot(appContext, state, current)
    }

    /** Re-confirm an existing unchanged binding after local user review. */
    @JvmStatic
    fun confirmMapping(context: Context, simId: String, subId: Int): SimMapping =
        confirmMapping(context, simId, subId, serverRevision = null)

    /**
     * Re-confirm the identity of an unchanged binding locally. New or changed
     * server bindings must use [confirmMappings] so one accepted proposal is
     * adopted atomically.
     */
    @JvmStatic
    fun confirmMapping(
        context: Context,
        simId: String,
        subId: Int,
        serverRevision: Long?
    ): SimMapping = synchronized(lock) {
        val appContext = context.applicationContext
        val canonicalSimId = canonicalServerSimId(simId)
        val state = loadState(appContext)
        ensureGatewayOwner(appContext, state)
        if (state.ownerId.isEmpty()) {
            throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Gateway is not paired to a server")
        }
        val current = readCurrentSubscriptions(appContext, state.salt, includeVoice = true)
        refreshBindings(appContext, state, current)
        if (serverRevision != null && serverRevision < state.mappingRevision) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "Server SIM mapping revision is stale")
        }
        val selected = current.firstOrNull { it.info.subscriptionId == subId }
            ?: throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Selected SIM subscription is unavailable")

        val otherBinding = state.mappings.values.firstOrNull {
            it.simId != canonicalSimId && it.subscriptionId == subId &&
                !Policy.fingerprintMismatch(it.fingerprint, selected.fingerprint)
        }
        if (otherBinding != null) {
            throw SimMappingException(
                ErrorCode.SIM_ALREADY_MAPPED,
                "Selected SIM is already bound to another server SIM id"
            )
        }

        val slot = selected.info.simSlotIndex
        val currentAccountKey = selected.phoneAccountHandle?.let(::accountHandleKey)
        val existing = state.mappings[canonicalSimId]
        if (Policy.fingerprintMismatch(existing?.fingerprint, selected.fingerprint)) {
            throw SimMappingException(
                ErrorCode.SIM_MAPPING_CHANGED,
                "This server SIM id is already pinned to a different SIM; request a new SIM id"
            )
        }
        if (existing?.fingerprint != null && selected.fingerprint == null &&
            existing.subscriptionId != subId) {
            throw SimMappingException(
                ErrorCode.SIM_MAPPING_CHANGED,
                "The existing SIM identity cannot be checked; confirm it again in the same subscription"
            )
        }
        val identityState = if (selected.fingerprint != null) {
            IdentityState.FINGERPRINT_VERIFIED
        } else {
            IdentityState.LOCAL_CONFIRMED
        }
        val accountChanged = existing?.accountHandleKey != null && currentAccountKey != null &&
            existing.accountHandleKey != currentAccountKey
        val bindingChanged = existing == null ||
            existing.subscriptionId != subId ||
            existing.slotIndex != slot ||
            accountChanged
        if (!Policy.canUseSingleConfirmation(state.mappingRevision, serverRevision, bindingChanged)) {
            throw SimMappingException(
                ErrorCode.SIM_MAPPING_CHANGED,
                "New or changed SIM bindings must be confirmed as a complete server proposal"
            )
        }
        state.mappings[canonicalSimId] = StoredMapping(
            simId = canonicalSimId,
            subscriptionId = subId,
            slotIndex = slot,
            fingerprint = selected.fingerprint ?: existing?.fingerprint,
            identityState = identityState,
            confirmedBootCount = if (selected.fingerprint == null) readBootCount(appContext) else null,
            accountHandleKey = currentAccountKey ?: existing?.accountHandleKey
        )
        persist(appContext, state)
        mappingFor(state, state.mappings.getValue(canonicalSimId), selected)
    }

    /** Atomically adopt the complete active local binding set from one server proposal. */
    @JvmStatic
    fun confirmMappings(
        context: Context,
        confirmations: List<SimBindingConfirmation>,
        serverRevision: Long
    ): List<SimMapping> = synchronized(lock) {
        val canonical = confirmations.map { canonicalServerSimId(it.simId) }
        if (canonical.distinct().size != canonical.size ||
            confirmations.map { it.subscriptionId }.distinct().size != confirmations.size) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "SIM binding confirmation contains duplicates")
        }

        val appContext = context.applicationContext
        val state = loadState(appContext)
        ensureGatewayOwner(appContext, state)
        if (state.ownerId.isEmpty()) {
            throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Gateway is not paired to a server")
        }
        val currentSubscriptions = readCurrentSubscriptions(appContext, state.salt, includeVoice = true)
        refreshBindings(appContext, state, currentSubscriptions)
        if (!Policy.isNewProposal(state.mappingRevision, serverRevision)) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "Changed SIM binding requires a newer server revision")
        }
        val current = currentSubscriptions.associateBy { it.info.subscriptionId }
        val prepared = confirmations.mapIndexed { index, confirmation ->
            val simId = canonical[index]
            val selected = current[confirmation.subscriptionId]
                ?: throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Selected SIM subscription is unavailable")
            val prior = state.mappings[simId]
            if (Policy.fingerprintMismatch(prior?.fingerprint, selected.fingerprint)) {
                throw SimMappingException(
                    ErrorCode.SIM_MAPPING_CHANGED,
                    "This server SIM id is pinned to a different SIM; request a new SIM id"
                )
            }
            if (prior?.fingerprint != null && selected.fingerprint == null &&
                prior.subscriptionId != confirmation.subscriptionId) {
                throw SimMappingException(
                    ErrorCode.SIM_MAPPING_CHANGED,
                    "The existing SIM identity cannot be checked on the new subscription"
                )
            }
            val other = state.mappings.values.firstOrNull {
                it.simId != simId && it.subscriptionId == confirmation.subscriptionId &&
                    !Policy.fingerprintMismatch(it.fingerprint, selected.fingerprint)
            }
            if (other != null) {
                throw SimMappingException(ErrorCode.SIM_ALREADY_MAPPED, "Selected SIM is already bound to another server SIM id")
            }
            val accountKey = selected.phoneAccountHandle?.let(::accountHandleKey)
            StoredMapping(
                simId = simId,
                subscriptionId = confirmation.subscriptionId,
                slotIndex = selected.info.simSlotIndex,
                fingerprint = selected.fingerprint ?: prior?.fingerprint,
                identityState = if (selected.fingerprint != null) IdentityState.FINGERPRINT_VERIFIED else IdentityState.LOCAL_CONFIRMED,
                confirmedBootCount = if (selected.fingerprint == null) readBootCount(appContext) else null,
                accountHandleKey = accountKey ?: prior?.accountHandleKey
            ) to selected
        }
        state.mappingRevision = serverRevision
        // This is a complete server proposal, not a partial update. Keeping an
        // old binding omitted from the accepted response would make it appear
        // current under the new revision after a SIM was removed/unverified.
        state.mappings.clear()
        prepared.forEach { (stored, _) -> state.mappings[stored.simId] = stored }
        persist(appContext, state)
        prepared.map { (stored, currentSubscription) -> mappingFor(state, stored, currentSubscription) }
    }

    /**
     * Resolve a mapping for a SIM-directed operation such as SMS. This path
     * does not wait on the Telecom broker; voice dispatch performs a separate
     * exact account revalidation immediately before using Telecom.
     */
    @JvmStatic
    fun resolve(
        context: Context,
        simId: String,
        expectedRevision: Long,
        expectedLocalRevisionBarrier: Long? = null
    ): SimMapping = resolveInternal(
        context,
        simId,
        expectedRevision,
        expectedLocalRevisionBarrier,
        includeVoice = false
    )

    private fun resolveInternal(
        context: Context,
        simId: String,
        expectedRevision: Long,
        expectedLocalRevisionBarrier: Long?,
        includeVoice: Boolean
    ): SimMapping = synchronized(lock) {
        val appContext = context.applicationContext
        val state = loadState(appContext)
        ensureGatewayOwner(appContext, state)
        val active = readCurrentSubscriptions(appContext, state.salt, includeVoice)
        refreshBindings(appContext, state, active)
        persist(appContext, state)

        val canonicalSimId = canonicalServerSimId(simId)
        if (state.ownerId.isEmpty()) {
            throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Gateway is not paired to a server")
        }
        if (!Policy.dispatchVersionsMatch(
                expectedRevision, state.mappingRevision,
                expectedLocalRevisionBarrier, state.localRevisionBarrier
            )) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "SIM mapping changed after command validation")
        }
        val stored = state.mappings[canonicalSimId]
            ?: throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "SIM id is not locally mapped")
        val current = active.firstOrNull { it.info.subscriptionId == stored.subscriptionId }
            ?: throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Mapped SIM subscription is unavailable")
        if (Policy.fingerprintMismatch(stored.fingerprint, current.fingerprint) ||
            Policy.newFingerprintNeedsConfirmation(stored.identityState, stored.fingerprint, current.fingerprint)) {
            if (stored.identityState != IdentityState.UNVERIFIED) {
                stored.identityState = IdentityState.UNVERIFIED
                bumpLocalBarrier(state)
                persist(appContext, state)
            }
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "SIM fingerprint changed or needs local confirmation")
        }
        if (stored.identityState == IdentityState.UNVERIFIED) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "SIM identity needs local confirmation")
        }
        if (current.info.simSlotIndex != stored.slotIndex) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "Mapped SIM moved to a different slot")
        }
        mappingFor(state, stored, current)
    }

    /** Resolve a mapping that is safe for explicit Telecom placeCall. */
    @JvmStatic
    fun resolveForVoice(
        context: Context,
        simId: String,
        expectedRevision: Long,
        expectedLocalRevisionBarrier: Long? = null
    ): SimMapping {
        MagiskRuntime.invalidatePhoneAccounts()
        val mapping = resolveInternal(
            context,
            simId,
            expectedRevision,
            expectedLocalRevisionBarrier,
            includeVoice = true
        )
        if (!mapping.voiceAvailable || mapping.phoneAccountHandle == null) {
            throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "No verified Telecom account is available for this SIM")
        }
        return mapping
    }

    /** Resolve an incoming Telecom call's exact account handle to a local SIM. */
    @JvmStatic
    fun resolvePhoneAccount(context: Context, handle: PhoneAccountHandle?): SimMapping? {
        if (handle == null) return null
        MagiskRuntime.invalidatePhoneAccounts()
        return synchronized(lock) {
            val appContext = context.applicationContext
            val state = loadState(appContext)
            ensureGatewayOwner(appContext, state)
            val current = readCurrentSubscriptions(appContext, state.salt, includeVoice = true)
            refreshBindings(appContext, state, current)
            val match = state.mappings.values.firstNotNullOfOrNull { stored ->
                val subscription = current.firstOrNull { it.info.subscriptionId == stored.subscriptionId }
                    ?: return@firstNotNullOfOrNull null
                val subscriptionAccountKey = subscription.phoneAccountHandle?.let(::accountHandleKey)
                val identityChanged = subscription.info.simSlotIndex != stored.slotIndex ||
                    Policy.fingerprintMismatch(stored.fingerprint, subscription.fingerprint) ||
                    Policy.newFingerprintNeedsConfirmation(
                        stored.identityState, stored.fingerprint, subscription.fingerprint
                    ) ||
                    (stored.identityState == IdentityState.FINGERPRINT_VERIFIED &&
                        stored.fingerprint != null && subscription.fingerprint == null) ||
                    (stored.accountHandleKey != null && subscriptionAccountKey != null &&
                        stored.accountHandleKey != subscriptionAccountKey)
                if (identityChanged && stored.identityState != IdentityState.UNVERIFIED) {
                    stored.identityState = IdentityState.UNVERIFIED
                    bumpLocalBarrier(state)
                }
                if (identityChanged || stored.identityState == IdentityState.UNVERIFIED ||
                    subscription.phoneAccountHandle != handle ||
                    handle.userHandle != UserHandle.getUserHandleForUid(Process.myUid())) {
                    null
                } else {
                    mappingFor(state, stored, subscription)
                }
            }
            persist(appContext, state)
            match?.takeIf { it.voiceAvailable && it.phoneAccountHandle == handle }
        }
    }

    private fun makeSnapshot(
        context: Context,
        state: RegistryState,
        current: List<CurrentSubscription> = readCurrentSubscriptions(context, state.salt)
    ): SimRegistrySnapshot {
        val currentBySubId = current.associateBy { it.info.subscriptionId }
        val mappings = state.mappings.values.map { stored ->
            val selected = currentBySubId[stored.subscriptionId]
            mappingFor(state, stored, selected)
        }
        val mappedSubIds = state.mappings.values.mapTo(HashSet()) { it.subscriptionId }
        val subscriptions = current.map { item ->
            val info = item.info
            SimSubscription(
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                displayName = info.displayName?.toString().orEmpty(),
                carrierName = info.carrierName?.toString().orEmpty(),
                phoneNumber = info.number?.trim()?.ifEmpty { null },
                isMapped = info.subscriptionId in mappedSubIds,
                fingerprintPresent = item.fingerprint != null,
                voiceAvailable = item.phoneAccountHandle != null
            )
        }
        return SimRegistrySnapshot(state.mappingRevision, state.localRevisionBarrier, mappings, subscriptions)
    }

    private fun mappingFor(
        state: RegistryState,
        stored: StoredMapping,
        current: CurrentSubscription?
    ): SimMapping {
        val account = current?.phoneAccountHandle
        val accountMatches = stored.accountHandleKey == null ||
            (account != null && stored.accountHandleKey == accountHandleKey(account))
        val voiceAvailable = account != null && accountMatches
        return SimMapping(
            simId = stored.simId,
            subscriptionId = stored.subscriptionId,
            slotIndex = stored.slotIndex,
            mappingRevision = state.mappingRevision,
            localRevisionBarrier = state.localRevisionBarrier,
            identityState = stored.identityState,
            phoneAccountHandle = if (voiceAvailable) account else null,
            smsAvailable = current != null && stored.identityState != IdentityState.UNVERIFIED,
            voiceAvailable = voiceAvailable
        )
    }

    private fun refreshBindings(
        context: Context,
        state: RegistryState,
        subscriptions: List<CurrentSubscription> = readCurrentSubscriptions(context, state.salt)
    ) {
        val current = subscriptions.associateBy { it.info.subscriptionId }
        state.mappings.values.forEach { stored ->
            val active = current[stored.subscriptionId]
            if (active == null) {
                if (stored.identityState != IdentityState.UNVERIFIED) {
                    bumpLocalBarrier(state)
                    stored.identityState = IdentityState.UNVERIFIED
                }
                return@forEach
            }
            val slotChanged = active.info.simSlotIndex != stored.slotIndex
            val fingerprintMismatch = Policy.fingerprintMismatch(stored.fingerprint, active.fingerprint)
            // A missing fingerprint requires a new local confirmation after a
            // fingerprint-based binding. A prior LOCAL_CONFIRMED binding is
            // already the explicit fallback for devices that cannot expose it.
            val fingerprintUnavailable = stored.fingerprint != null && active.fingerprint == null &&
                stored.identityState == IdentityState.FINGERPRINT_VERIFIED
            val handleKey = active.phoneAccountHandle?.let(::accountHandleKey)
            val accountChanged = stored.accountHandleKey != null && handleKey != null &&
                stored.accountHandleKey != handleKey
            val currentBootCount = readBootCount(context)
            val localConfirmationExpired = Policy.localConfirmationExpired(
                stored.identityState, active.fingerprint, stored.confirmedBootCount, currentBootCount
            )

            if (slotChanged || fingerprintMismatch || fingerprintUnavailable || accountChanged || localConfirmationExpired) {
                if (stored.identityState != IdentityState.UNVERIFIED) bumpLocalBarrier(state)
                stored.identityState = IdentityState.UNVERIFIED
            } else if (Policy.newFingerprintNeedsConfirmation(
                    stored.identityState, stored.fingerprint, active.fingerprint
                )) {
                // A newly readable ICCID cannot prove that this is the same
                // physical card the user confirmed while no fingerprint was
                // available. Require another local confirmation before pinning it.
                stored.identityState = IdentityState.UNVERIFIED
                bumpLocalBarrier(state)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readCurrentSubscriptions(
        context: Context,
        salt: ByteArray,
        includeVoice: Boolean = true
    ): List<CurrentSubscription> {
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            ?: throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Subscription service is unavailable")
        val subscriptions = try {
            subscriptionManager.activeSubscriptionInfoList.orEmpty()
        } catch (e: SecurityException) {
            throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Cannot read active SIM subscriptions")
        } catch (e: RuntimeException) {
            throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Cannot read active SIM subscriptions")
        }
        val voiceHandles = if (includeVoice) resolveVoiceHandles(context, subscriptions) else emptyMap()
        return subscriptions.map { info ->
            CurrentSubscription(
                info = info,
                fingerprint = fingerprint(info, salt),
                phoneAccountHandle = voiceHandles[info.subscriptionId]
            )
        }
    }

    private fun resolveVoiceHandles(
        context: Context,
        subscriptions: List<SubscriptionInfo>
    ): Map<Int, PhoneAccountHandle> {
        val publicResult = publicVoiceHandles(context, subscriptions)
        if (publicResult != null) return publicResult

        val rootResult = MagiskRuntime.phoneAccounts().orEmpty()
        val activeCounts = subscriptions.groupingBy { it.subscriptionId }.eachCount()
        return rootResult.filterKeys { activeCounts[it] == 1 }
    }

    /**
     * API 30 adds the public PhoneAccountHandle -> subscriptionId query; API
     * 31 adds the public subId-pinned manager -> PhoneAccountHandle direction.
     * These checks keep pre-31 support capability-based rather than disabling
     * voice for an entire Android release.
     */
    @SuppressLint("MissingPermission", "NewApi")
    private fun publicVoiceHandles(
        context: Context,
        subscriptions: List<SubscriptionInfo>
    ): Map<Int, PhoneAccountHandle>? {
        val apiPath = Policy.voiceAccountApiPath(Build.VERSION.SDK_INT)
        if (apiPath == VoiceAccountApiPath.MAGISK_BROKER) return null
        return try {
            val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
            val expectedUser = UserHandle.getUserHandleForUid(Process.myUid())
            val callCapable = telecom.callCapablePhoneAccounts.orEmpty().distinct()
            val activeCounts = subscriptions.groupingBy { it.subscriptionId }.eachCount()
            val uniqueActive = activeCounts.filterValues { it == 1 }.keys
            val candidates = ArrayList<Pair<Int, PhoneAccountHandle>>()

            if (apiPath == VoiceAccountApiPath.PUBLIC_FORWARD_AND_REVERSE) {
                for (subId in uniqueActive) {
                    val handle = telephony.createForSubscriptionId(subId).phoneAccountHandle ?: continue
                    if (handle.userHandle != expectedUser || handle !in callCapable) continue
                    val account = telecom.getPhoneAccount(handle) ?: continue
                    if (!account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) continue
                    if (telephony.getSubscriptionId(handle) != subId) continue
                    candidates += subId to handle
                }
            } else {
                for (handle in callCapable) {
                    if (handle.userHandle != expectedUser) continue
                    val account = telecom.getPhoneAccount(handle) ?: continue
                    if (!account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) continue
                    val subId = telephony.getSubscriptionId(handle)
                    if (subId !in uniqueActive) continue
                    candidates += subId to handle
                }
            }

            val subCounts = candidates.groupingBy { it.first }.eachCount()
            val handleCounts = candidates.groupingBy { it.second }.eachCount()
            candidates.asSequence()
                .filter { subCounts[it.first] == 1 && handleCounts[it.second] == 1 }
                .associate { it.first to it.second }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun fingerprint(info: SubscriptionInfo, salt: ByteArray): String? {
        val iccid = try {
            info.iccId?.trim()?.takeIf { it.isNotEmpty() && it.any(Char::isDigit) }
        } catch (_: SecurityException) {
            null
        } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val bytes = digest.digest(iccid.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun accountHandleKey(handle: PhoneAccountHandle): String =
        "${handle.componentName.flattenToString()}|${handle.id}"

    private fun canonicalServerSimId(simId: String): String {
        val canonical = try {
            UUID.fromString(simId).toString()
        } catch (_: IllegalArgumentException) {
            null
        }
        if (canonical == null || !canonical.equals(simId, ignoreCase = true)) {
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "SIM id must be a server-assigned UUID")
        }
        return canonical
    }

    private fun readBootCount(context: Context): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: RuntimeException) {
        null
    }

    private fun loadState(context: Context): RegistryState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encodedSalt = prefs.getString(KEY_SALT, null)
        val salt = if (encodedSalt == null) {
            ByteArray(32).also(SecureRandom()::nextBytes)
        } else {
            try {
                android.util.Base64.decode(encodedSalt, android.util.Base64.NO_WRAP)
                    .also { if (it.size != 32) throw IllegalArgumentException("Invalid salt length") }
            } catch (_: IllegalArgumentException) {
                throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "Local SIM registry is corrupt")
            }
        }

        val mappings = LinkedHashMap<String, StoredMapping>()
        val rawMappings = prefs.getString(KEY_MAPPINGS, "[]") ?: "[]"
        try {
            val array = JSONArray(rawMappings)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val simId = canonicalServerSimId(item.getString("sim_id"))
                val mapping = StoredMapping(
                    simId = simId,
                    subscriptionId = item.getInt("sub_id"),
                    slotIndex = item.getInt("slot_index"),
                    fingerprint = item.optString("fingerprint").takeIf { it.isNotEmpty() },
                    identityState = IdentityState.valueOf(item.getString("identity_state")),
                    confirmedBootCount = item.optInt("confirmed_boot_count", -1).takeIf { it >= 0 },
                    accountHandleKey = item.optString("phone_account").takeIf { it.isNotEmpty() }
                )
                if (mappings.put(simId, mapping) != null) {
                    throw IllegalArgumentException("Duplicate sim_id")
                }
            }
        } catch (e: Exception) {
            if (e is SimMappingException) throw e
            throw SimMappingException(ErrorCode.SIM_MAPPING_CHANGED, "Local SIM registry is corrupt")
        }

        return RegistryState(
            mappingRevision = prefs.getLong(KEY_REVISION, 0L).coerceAtLeast(0L),
            localRevisionBarrier = prefs.getLong(KEY_LOCAL_BARRIER, 0L).coerceAtLeast(0L),
            ownerId = prefs.getString(KEY_OWNER_ID, null).orEmpty(),
            salt = salt,
            mappings = mappings
        )
    }

    private fun persist(context: Context, state: RegistryState) {
        val array = JSONArray()
        state.mappings.values.forEach { mapping ->
            array.put(JSONObject().apply {
                put("sim_id", mapping.simId)
                put("sub_id", mapping.subscriptionId)
                put("slot_index", mapping.slotIndex)
                put("fingerprint", mapping.fingerprint ?: "")
                put("identity_state", mapping.identityState.name)
                put("confirmed_boot_count", mapping.confirmedBootCount ?: -1)
                put("phone_account", mapping.accountHandleKey ?: "")
            })
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.edit()
            .putLong(KEY_REVISION, state.mappingRevision)
            .putLong(KEY_LOCAL_BARRIER, state.localRevisionBarrier)
            .putString(KEY_OWNER_ID, state.ownerId)
            .putString(KEY_SALT, android.util.Base64.encodeToString(state.salt, android.util.Base64.NO_WRAP))
            .putString(KEY_MAPPINGS, array.toString())
            .commit()
        if (!saved) {
            throw SimMappingException(ErrorCode.SIM_UNAVAILABLE, "Could not persist local SIM mapping")
        }
    }

    private fun bumpLocalBarrier(state: RegistryState) {
        if (state.localRevisionBarrier < Long.MAX_VALUE) state.localRevisionBarrier++
    }

    private fun ensureGatewayOwner(context: Context, state: RegistryState) {
        val ownerId = CredentialStore.load(context)?.gatewayId.orEmpty()
        if (state.ownerId == ownerId) return
        state.mappings.clear()
        state.mappingRevision = 0L
        state.localRevisionBarrier = 0L
        state.ownerId = ownerId
    }
}
