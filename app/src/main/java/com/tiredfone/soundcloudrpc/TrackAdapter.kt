package com.tiredfone.soundcloudrpc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.tiredfone.soundcloudrpc.databinding.ItemTrackBinding

class TrackAdapter(private val onTrackClick: (ScTrack) -> Unit) :
    RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    private val tracks = mutableListOf<ScTrack>()
    private var currentPlayingId: Long? = null
    private var likedIds: MutableSet<Long> = mutableSetOf()
    var onLikeClick: ((ScTrack, Boolean) -> Unit)? = null
    var onLongClick: ((ScTrack) -> Unit)? = null

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

            binding.btnLike.setImageResource(if (track.id in likedIds) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            binding.btnLike.setColorFilter(if (track.id in likedIds) 0xFFFF6600.toInt() else 0xFF888888.toInt())
            binding.btnLike.setOnClickListener {
                val nowLiked = track.id !in likedIds
                if (nowLiked) likedIds.add(track.id) else likedIds.remove(track.id)
                binding.btnLike.setImageResource(if (nowLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart)
                binding.btnLike.setColorFilter(if (nowLiked) 0xFFFF6600.toInt() else 0xFF888888.toInt())
                onLikeClick?.invoke(track, nowLiked)
            }

            binding.root.setOnClickListener { onTrackClick(track) }
            binding.root.setOnLongClickListener { onLongClick?.invoke(track); true }
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
