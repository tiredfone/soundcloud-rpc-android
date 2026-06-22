package app.tiredfone.sclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import app.tiredfone.sclient.databinding.ItemTrackBinding

class TrackAdapter(private val onTrackClick: (ScTrack) -> Unit) :
    RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    private val tracks = mutableListOf<ScTrack>()
    private var currentPlayingId: Long? = null
    private var likedIds: MutableSet<Long> = mutableSetOf()
    var onLongClick: ((ScTrack) -> Unit)? = null
    var onMoreClick: ((ScTrack) -> Unit)? = null

    inner class ViewHolder(private val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: ScTrack) {
            binding.tvTitle.text = track.displayTitle
            binding.tvArtist.text = track.displayArtist
            binding.ivArtwork.load(track.artworkHigh ?: track.artworkUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_notification)
            }
            binding.ivNowPlaying.visibility =
                if (track.id == currentPlayingId) View.VISIBLE else View.GONE

            binding.btnMore.setOnClickListener { onMoreClick?.invoke(track) }
            binding.root.setOnClickListener { onTrackClick(track) }
            binding.root.setOnLongClickListener { onLongClick?.invoke(track); true }
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
    }

    fun addLikedId(id: Long) {
        likedIds.add(id)
    }

    fun removeLikedId(id: Long) {
        likedIds.remove(id)
    }

    fun isLiked(id: Long): Boolean = id in likedIds
}
