package app.tiredfone.sclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import coil.load
import app.tiredfone.sclient.databinding.ItemTrackBinding

class TrackAdapter(private val onTrackClick: (ScTrack) -> Unit) :
    RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    private val tracks = mutableListOf<ScTrack>()
    private var currentPlayingId: Long? = null
    private var likedIds: MutableSet<Long> = mutableSetOf()
    var onLongClick: ((ScTrack) -> Unit)? = null
    var onAddToPlaylistClick: ((ScTrack) -> Unit)? = null
    var onViewProfileClick: ((ScTrack) -> Unit)? = null
    var onLikeClick: ((ScTrack, Boolean) -> Unit)? = null
    var onEnqueueClick: ((ScTrack) -> Unit)? = null
    var onShareClick: ((ScTrack) -> Unit)? = null

    inner class ViewHolder(private val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: ScTrack) {
            binding.tvTitle.text = track.displayTitle
            binding.tvArtist.text = track.displayArtist
            binding.tvDuration.text = formatDuration(track.duration)
            binding.ivArtwork.load(track.artworkHigh ?: track.artworkUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_notification)
            }
            binding.ivNowPlaying.visibility =
                if (track.id == currentPlayingId) View.VISIBLE else View.GONE

            updateLikeIcon(track)
            binding.btnLike.setOnClickListener {
                val nowLiked = track.id !in likedIds
                if (nowLiked) addLikedId(track.id) else removeLikedId(track.id)
                onLikeClick?.invoke(track, nowLiked)
            }

            binding.btnMore.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                popup.menuInflater.inflate(R.menu.menu_track_options, popup.menu)
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_add_to_queue -> { onEnqueueClick?.invoke(track); true }
                        R.id.action_add_to_playlist -> { onAddToPlaylistClick?.invoke(track); true }
                        R.id.action_view_profile -> { onViewProfileClick?.invoke(track); true }
                        R.id.action_share -> { onShareClick?.invoke(track); true }
                        else -> false
                    }
                }
                popup.show()
            }

            binding.root.setOnClickListener { onTrackClick(track) }
            binding.root.setOnLongClickListener { onLongClick?.invoke(track); true }
        }

        private fun updateLikeIcon(track: ScTrack) {
            val isLiked = track.id in likedIds
            binding.btnLike.setImageResource(if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            binding.btnLike.setColorFilter(if (isLiked) 0xFFFF5500.toInt() else 0xFFAEAEB2.toInt())
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(tracks[position])
    }

    override fun getItemCount(): Int = tracks.size

    fun setTracks(list: List<ScTrack>) {
        tracks.clear()
        tracks.addAll(list)
        notifyDataSetChanged()
    }

    fun appendTracks(list: List<ScTrack>) {
        val start = tracks.size
        tracks.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun setCurrentTrack(id: Long?) {
        currentPlayingId = id
        notifyDataSetChanged()
    }

    fun setLikedIds(ids: Set<Long>) {
        likedIds = ids.toMutableSet()
        notifyDataSetChanged()
    }

    fun addLikedId(id: Long) {
        likedIds.add(id)
        notifyDataSetChanged()
    }

    fun removeLikedId(id: Long) {
        likedIds.remove(id)
        notifyDataSetChanged()
    }
}
