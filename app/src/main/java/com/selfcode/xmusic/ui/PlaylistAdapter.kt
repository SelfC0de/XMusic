package com.selfcode.xmusic.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.selfcode.xmusic.R
import com.selfcode.xmusic.data.MusicStorage
import com.selfcode.xmusic.databinding.ItemPlaylistBinding
import com.selfcode.xmusic.ui.views.BounceEffect
import java.io.File

class PlaylistAdapter(
    private val onClick: (MusicStorage.Playlist) -> Unit,
    private val onEdit: ((MusicStorage.Playlist) -> Unit)? = null,
    private val onDelete: (MusicStorage.Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    private val items = mutableListOf<MusicStorage.Playlist>()

    inner class VH(val b: ItemPlaylistBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val pl = items[position]
        val b = holder.b

        b.tvPlaylistName.text = pl.name
        val count = pl.trackUrls.size
        b.tvTrackCount.text = "$count ${trackWord(count)}"

        if (pl.coverPath.isNotEmpty() && File(pl.coverPath).exists()) {
            b.imgPlaylistCover.setImageURI(Uri.fromFile(File(pl.coverPath)))
        } else {
            b.imgPlaylistCover.setImageResource(R.drawable.ic_library)
        }

        b.cardRoot.setOnClickListener { onClick(pl) }

        b.btnEditPlaylist.isVisible = onEdit != null
        b.btnEditPlaylist.setOnClickListener { onEdit?.invoke(pl) }
        b.btnDeletePlaylist.setOnClickListener { onDelete(pl) }

        BounceEffect.apply(b.btnEditPlaylist, b.btnDeletePlaylist)

        b.root.alpha = 0f
        b.root.translationY = 20f
        b.root.animate().alpha(1f).translationY(0f)
            .setDuration(300)
            .setStartDelay((position % 10 * 30).toLong())
            .setInterpolator(OvershootInterpolator(0.8f))
            .start()
    }

    fun submit(list: List<MusicStorage.Playlist>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    private fun trackWord(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod100 in 11..19 -> "треков"
            mod10 == 1 -> "трек"
            mod10 in 2..4 -> "трека"
            else -> "треков"
        }
    }
}
