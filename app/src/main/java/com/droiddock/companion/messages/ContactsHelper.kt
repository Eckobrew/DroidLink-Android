package com.droiddock.companion.messages

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log

class ContactsHelper(private val context: Context) {

    companion object {
        private const val TAG = "DroidDockContacts"
    }

    // Cache of normalized/clean numbers to display names
    private val contactCache = mutableMapOf<String, String>()
    private val lookupCache = mutableMapOf<String, String>()
    private var lastCacheTime: Long = 0
    private val CACHE_TTL_MS = 60_000 // 1 minute cache

    @Synchronized
    fun getContactMap(): Map<String, String> = refreshContactMap().toMap()

    private fun refreshContactMap(): Map<String, String> {
        val now = System.currentTimeMillis()
        if (lastCacheTime != 0L && (now - lastCacheTime < CACHE_TTL_MS)) {
            return contactCache
        }

        contactCache.clear()
        lookupCache.clear()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
                ),
                null,
                null,
                null
            )

            cursor?.use {
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val normIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)

                while (it.moveToNext()) {
                    val rawNum = it.getString(numIdx) ?: continue
                    val name = it.getString(nameIdx) ?: continue
                    val normNum = if (normIdx != -1) it.getString(normIdx) else null

                    val cleanNum = cleanNumber(rawNum)
                    if (cleanNum.isNotEmpty()) {
                        contactCache[cleanNum] = name
                    }
                    if (!normNum.isNullOrEmpty()) {
                        contactCache[cleanNumber(normNum)] = name
                    }
                }
            }
            lastCacheTime = now
            Log.d(TAG, "Cached ${contactCache.size} contact phone mappings")
        } catch (e: Exception) {
            Log.w(TAG, "Error caching contacts: ${e.message}")
        }
        return contactCache
    }

    @Synchronized
    fun resolveContactName(phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank()) return "Unknown"
        val clean = cleanNumber(phoneNumber)
        val map = refreshContactMap()

        lookupCache[phoneNumber]?.let { return it }

        // 1. Exact match in cache
        map[clean]?.let { return it }

        // 2. Suffix match (last 7-10 digits for international vs local dialing)
        if (clean.length >= 7) {
            val suffix = clean.takeLast(7)
            for ((key, value) in map) {
                if (key.endsWith(suffix)) {
                    return value
                }
            }
        }

        // 3. Query PhoneLookup as fallback
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToNext()) {
                    val name = it.getString(0)
                    if (!name.isNullOrBlank()) {
                        lookupCache[phoneNumber] = name
                        return name
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore phone lookup errors
        }

        lookupCache[phoneNumber] = phoneNumber
        return phoneNumber
    }

    private fun cleanNumber(number: String): String {
        return number.filter { it.isDigit() || it == '+' }
    }

    fun getAllContactsList(): List<com.droiddock.companion.network.ContactItem> {
        val contactsMap = mutableMapOf<String, Pair<String, MutableList<String>>>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val id = it.getString(idIdx) ?: continue
                    val name = it.getString(nameIdx) ?: continue
                    val number = it.getString(numIdx) ?: continue

                    val entry = contactsMap.getOrPut(id) { name to mutableListOf() }
                    if (!entry.second.contains(number)) {
                        entry.second.add(number)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching contacts list: ${e.message}", e)
        }

        return contactsMap.map { (id, pair) ->
            com.droiddock.companion.network.ContactItem(
                id = id,
                name = pair.first,
                phoneNumbers = pair.second
            )
        }.sortedBy { it.name.lowercase() }
    }
}
