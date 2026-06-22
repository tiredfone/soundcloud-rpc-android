package app.tiredfone.sclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog

object TrackOptionsSheet {
    fun show(
        activity: AppCompatActivity,
        track: ScTrack,
        isLiked: Boolean = false,
        onEnqueue: (() -> Unit)? = null,
        onAddToPlaylist: (() -> Unit)? = null,
        onLike: ((Boolean) -> Unit)? = null,
        onViewArtist: (() -> Unit)? = null,
        onShare: (() -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_track_options, null)
        dialog.setContentView(view)

        // Header
        view.findViewById<ImageView>(R.id.bsArtwork).load(track.artworkHigh ?: track.artworkUrl) {
            crossfade(true); placeholder(R.drawable.ic_notification)
        }
        view.findViewById<TextView>(R.id.bsTitle).text = track.displayTitle
        view.findViewById<TextView>(R.id.bsArtist).text = track.displayArtist

        // Like row
        val likeRow = view.findViewById<LinearLayout>(R.id.bsLikeRow)
        val likeIcon = view.findViewById<ImageView>(R.id.bsLikeIcon)
        val likeText = view.findViewById<TextView>(R.id.bsLikeText)
        var currentlyLiked = isLiked
        fun updateLikeRow() {
            likeIcon.setImageResource(if (currentlyLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            likeIcon.setColorFilter(if (currentlyLiked) 0xFFFF5500.toInt() else 0xFFAEAEB2.toInt())
            likeText.text = if (currentlyLiked) "Unlike" else "Like"
        }
        updateLikeRow()
        likeRow.setOnClickListener {
            currentlyLiked = !currentlyLiked
            onLike?.invoke(currentlyLiked)
            dialog.dismiss()
        }

        // Enqueue
        view.findViewById<LinearLayout>(R.id.bsEnqueueRow).apply {
            if (onEnqueue == null) visibility = View.GONE
            else setOnClickListener { onEnqueue.invoke(); dialog.dismiss() }
        }

        // Playlist
        view.findViewById<LinearLayout>(R.id.bsPlaylistRow).apply {
            if (onAddToPlaylist == null) visibility = View.GONE
            else setOnClickListener { onAddToPlaylist.invoke(); dialog.dismiss() }
        }

        // View artist
        view.findViewById<LinearLayout>(R.id.bsArtistRow).apply {
            if (onViewArtist == null) visibility = View.GONE
            else setOnClickListener { onViewArtist.invoke(); dialog.dismiss() }
        }

        // Share
        view.findViewById<LinearLayout>(R.id.bsShareRow).apply {
            if (onShare == null) visibility = View.GONE
            else setOnClickListener { onShare.invoke(); dialog.dismiss() }
        }

        dialog.show()
    }
}
