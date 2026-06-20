package app.tiredfone.sclient

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import app.tiredfone.sclient.databinding.ItemPlaylistBinding

class PlaylistAdapter(private val onClick: (ScPlaylist) -> Unit) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {
    private val items = mutableListOf<ScPlaylist>()

    inner class ViewHolder(val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(pl: ScPlaylist) {
            binding.tvTitle.text = pl.displayTitle
            binding.tvTrackCount.text = "${pl.trackCount} tracks"
            binding.ivArtwork.load(pl.artworkHigh ?: pl.artworkUrl) { crossfade(true) }
            binding.root.setOnClickListener { onClick(pl) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    fun setPlaylists(list: List<ScPlaylist>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun appendPlaylists(list: List<ScPlaylist>) {
        val s = items.size
        items.addAll(list)
        notifyItemRangeInserted(s, list.size)
    }
}
