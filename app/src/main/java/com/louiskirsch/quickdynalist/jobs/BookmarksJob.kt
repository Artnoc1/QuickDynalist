package com.louiskirsch.quickdynalist.jobs

import com.birbit.android.jobqueue.RetryConstraint
import com.birbit.android.jobqueue.CancelReason
import com.birbit.android.jobqueue.Job
import com.birbit.android.jobqueue.Params
import com.louiskirsch.quickdynalist.BookmarkContentsUpdatedEvent
import com.louiskirsch.quickdynalist.BookmarksUpdatedEvent
import com.louiskirsch.quickdynalist.Dynalist
import com.louiskirsch.quickdynalist.DynalistApp
import com.louiskirsch.quickdynalist.network.AuthenticatedRequest
import com.louiskirsch.quickdynalist.network.ReadDocumentRequest
import org.greenrobot.eventbus.EventBus
import org.jetbrains.annotations.Nullable
import java.io.Serializable
import java.util.*


class BookmarksJob(val largest_k: Int = 9)
    : Job(Params(-1).requireUnmeteredNetwork().singleInstanceBy("bookmarks")) {

    override fun onAdded() {}

    @Throws(Throwable::class)
    override fun onRun() {
        val dynalist = Dynalist(applicationContext)
        val token = dynalist.token
        val service = DynalistApp.instance.dynalistService

        val files = service.listFiles(AuthenticatedRequest(token!!)).execute().body()!!.files!!
        val documents = files.filter { it.isDocument && it.isEditable }
        val contents = documents.map {
            service.readDocument(ReadDocumentRequest(it.id!!, token)).execute().body()!!.nodes!!
        }

        val candidates = documents.zip(contents).flatMap {
            (doc, content) -> content.map { Bookmark(doc.id, it.parent, it.id, it.content, it.note,
                it.children ?: emptyList()) }
        }
        val itemMap = candidates.associateBy { it.absoluteId!! }
        val itemByNameMap = candidates.groupBy { it.name }

        // TEMPORARY FIX - MISTAKE IN API SPEC
        candidates.forEach { it.fixParents(itemMap) }

        val previousInbox = dynalist.bookmarks.first { it.isInbox }
        val guessedParents = previousInbox.children.filter { it.id == null }
                .flatMap {
                    itemByNameMap[it.name]?.mapNotNull { match ->
                        itemMap[Pair(match.file_id, match.parent)]
                    }
                    ?: emptyList()
                }
        val newInbox = guessedParents
                .groupingBy { it }
                .eachCount()
                .maxBy { it.value } ?.key ?: previousInbox
        newInbox.apply {
            isInbox = true
            populateChildren(itemMap)
        }


        val markedItems = candidates.filter { it.markedAsBookmark }
        val bookmarks = if (markedItems.isEmpty()) {
            candidates.filter { it.childrenCount > 10 && !it.mightBeInbox }
                    .sortedByDescending { it.childrenCount }
                    .take(largest_k)
        } else {
            markedItems
        }
        bookmarks.forEach { it.populateChildren(itemMap) }

        val bookmarksInclInbox = (listOf(newInbox) + bookmarks).toTypedArray()
        dynalist.bookmarks = bookmarksInclInbox
        EventBus.getDefault().also {
            it.post(BookmarksUpdatedEvent(bookmarksInclInbox))
            bookmarksInclInbox.map { b -> it.post(BookmarkContentsUpdatedEvent(b)) }
        }

    }

    override fun onCancel(@CancelReason cancelReason: Int, @Nullable throwable: Throwable?) {}

    override fun shouldReRunOnThrowable(throwable: Throwable, runCount: Int,
                                        maxRunCount: Int): RetryConstraint {
        return RetryConstraint.createExponentialBackoff(runCount, 10 * 1000)
    }
}

class Bookmark(val file_id: String?, var parent: String?, val id: String?, val name: String,
               val note: String, private val childrenIds: List<String>,
               var isInbox: Boolean = false) : Serializable {

    val clientId = random.nextLong()
    var children: MutableList<Bookmark> = ArrayList()

    override fun toString() = shortenedName

    val shortenedName: String get() {
        val label = strippedMarkersName
        return if (label.length > 30)
            "${label.take(27)}..."
        else
            label
    }

    val absoluteId: Pair<String, String>? get() {
        if (file_id == null || id == null)
            return null
        return Pair(file_id, id)
    }

    fun populateChildren(itemMap: Map<Pair<String, String>, Bookmark>, maxDepth: Int = 1) {
        children = childrenIds.map { itemMap[Pair(file_id, it)]!! } .toMutableList()
        if (maxDepth > 1)
            children.forEach { it.populateChildren(itemMap, maxDepth - 1) }
    }

    fun fixParents(itemMap: Map<Pair<String, String>, Bookmark>) {
        // TODO Currently the API does not send the parent according to the spec
        childrenIds.forEach { itemMap[Pair(file_id, it)]!!.parent = id }
    }

    val childrenCount: Int
        get() = childrenIds.size

    val mightBeInbox: Boolean
        get() = name.toLowerCase() == "inbox"

    val markedAsBookmark: Boolean
        get() = emojiMarkers.any { name.contains(it, true)
                                || note.contains(it, true) } ||
                tagMarkers.any   { name.contains(" $it", true)
                                || name.startsWith(it, true)
                                || note.contains(" $it", true)
                                || note.startsWith(it, true) }

    private val strippedMarkersName: String
        get() = markers.fold(name) { acc, marker ->
            acc.replace(marker, "", true) }
                .replace("# ", "")
                .trim()

    companion object {
        fun newInbox() = Bookmark(null, null, "inbox",
                "\uD83D\uDCE5 Inbox", "", emptyList(), isInbox = true)
        private val tagMarkers = listOf("#quickdynalist", "#inbox")
        private val emojiMarkers = listOf("📒", "📓", "📔", "📕", "📖", "📗", "📘", "📙")
        private val markers = tagMarkers + emojiMarkers
        private val random = Random()
    }
}
