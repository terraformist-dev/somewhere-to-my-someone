package dev.terraformist.someone

import android.content.Context
import org.json.JSONObject

/**
 * Pairing two phones, and only two.
 *
 * Phone A makes a pair in Firebase with its public key and puts a six-digit code next to it.
 * Phone B types the code, finds the pair and adds itself and its public key. The rules only let
 * the second seat be taken once, so a third phone can never join. Each phone then works out the
 * shared secret on its own. To pair with someone else, you unpair first, which removes the pair
 * from Firebase for both of you.
 *
 * Everything here blocks on the network: call it off the main thread.
 */
object Pairing {

    /** What went wrong, in words for the screen. */
    class PairingException(message: String) : Exception(message)

    private const val CODE_LIFETIME_MS = 10 * 60_000L

    /** Make a pair and a code for the other phone to type. Returns the code. */
    fun makeCode(context: Context): Us.Pending {
        cancelCode(context)
        val uid = Cloud.uid(context)
        val keys = Crypto.newKeyPair()
        val pairId = Crypto.randomId()
        Cloud.patch(context, "pairs/$pairId", JSONObject().put("a", uid).put("ka", Crypto.publicOf(keys)))

        // Six digits can clash with someone else's live code (only if they share this Firebase), so try a few.
        repeat(6) {
            val code = Crypto.randomCode()
            val expires = System.currentTimeMillis() + CODE_LIFETIME_MS
            try {
                Cloud.put(context, "codes/$code", JSONObject().put("p", pairId).put("o", uid).put("x", expires))
                val pending = Us.Pending(code, pairId, Crypto.privateOf(keys), expires)
                Us.savePending(context, pending)
                return pending
            } catch (e: Cloud.CloudException) {
                if (e.code != 401 && e.code != 403) throw e
            }
        }
        throw PairingException("COULDN'T MAKE A CODE. TRY AGAIN.")
    }

    /** If the other phone has typed our code, finish pairing and return true. */
    fun checkCode(context: Context): Boolean {
        val pending = Us.pending(context) ?: return Us.paired(context)
        val pair = Cloud.get(context, "pairs/${pending.pairId}") as? JSONObject ?: return false
        val theirKey = pair.optString("kb", "")
        if (pair.optString("b", "").isEmpty() || theirKey.isEmpty()) return false
        val secret = Crypto.sharedSecret(Crypto.privateFrom(pending.privateKey), theirKey, pending.pairId)
        Us.savePair(context, pending.pairId, "a", secret)
        try {
            Cloud.delete(context, "codes/${pending.code}")
        } catch (e: Exception) {
            // It runs out on its own anyway.
        }
        return true
    }

    /** Stop offering a code (and remove the half-made pair). */
    fun cancelCode(context: Context) {
        val pending = Us.pending(context) ?: return
        Us.clearPending(context)
        try {
            Cloud.delete(context, "codes/${pending.code}")
            Cloud.delete(context, "pairs/${pending.pairId}")
        } catch (e: Exception) {
            // Nothing else to do; an unused code runs out after ten minutes.
        }
    }

    /** Type in the other phone's code. */
    fun join(context: Context, typed: String) {
        val code = typed.filter { it.isDigit() }
        if (code.length != 6) throw PairingException("THE CODE IS SIX NUMBERS")
        val uid = Cloud.uid(context)
        val entry = Cloud.get(context, "codes/$code") as? JSONObject
            ?: throw PairingException("THAT CODE DIDN'T WORK")
        if (entry.optLong("x", 0L) < System.currentTimeMillis()) throw PairingException("THAT CODE HAS RUN OUT")
        val pairId = entry.optString("p", "")
        if (entry.optString("o") == uid) throw PairingException("THAT'S YOUR OWN CODE")

        val pair = Cloud.get(context, "pairs/$pairId") as? JSONObject
            ?: throw PairingException("THAT CODE DIDN'T WORK")
        if (pair.has("b")) throw PairingException("THEY'RE ALREADY PAIRED")
        val theirKey = pair.optString("ka", "")
        if (theirKey.isEmpty()) throw PairingException("THAT CODE DIDN'T WORK")

        val keys = Crypto.newKeyPair()
        try {
            Cloud.patch(context, "pairs/$pairId", JSONObject().put("b", uid).put("kb", Crypto.publicOf(keys)))
        } catch (e: Cloud.CloudException) {
            if (e.code == 401 || e.code == 403) throw PairingException("THEY'RE ALREADY PAIRED")
            throw e
        }
        cancelCode(context)
        Us.savePair(context, pairId, "b", Crypto.sharedSecret(keys.private, theirKey, pairId))
    }

    /**
     * End the pairing for both phones: removes it from Firebase and forgets it here.
     * Throws if Firebase can't be reached, so it's never forgotten here but left there.
     */
    fun unpair(context: Context) {
        val pairId = Us.pairId(context)
        if (pairId != null) {
            try {
                Cloud.delete(context, "pairs/$pairId")
            } catch (e: Cloud.CloudException) {
                // 401/403: it's already gone (they unpaired first). Anything else, try again later.
                if (e.code != 401 && e.code != 403) throw e
            }
        }
        Us.forget(context)
    }

    /** True if the other phone has ended the pairing (the pair is gone from Firebase). */
    fun endedByThem(context: Context): Boolean {
        val pairId = Us.pairId(context) ?: return false
        return try {
            Cloud.get(context, "pairs/$pairId") == null
        } catch (e: Cloud.CloudException) {
            e.code == 401 || e.code == 403
        }
    }
}
