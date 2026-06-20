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
            binding.root.setOnClickListener { onTrackClick(track) }
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
}
