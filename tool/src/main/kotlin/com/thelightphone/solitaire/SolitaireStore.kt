package com.thelightphone.solitaire

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Keeps the deal across launches. The SDK hands every tool a
 * `DataStore<Preferences>` through `lightContext`, which is plenty for one line
 * of text.
 */
class SolitaireStore(private val dataStore: DataStore<Preferences>) {

    private val key = stringPreferencesKey(KEY_NAME)

    suspend fun load(): Game? = SaveState.decode(dataStore.data.first()[key])

    suspend fun save(game: Game) {
        dataStore.edit { it[key] = SaveState.encode(game) }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }

    private companion object {
        const val KEY_NAME = "solitaire_game"
    }
}
