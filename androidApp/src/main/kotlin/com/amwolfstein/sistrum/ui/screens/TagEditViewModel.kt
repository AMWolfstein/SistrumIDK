package com.amwolfstein.sistrum.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amwolfstein.sistrum.library.LibraryRepository
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.ScanEntryEntity
import com.amwolfstein.sistrum.library.tags.TagEdit
import com.amwolfstein.sistrum.library.tags.TagWriteResult
import com.amwolfstein.sistrum.library.tags.TagWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class TagEditUiState(
    val item: LibraryItemEntity? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

class TagEditViewModel(
    context: Context,
    private val itemId: String,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val database = LibraryDatabase.get(appContext)
    private val dao = database.libraryDao()
    private val repository = LibraryRepository(context, database)
    private val writer = TagWriter(context, dao)
    private val _state = MutableStateFlow(TagEditUiState())
    val state: StateFlow<TagEditUiState> = _state

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(item = dao.itemById(itemId))
        }
    }

    fun save(edit: TagEdit) {
        val item = _state.value.item ?: return
        if (_state.value.saving) return
        _state.value = _state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            when (val result = writer.write(item, edit)) {
                is TagWriteResult.Success -> {
                    val source = dao.source(item.sourceId)
                    if (source != null) {
                        runCatching {
                            repository.publishEntry(
                                source,
                                ScanEntryEntity(
                                    runId = "tag-write",
                                    documentKey = item.documentKey,
                                    fileUri = item.fileUri,
                                    modTime = System.currentTimeMillis(),
                                    size = item.fileSize,
                                    state = "SEEN",
                                    relativePath = item.displayPath.orEmpty(),
                                    name = item.displayPath?.substringAfterLast('/').orEmpty(),
                                    changed = true,
                                ),
                            )
                        }
                        runCatching {
                            val artwork = com.amwolfstein.sistrum.library.artwork.ArtworkLibraryService(appContext)
                            artwork.refreshItemArtwork(item.id)
                            artwork.refreshArtistsForItem(item.id)
                        }
                    }
                    _state.value = _state.value.copy(saving = false, saved = true)
                }
                is TagWriteResult.Failure ->
                    _state.value = _state.value.copy(saving = false, error = result.reason)
            }
        }
    }
}
