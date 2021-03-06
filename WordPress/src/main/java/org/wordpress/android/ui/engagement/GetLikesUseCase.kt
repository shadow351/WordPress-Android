package org.wordpress.android.ui.engagement

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.wordpress.android.R
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.action.CommentAction.FETCHED_COMMENT_LIKES
import org.wordpress.android.fluxc.generated.CommentActionBuilder
import org.wordpress.android.fluxc.generated.PostActionBuilder
import org.wordpress.android.fluxc.model.CauseOfOnPostChanged.FetchPostLikes
import org.wordpress.android.fluxc.model.LikeModel
import org.wordpress.android.fluxc.store.CommentStore
import org.wordpress.android.fluxc.store.CommentStore.FetchCommentLikesPayload
import org.wordpress.android.fluxc.store.CommentStore.OnCommentLikesChanged
import org.wordpress.android.fluxc.store.PostStore
import org.wordpress.android.fluxc.store.PostStore.FetchPostLikesPayload
import org.wordpress.android.fluxc.store.PostStore.OnPostLikesChanged
import org.wordpress.android.fluxc.store.Store.OnChanged
import org.wordpress.android.ui.engagement.GetLikesUseCase.FailureType.GENERIC
import org.wordpress.android.ui.engagement.GetLikesUseCase.FailureType.NO_NETWORK
import org.wordpress.android.ui.engagement.GetLikesUseCase.GetLikesState.Failure
import org.wordpress.android.ui.engagement.GetLikesUseCase.GetLikesState.Failure.EmptyStateData
import org.wordpress.android.ui.engagement.GetLikesUseCase.GetLikesState.Loading
import org.wordpress.android.ui.engagement.GetLikesUseCase.GetLikesState.LikesData
import org.wordpress.android.ui.engagement.GetLikesUseCase.LikeCategory.COMMENT_LIKE
import org.wordpress.android.ui.engagement.GetLikesUseCase.LikeCategory.POST_LIKE
import org.wordpress.android.ui.utils.UiString
import org.wordpress.android.ui.utils.UiString.UiStringRes
import org.wordpress.android.ui.utils.UiString.UiStringText
import org.wordpress.android.util.AppLog
import org.wordpress.android.util.AppLog.T
import org.wordpress.android.util.NetworkUtilsWrapper
import javax.inject.Inject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// NOTE: Do not remove the commentStore and postStore fields; commentStore seems needed so that the store is registered
// when we dispatch events; postStore added to keep the rational even if not strictly needed as of today.
// Possibly there is a better way, in that case please update here.
class GetLikesUseCase @Inject constructor(
    private val networkUtilsWrapper: NetworkUtilsWrapper,
    private val dispatcher: Dispatcher,
    @SuppressWarnings("Unused")
    val commentStore: CommentStore,
    @SuppressWarnings("Unused")
    val postStore: PostStore
) {
    private var getLikesContinuations = mutableMapOf<String, Continuation<OnChanged<*>>>()

    init {
        dispatcher.register(this)
    }

    fun clear() {
        dispatcher.unregister(this)
    }

    suspend fun getLikesForPost(
        siteId: Long,
        postId: Long,
        numLikes: Int
    ): Flow<GetLikesState> = flow {
        getLikes(POST_LIKE, this, siteId, postId, numLikes)
    }

    suspend fun getLikesForComment(
        siteId: Long,
        commentId: Long,
        numLikes: Int
    ): Flow<GetLikesState> = flow {
        getLikes(COMMENT_LIKE, this, siteId, commentId, numLikes)
    }

    private suspend fun getLikes(
        category: LikeCategory,
        flow: FlowCollector<GetLikesState>,
        siteId: Long,
        entityId: Long,
        numLikes: Int
    ) {
        flow.emit(Loading)
        delay(PROGRESS_DELAY_MS)

        val noNetworkDetected = !networkUtilsWrapper.isNetworkAvailable()

        val event = suspendCoroutine<OnChanged<*>> {
            getLikesContinuations[category.getActionKey(siteId, entityId)] = it
            when (category) {
                POST_LIKE -> {
                    val payload = FetchPostLikesPayload(siteId, entityId)
                    dispatcher.dispatch(PostActionBuilder.newFetchPostLikesAction(payload))
                }
                COMMENT_LIKE -> {
                    val payload = FetchCommentLikesPayload(siteId, entityId)
                    dispatcher.dispatch(CommentActionBuilder.newFetchCommentLikesAction(payload))
                }
            }
        }

        val isPostLikeEvent = category == POST_LIKE && event is OnPostLikesChanged
        val isCommentLikeEvent = category == COMMENT_LIKE && event is OnCommentLikesChanged

        if (isPostLikeEvent || isCommentLikeEvent) {
            var likes = listOf<LikeModel>()
            var errorMessage: String? = null

            if (event is OnPostLikesChanged) {
                likes = event.postLikes
                if (event.isError) errorMessage = event.error.message
            }

            if (event is OnCommentLikesChanged) {
                likes = event.commentLikes
                if (event.isError) errorMessage = event.error.message
            }

            flow.emit(
                    if (event.isError) {
                        getFailureState(noNetworkDetected, likes, errorMessage, numLikes)
                    } else {
                        LikesData(likes, numLikes)
                    }
            )
        }
    }

    private fun getFailureState(
        noNetworkDetected: Boolean,
        orderedLikes: List<LikeModel>,
        errorMessage: String?,
        expectedNumLikes: Int
    ): Failure {
        return if (noNetworkDetected) {
            Failure(
                    NO_NETWORK,
                    UiStringRes(R.string.get_likes_no_network_error),
                    orderedLikes,
                    EmptyStateData(
                            orderedLikes.isEmpty(),
                            UiStringRes(R.string.no_network_title)
                    ),
                    expectedNumLikes
            )
        } else {
            Failure(
                    GENERIC,
                    if (errorMessage.isNullOrEmpty()) {
                        UiStringRes(R.string.get_likes_unknown_error)
                    } else {
                        UiStringText(errorMessage)
                    },
                    orderedLikes,
                    EmptyStateData(
                            orderedLikes.isEmpty(),
                            UiStringRes(R.string.get_likes_empty_state_title)
                    ),
                    expectedNumLikes
            )
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onPostLikesChanged(event: OnPostLikesChanged) {
        if (event.causeOfChange !is FetchPostLikes) {
            AppLog.d(T.POSTS, "GetLikesUseCase > unexpected event cause received [${event.causeOfChange}]")
            return
        }

        val key = POST_LIKE.getActionKey(event.siteId, event.postId)

        getLikesContinuations[key]?.let {
            it.resume(event)
            getLikesContinuations.remove(key)
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onCommentLikesChanged(event: OnCommentLikesChanged) {
        if (event.causeOfChange != FETCHED_COMMENT_LIKES) {
            AppLog.d(T.COMMENTS, "GetLikesUseCase > unexpected event cause received [${event.causeOfChange}]")
            return
        }

        val key = COMMENT_LIKE.getActionKey(event.siteId, event.commentId)

        getLikesContinuations[key]?.let {
            it.resume(event)
            getLikesContinuations.remove(key)
        }
    }

    sealed class GetLikesState {
        object Loading : GetLikesState()

        data class LikesData(val likes: List<LikeModel>, val expectedNumLikes: Int) : GetLikesState()

        data class Failure(
            val failureType: FailureType,
            val error: UiString,
            val cachedLikes: List<LikeModel>,
            val emptyStateData: EmptyStateData,
            val expectedNumLikes: Int
        ) : GetLikesState() {
            data class EmptyStateData(
                val showEmptyState: Boolean,
                val title: UiStringRes? = null
            )
        }
    }

    // Extend error categories if appropriate
    enum class FailureType {
        NO_NETWORK,
        GENERIC
    }

    enum class LikeCategory {
        POST_LIKE,
        COMMENT_LIKE;

        fun getActionKey(siteId: Long, entityId: Long): String {
            return "${this.name}-$siteId-$entityId"
        }
    }

    companion object {
        // Pretty arbitrary amount to allow the loading state to appear to the user
        private const val PROGRESS_DELAY_MS = 600L
    }
}
