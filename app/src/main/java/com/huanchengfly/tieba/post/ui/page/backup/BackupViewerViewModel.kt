package com.huanchengfly.tieba.post.ui.page.backup

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import com.huanchengfly.tieba.post.arch.BaseStateViewModel
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.backup.BackupContentItem
import com.huanchengfly.tieba.post.backup.BackupData
import com.huanchengfly.tieba.post.backup.BackupImageContentRender
import com.huanchengfly.tieba.post.backup.BackupRepository
import com.huanchengfly.tieba.post.backup.BackupVideoContentRender
import com.huanchengfly.tieba.post.backup.imageKeyOrUrl
import com.huanchengfly.tieba.post.ui.common.PbContentRender
import com.huanchengfly.tieba.post.ui.common.TextContentRender
import com.huanchengfly.tieba.post.ui.models.LikeZero
import com.huanchengfly.tieba.post.ui.models.PostData
import com.huanchengfly.tieba.post.ui.models.UserData
import com.huanchengfly.tieba.post.ui.page.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject

data class BackupViewerUiState(
    val isLoading: Boolean = true,
    val backup: BackupData? = null,
    /** Directory containing extracted images for offline viewing (null if no ZIP or not yet extracted). */
    val imagesDir: File? = null,
    /** [PostData] built from [backup] for rendering with the same [PostCard] as the online viewer. */
    val postData: PostData? = null,
    val error: Throwable? = null,
) : UiState

@HiltViewModel
class BackupViewerViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    savedStateHandle: SavedStateHandle,
) : BaseStateViewModel<BackupViewerUiState>() {

    private val threadId: Long = savedStateHandle.toRoute<Destination.BackupViewer>().threadId

    override fun createInitialState() = BackupViewerUiState()

    init {
        loadBackup()
    }

    fun loadBackup() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        launchInVM {
            val backup = backupRepository.getBackupByThreadId(threadId)
            // Extract the companion ZIP to a temp cache dir so images can be loaded offline.
            val imagesDir = if (backup != null) {
                backupRepository.extractImagesToCache(threadId)
            } else null
            val postData = backup?.toPostData(imagesDir)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    backup = backup,
                    imagesDir = imagesDir,
                    postData = postData,
                    error = if (backup == null) Exception("Backup not found") else null,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Converts a [BackupData] into a [PostData] so the backup can be displayed using the same
 * [com.huanchengfly.tieba.post.ui.page.thread.PostCard] composable as the online thread viewer.
 */
private fun BackupData.toPostData(imagesDir: File?): PostData {
    // Resolve author avatar: prefer local file, fall back to network URL.
    val authorAvatarUrl: String = when (
        val m = imageKeyOrUrl(imagesDir, imageKeyAuthorAvatar, authorAvatar)
    ) {
        is File -> m.absolutePath
        is String -> m
        else -> authorAvatar
    }

    val author = UserData(
        id = authorId,
        name = authorName,
        nameShow = authorName,
        showBothName = false,
        avatarUrl = authorAvatarUrl,
        portrait = "",
        ip = "",
        levelId = 0,
        bawuType = null,
        isLz = true,
    )

    val renders: List<PbContentRender> = contentItems.map { item ->
        when (item) {
            is BackupContentItem.Text -> TextContentRender(item.content)

            is BackupContentItem.Image -> BackupImageContentRender(
                model = imageKeyOrUrl(
                    imagesDir,
                    item.imageKey,
                    item.originUrl.takeIf { it.isNotBlank() } ?: item.url,
                )
            )

            is BackupContentItem.Video -> BackupVideoContentRender(
                coverModel = imageKeyOrUrl(imagesDir, item.imageKeyCover, item.coverUrl),
                videoUrl = item.videoUrl,
                webUrl = item.webUrl,
            )
        }
    }

    val plainText = contentItems
        .filterIsInstance<BackupContentItem.Text>()
        .joinToString("\n") { it.content }

    return PostData(
        id = threadId,
        author = author,
        floor = 1,
        title = title,
        time = backupTime,
        like = LikeZero,
        blocked = false,
        plainText = plainText,
        contentRenders = renders,
        subPosts = null,
        subPostNumber = 0,
    )
}
